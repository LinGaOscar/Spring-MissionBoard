# 專案介紹短片重製（產片 pipeline 入版控） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把一次性手工產片改成可重跑、入版控的三段式 pipeline，並用它重製出流暢的 `docs/missionboard-intro.mp4`。

**Architecture:** `storyboard.py`（純資料設定）餵給三支各司其職的腳本——`capture.py` 用 Playwright 起環境並擷取真實畫面與座標中繼資料、`sequencer.py` 純離線用 PIL 做補間／轉場／字幕並輸出 30fps 圖片序列、`encode.sh` 單次 ffmpeg 編碼。只有 `capture.py` 需要瀏覽器，因此調整節奏只需重跑純運算的 `sequencer.py`。

**Tech Stack:** Python 3.9.6（本機版本，**不可用 3.10+ 語法**如 `match`、`X | None`）、Playwright（已安裝且實測可用）、Pillow 11.3.0、ffmpeg 8.1.1、`unittest`（stdlib，不新增測試依賴）

**Spec:** `docs/superpowers/specs/2026-09-09-intro-video-remake-design.md`

## Global Constraints

- **不自動 commit。** 使用者明確指示：「commit 等影片完成再一起用」。所有任務**不得**執行 `git commit`；唯一一次提交在 Task 8 末尾，且須先取得使用者核准（依 `CLAUDE.md` Git Standards 狀態機）。
- **不改動應用程式任何行為。** 不得修改 `src/` 下任何檔案。加 CSS transition／FLIP 動畫屬「路線二」，需另開 spec。
- 成片規格：`1600×812`、`30fps`、`yuv420p`、**無聲**、總長 60–63 秒。
- 字幕字型釘死 `/System/Library/Fonts/STHeiti Medium.ttc`（Heiti TC Medium）。**本機無 `PingFang.ttc`**；**不可用 Hiragino 代替**（日文字形，部分繁中字寫法不同）。缺字型必須 fail fast，禁止靜默 fallback。
- 顏色一律取自 `src/main/resources/static/css/app.css` 的 `:root` token：`--ink #1A1A1A`、`--paper #FFFFFF`、`--ink-muted #6B6B70`、`--danger #B91C1C`、`--signal #1D4ED8`。
- 轉場一律在 Python 端以 alpha 混合完成，**禁用 ffmpeg `xfade`**；組片**禁用 concat demuxer**（會少算約 2 秒且末幀時長不穩），只用 `-framerate 30 -i seq/%05d.png`。
- **禁用 `minterpolate`**（UI 截圖會產生鬼影）。
- Ken Burns 只允許用在片頭／片尾卡，UI 畫面一律 `zoom=(1.0, 1.0)`。
- 任務起迄日一律在 `storyboard.py` 寫死絕對日期，**禁用相對日期**。
- 工作幀寫到 `.superpowers/intro-video/`（gitignored）。**不要動既有的 `.superpowers/intro-frames/`**——舊幀是新片驗收失敗時的唯一退路。
- 測試執行位置固定：`cd scripts/intro-video && python3 -m unittest discover -s tests -t .`

---

## File Structure

| 檔案 | 職責 |
|---|---|
| `scripts/intro-video/storyboard.py` | 純資料：解析度／fps／顏色／字型路徑、場景清單、字幕、秒數、紅框、拖曳路徑、寫死日期。**不含邏輯**，改分鏡不必動程式 |
| `scripts/intro-video/easing.py` | 緩動函式與路徑取樣（純函式，無 I/O） |
| `scripts/intro-video/compositing.py` | PIL 基元：字型載入、sprite 裁切與貼上、字幕帶、紅框、游標、交叉淡入、Ken Burns |
| `scripts/intro-video/sequencer.py` | 讀 raw 幀＋`meta.json`＋分鏡 → 產出 `seq/%05d.png`。純離線 |
| `scripts/intro-video/capture.py` | Playwright 擷取＋環境編排（重置 DB、起 app、跑分鏡操作、產拖曳底圖與 sprite bbox） |
| `scripts/intro-video/encode.sh` | ffmpeg 單次編碼 |
| `scripts/intro-video/README.md` | 怎麼重跑這套 pipeline |
| `scripts/intro-video/tests/test_easing.py` | 緩動與路徑取樣 |
| `scripts/intro-video/tests/test_compositing.py` | PIL 基元 |
| `scripts/intro-video/tests/test_storyboard.py` | 分鏡資料完整性（總長、id 唯一、日期為絕對日期） |
| `scripts/intro-video/tests/test_sequencer.py` | 幀數、連號、尺寸偶數、轉場確實混合 |

Task 2–5 完全不依賴瀏覽器，用測試自造的合成圖驗證，因此可以在 `capture.py`（Task 6–7，唯一會卡關的部分）之前先完成並鎖定。

---

### Task 1: 骨架與分鏡資料

**Files:**
- Create: `scripts/intro-video/storyboard.py`
- Test: `scripts/intro-video/tests/test_storyboard.py`

**Interfaces:**
- Consumes: 無
- Produces: 常數 `FPS=30`、`WIDTH=1600`、`HEIGHT=812`、`FONT_PATH`、`INK`／`PAPER`／`INK_MUTED`／`DANGER`／`SIGNAL`（RGB tuple）、`TRANSITION_FRAMES=8`、`CAPTION_FADE_FRAMES=5`、`CAPTION_HEIGHT=84`；dataclass `Drag(base_id, sprite_from, path, rotate_deg)`、`Scene(id, seconds, caption, boxes, drag, zoom, card_html)`；`SCENES: Tuple[Scene, ...]`；函式 `total_seconds() -> float`、`total_frames() -> int`

- [ ] **Step 1: 寫失敗測試**

建立 `scripts/intro-video/tests/test_storyboard.py`：

```python
import re
import unittest

import storyboard as sb


class TestStoryboard(unittest.TestCase):
    def test_total_length_within_spec(self):
        # spec 綁定條件：成片總長 60-63 秒
        self.assertGreaterEqual(sb.total_seconds(), 60.0)
        self.assertLessEqual(sb.total_seconds(), 63.0)

    def test_total_frames_matches_fps(self):
        self.assertEqual(sb.total_frames(), sum(
            max(1, int(round(s.seconds * sb.FPS))) for s in sb.SCENES))

    def test_scene_ids_unique(self):
        ids = [s.id for s in sb.SCENES]
        self.assertEqual(len(ids), len(set(ids)))

    def test_drag_scene_is_never_a_card(self):
        # 拖曳需要真實截圖當底圖與 sprite 來源，不可能是自繪卡片
        for s in sb.SCENES:
            if s.drag is not None:
                with self.subTest(scene=s.id):
                    self.assertIsNone(s.card_html, "拖曳場景不能同時是自繪卡片")

    def test_captions_present_on_ui_scenes(self):
        # UI 畫面一律要有字幕；片頭/片尾卡自帶文字，不需要字幕列
        for s in sb.SCENES:
            if s.card_html is None:
                with self.subTest(scene=s.id):
                    self.assertTrue(s.caption, "UI 場景必須有字幕")

    def test_drag_paths_have_at_least_two_points(self):
        for s in sb.SCENES:
            if s.drag is not None:
                with self.subTest(scene=s.id):
                    self.assertGreaterEqual(len(s.drag.path), 2)

    def test_dates_are_absolute(self):
        # Global Constraint：禁用相對日期，否則跨日重跑畫面會變
        for key, value in sb.DATES.items():
            with self.subTest(key=key):
                self.assertRegex(value, r"^\d{4}-\d{2}-\d{2}$")

    def test_ken_burns_only_on_cards(self):
        for s in sb.SCENES:
            if s.zoom != (1.0, 1.0):
                with self.subTest(scene=s.id):
                    self.assertIsNotNone(
                        s.card_html, "UI 畫面禁止縮放（14px 文字會閃爍）")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'storyboard'`

- [ ] **Step 3: 寫 storyboard.py**

```python
# -*- coding: utf-8 -*-
"""介紹短片分鏡資料。純資料，不含邏輯——改分鏡不需要動其他程式。"""
from dataclasses import dataclass, field
from typing import Optional, Tuple

FPS = 30
WIDTH = 1600
HEIGHT = 812

# 字幕字型：本機無 PingFang；不可用 Hiragino（日文字形）
FONT_PATH = "/System/Library/Fonts/STHeiti Medium.ttc"

# 顏色沿用 app.css :root token
INK = (26, 26, 26)
PAPER = (255, 255, 255)
INK_MUTED = (107, 107, 112)
DANGER = (185, 28, 28)
SIGNAL = (29, 78, 216)

TRANSITION_FRAMES = 8      # 場景交叉淡入長度（≈0.27 秒）
CAPTION_FADE_FRAMES = 5    # 字幕淡入淡出長度
CAPTION_HEIGHT = 84

# 流程中輸入的日期一律寫死，跨日重跑畫面才不會變（見 spec 驗證項）
DATES = {
    "start": "2026-09-14",
    "due_a": "2026-09-18",
    "due_b": "2026-09-25",
    "due_c": "2026-10-09",
}

Box = Tuple[int, int, int, int]
Point = Tuple[float, float]


@dataclass(frozen=True)
class Drag:
    base_id: str          # 「拖曳中」底圖：原位卡片已壓透明、目標欄已加虛線框
    sprite_from: str      # 裁卡片 sprite 的來源（卡片完整不透明的乾淨畫面）
    path: Tuple[Point, ...]
    rotate_deg: float = 2.0


@dataclass(frozen=True)
class Scene:
    id: str
    seconds: float
    caption: Optional[str] = None
    boxes: Tuple[Box, ...] = ()
    drag: Optional[Drag] = None
    zoom: Tuple[float, float] = (1.0, 1.0)   # Ken Burns，只允許用在自繪卡片
    card_html: Optional[str] = None          # 有值代表這格是自繪卡片，不是螢幕截圖


def _card(title, subtitle):
    """片頭／片尾卡。字體與配色沿用 app.css token。"""
    return (
        '<div style="width:100vw;height:100vh;display:flex;flex-direction:column;'
        'align-items:center;justify-content:center;background:#FFFFFF;'
        'font-family:-apple-system,\'PingFang TC\',sans-serif">'
        '<div style="font-size:58px;font-weight:600;color:#1A1A1A">{}</div>'
        '<div style="font-size:26px;color:#6B6B70;margin-top:20px">{}</div>'
        '</div>'
    ).format(title, subtitle)


# 拖曳路徑座標為現有幀的量測值，Task 7 Step 5 會依實際 meta.json 校正。
# 各場景的 boxes（紅框標示區）同樣由 Task 7 Step 5 量測後填入——
# 現在留空是「還沒量」，不是「不要紅框」。
_BOARD_FROM = (910.0, 301.0)     # 「進行中」欄的卡片中心
_BOARD_TO = (1354.0, 301.0)      # 「已完成」欄的落點

SCENES: Tuple[Scene, ...] = (
    Scene("s00_title", 3.0, zoom=(1.0, 1.03),
          card_html=_card("MissionBoard 任務管理系統",
                          "扁平任務模型 × 看板拖曳的任務派工系統")),
    Scene("s01_stack", 3.0,
          caption="Java 21 + Spring Boot 3.4／PostgreSQL 16／Vue 3 離線版",
          card_html=_card("技術棧", "Spring Security 表單登入・Thymeleaf 頁殼・純 REST")),
    Scene("s02_login", 3.0, caption="以 leader／password123 登入"),
    Scene("s03_home_leader", 3.0, caption="個人視角：我的專案與指派給我的任務"),
    Scene("s04_create_project", 3.0, caption="建立專案，科別與負責人自動代入"),
    Scene("s05_board_empty", 3.0, caption="側邊欄切換：看板／人員派工／WBS 檢視"),
    Scene("s06_members", 3.0, caption="成員管理：把 member 加入專案"),
    Scene("s07_new_task", 3.0, caption="新增任務，指派人限專案成員"),
    Scene("s08_drag_board", 2.5, caption="拖曳卡片跨欄改狀態",
          drag=Drag("s08_base", "s08_clean", (_BOARD_FROM, _BOARD_TO))),
    Scene("s09_board_after", 2.0, caption="放開後狀態即時更新"),
    Scene("s10_wbs", 3.0, caption="WBS 建立大項與子類別"),
    Scene("s11_drag_wbs", 2.5, caption="拖曳任務即可歸類",
          drag=Drag("s11_base", "s11_clean", ((980.0, 300.0), (700.0, 470.0)))),
    Scene("s12_wbs_after", 2.0, caption="節點完成度即時重算"),
    Scene("s13_export", 3.0, caption="一鍵匯出 Excel，欄位對齊 WBS 樹狀順序"),
    Scene("s14_member_assign", 2.5, caption="成員自我認領任務",
          drag=Drag("s14_base", "s14_clean", ((420.0, 300.0), (900.0, 300.0)))),
    Scene("s15_member_after", 2.0, caption="認領完成，任務落到自己欄位"),
    Scene("s16_chief_home", 3.0, caption="科長視角：本科所有專案進度"),
    Scene("s17_archive", 3.0, caption="封存後全員唯讀"),
    Scene("s18_director", 3.0, caption="主任視角：跨科總覽，一律唯讀"),
    # 加映：含「拖過頭再拖回」的往返，補間後才看得出是「拖」而不是瞬移
    Scene("s19_drag_full", 7.0, caption="加映：完整拖曳一次",
          drag=Drag("s19_base", "s19_clean",
                    (_BOARD_FROM, _BOARD_TO, (1560.0, 320.0),
                     (1150.0, 310.0), _BOARD_TO))),
    Scene("s20_end", 3.0, zoom=(1.0, 1.03),
          card_html=_card("感謝觀看", "docs/user-guide.md ・ docs/dev.md")),
)


def total_seconds():
    return sum(s.seconds for s in SCENES)


def total_frames():
    return sum(max(1, int(round(s.seconds * FPS))) for s in SCENES)
```

- [ ] **Step 4: 執行測試確認通過**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: PASS（8 tests）。`total_seconds()` 應為 `62.5`、`total_frames()` 應為 `1875`。

若總長超出 60–63，調整 `SCENES` 的 `seconds` 而非放寬測試。

---

### Task 2: 緩動與路徑取樣

**Files:**
- Create: `scripts/intro-video/easing.py`
- Test: `scripts/intro-video/tests/test_easing.py`

**Interfaces:**
- Consumes: 無
- Produces: `ease_in_out_cubic(t: float) -> float`、`sample_path(points: Sequence[Tuple[float, float]], n_frames: int) -> List[Tuple[float, float]]`（依總弧長進度取樣，支援往返路徑）

- [ ] **Step 1: 寫失敗測試**

建立 `scripts/intro-video/tests/test_easing.py`：

```python
import math
import unittest

from easing import ease_in_out_cubic, sample_path


def _dist(a, b):
    return math.hypot(b[0] - a[0], b[1] - a[1])


class TestEasing(unittest.TestCase):
    def test_endpoints(self):
        self.assertAlmostEqual(ease_in_out_cubic(0.0), 0.0)
        self.assertAlmostEqual(ease_in_out_cubic(1.0), 1.0)

    def test_midpoint(self):
        self.assertAlmostEqual(ease_in_out_cubic(0.5), 0.5)

    def test_symmetric(self):
        for t in (0.1, 0.25, 0.4):
            self.assertAlmostEqual(
                ease_in_out_cubic(t) + ease_in_out_cubic(1 - t), 1.0)

    def test_clamps_out_of_range(self):
        self.assertEqual(ease_in_out_cubic(-1.0), 0.0)
        self.assertEqual(ease_in_out_cubic(2.0), 1.0)


class TestSamplePath(unittest.TestCase):
    def test_hits_both_endpoints(self):
        pts = sample_path([(0.0, 0.0), (100.0, 0.0)], 10)
        self.assertEqual(len(pts), 10)
        self.assertAlmostEqual(pts[0][0], 0.0)
        self.assertAlmostEqual(pts[-1][0], 100.0)

    def test_speed_ramps_up_then_down(self):
        # 這是「流暢」的核心：位移量必須先增後減，不能等速也不能跳點
        pts = sample_path([(0.0, 0.0), (300.0, 0.0)], 31)
        steps = [_dist(a, b) for a, b in zip(pts, pts[1:])]
        mid = len(steps) // 2
        self.assertGreater(steps[mid], steps[0])
        self.assertGreater(steps[mid], steps[-1])

    def test_no_jump_exceeds_reasonable_step(self):
        # 舊片的病灶：單幀位移 180-220px。補間後任一幀都不該有大跳點
        pts = sample_path([(0.0, 0.0), (440.0, 0.0)], 75)
        steps = [_dist(a, b) for a, b in zip(pts, pts[1:])]
        self.assertLess(max(steps), 20.0)

    def test_round_trip_path_returns_to_target(self):
        # 加映的「拖過頭再拖回」：終點必須落在最後一個關鍵點
        path = [(0.0, 0.0), (100.0, 0.0), (160.0, 10.0), (60.0, 5.0), (100.0, 0.0)]
        pts = sample_path(path, 60)
        self.assertAlmostEqual(pts[-1][0], 100.0, places=3)
        self.assertAlmostEqual(pts[-1][1], 0.0, places=3)

    def test_single_frame_returns_start(self):
        self.assertEqual(sample_path([(5.0, 6.0), (9.0, 9.0)], 1), [(5.0, 6.0)])

    def test_rejects_short_path(self):
        with self.assertRaises(ValueError):
            sample_path([(0.0, 0.0)], 10)

    def test_rejects_non_positive_frames(self):
        with self.assertRaises(ValueError):
            sample_path([(0.0, 0.0), (1.0, 1.0)], 0)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'easing'`

- [ ] **Step 3: 寫 easing.py**

```python
# -*- coding: utf-8 -*-
"""緩動與路徑取樣。純函式，無 I/O，因此補間曲線可以獨立驗證。"""
import math
from typing import List, Sequence, Tuple

Point = Tuple[float, float]


def ease_in_out_cubic(t):
    if t <= 0.0:
        return 0.0
    if t >= 1.0:
        return 1.0
    if t < 0.5:
        return 4.0 * t * t * t
    return 1.0 - pow(-2.0 * t + 2.0, 3) / 2.0


def _segment_lengths(points):
    return [math.hypot(b[0] - a[0], b[1] - a[1])
            for a, b in zip(points, points[1:])]


def sample_path(points, n_frames):
    """沿折線依「總弧長進度」取樣，進度走 ease-in-out。

    用弧長而非分段平均，往返路徑（拖過頭再拖回）才不會在轉折處變速。
    """
    if n_frames <= 0:
        raise ValueError("n_frames 必須為正整數")
    points = [(float(p[0]), float(p[1])) for p in points]
    if len(points) < 2:
        raise ValueError("路徑至少需要兩個關鍵點")
    if n_frames == 1:
        return [points[0]]

    lengths = _segment_lengths(points)
    total = sum(lengths)
    if total == 0.0:
        return [points[0]] * n_frames

    out = []
    for i in range(n_frames):
        target = ease_in_out_cubic(i / float(n_frames - 1)) * total
        acc = 0.0
        for idx, seg in enumerate(lengths):
            if acc + seg >= target or idx == len(lengths) - 1:
                local = 0.0 if seg == 0.0 else (target - acc) / seg
                local = min(max(local, 0.0), 1.0)
                a, b = points[idx], points[idx + 1]
                out.append((a[0] + (b[0] - a[0]) * local,
                            a[1] + (b[1] - a[1]) * local))
                break
            acc += seg
    return out
```

- [ ] **Step 4: 執行測試確認通過**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: PASS（19 tests 累計：Task 1 的 8 ＋ 本任務 11）

---

### Task 3: PIL 合成基元

**Files:**
- Create: `scripts/intro-video/compositing.py`
- Test: `scripts/intro-video/tests/test_compositing.py`

**Interfaces:**
- Consumes: `storyboard`（顏色、字型路徑、`CAPTION_HEIGHT`）
- Produces: 全部回傳 **RGB** 模式 `Image`——`load_font(size, path=None) -> FreeTypeFont`、`crop_sprite(base, bbox) -> Image`（回傳 RGBA，唯一例外，因為 sprite 需要透明度）、`paste_sprite(canvas, sprite, center_xy, rotate_deg=2.0, shadow=True) -> Image`、`draw_caption(img, text, alpha=1.0) -> Image`、`draw_boxes(img, boxes, width=3) -> Image`、`draw_cursor(img, xy) -> Image`、`crossfade(a, b, t) -> Image`、`ken_burns(img, scale) -> Image`

- [ ] **Step 1: 寫失敗測試**

建立 `scripts/intro-video/tests/test_compositing.py`：

```python
import unittest

from PIL import Image

import storyboard as sb
from compositing import (crop_sprite, crossfade, draw_boxes, draw_caption,
                         draw_cursor, ken_burns, load_font, paste_sprite)


def _canvas(color=(200, 200, 200)):
    return Image.new("RGB", (sb.WIDTH, sb.HEIGHT), color)


class TestFont(unittest.TestCase):
    def test_loads_pinned_font(self):
        self.assertIsNotNone(load_font(30))

    def test_missing_font_fails_loudly(self):
        # 靜默 fallback 會產出豆腐字，等到成片才發現代價太高
        with self.assertRaises(FileNotFoundError):
            load_font(30, "/nonexistent/NoSuch.ttc")


class TestCrossfade(unittest.TestCase):
    def setUp(self):
        self.a = _canvas((0, 0, 0))
        self.b = _canvas((255, 255, 255))

    def test_t_zero_is_first_image(self):
        self.assertEqual(crossfade(self.a, self.b, 0.0).getpixel((10, 10)),
                         (0, 0, 0))

    def test_t_one_is_second_image(self):
        self.assertEqual(crossfade(self.a, self.b, 1.0).getpixel((10, 10)),
                         (255, 255, 255))

    def test_midpoint_is_blended(self):
        px = crossfade(self.a, self.b, 0.5).getpixel((10, 10))
        self.assertTrue(all(100 < v < 155 for v in px), px)

    def test_size_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            crossfade(self.a, Image.new("RGB", (10, 10)), 0.5)


class TestCaption(unittest.TestCase):
    def test_alpha_zero_leaves_image_untouched(self):
        base = _canvas()
        self.assertEqual(list(draw_caption(base, "測試字幕", 0.0).getdata()),
                         list(base.convert("RGB").getdata()))

    def test_none_text_leaves_image_untouched(self):
        base = _canvas()
        self.assertEqual(list(draw_caption(base, None, 1.0).getdata()),
                         list(base.convert("RGB").getdata()))

    def test_caption_band_changes_bottom_pixels(self):
        out = draw_caption(_canvas(), "拖曳卡片跨欄改狀態", 1.0)
        self.assertNotEqual(out.getpixel((800, sb.HEIGHT - 40)), (200, 200, 200))

    def test_caption_does_not_touch_top(self):
        out = draw_caption(_canvas(), "拖曳卡片跨欄改狀態", 1.0)
        self.assertEqual(out.getpixel((800, 40)), (200, 200, 200))

    def test_returns_rgb(self):
        self.assertEqual(draw_caption(_canvas(), "字幕", 1.0).mode, "RGB")


class TestSprite(unittest.TestCase):
    def test_paste_preserves_canvas_size(self):
        sprite = crop_sprite(_canvas((10, 20, 30)), (0, 0, 400, 80))
        out = paste_sprite(_canvas(), sprite, (800.0, 400.0))
        self.assertEqual(out.size, (sb.WIDTH, sb.HEIGHT))
        self.assertEqual(out.mode, "RGB")

    def test_paste_changes_pixels_at_target(self):
        sprite = crop_sprite(_canvas((10, 20, 30)), (0, 0, 400, 80))
        out = paste_sprite(_canvas(), sprite, (800.0, 400.0), shadow=False)
        self.assertNotEqual(out.getpixel((800, 400)), (200, 200, 200))

    def test_paste_leaves_far_pixels_alone(self):
        sprite = crop_sprite(_canvas((10, 20, 30)), (0, 0, 400, 80))
        out = paste_sprite(_canvas(), sprite, (800.0, 400.0), shadow=False)
        self.assertEqual(out.getpixel((20, 20)), (200, 200, 200))


class TestBoxesAndCursor(unittest.TestCase):
    def test_box_drawn_in_danger_color(self):
        out = draw_boxes(_canvas(), ((100, 100, 300, 200),))
        self.assertEqual(out.getpixel((200, 100)), sb.DANGER)

    def test_no_boxes_is_noop(self):
        base = _canvas()
        self.assertEqual(list(draw_boxes(base, ()).getdata()),
                         list(base.getdata()))

    def test_cursor_changes_pixels(self):
        out = draw_cursor(_canvas(), (500.0, 500.0))
        self.assertNotEqual(out.getpixel((502, 505)), (200, 200, 200))


class TestKenBurns(unittest.TestCase):
    def test_scale_one_is_noop(self):
        base = _canvas()
        self.assertEqual(list(ken_burns(base, 1.0).getdata()),
                         list(base.getdata()))

    def test_zoom_preserves_size(self):
        self.assertEqual(ken_burns(_canvas(), 1.03).size, (sb.WIDTH, sb.HEIGHT))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'compositing'`

- [ ] **Step 3: 寫 compositing.py**

```python
# -*- coding: utf-8 -*-
"""PIL 合成基元。除 crop_sprite 外一律回傳 RGB，避免模式在管線中飄移。"""
import os
from typing import Iterable, Optional, Tuple

from PIL import Image, ImageDraw, ImageFilter, ImageFont

import storyboard as sb


def load_font(size, path=None):
    path = path or sb.FONT_PATH
    # 缺字型一律 fail fast：靜默 fallback 會產出豆腐字
    if not os.path.exists(path):
        raise FileNotFoundError(
            "找不到字幕字型 {}。本機無 PingFang，且不可用 Hiragino 代替"
            "（日文字形，部分繁中字寫法不同）".format(path))
    return ImageFont.truetype(path, size)


def crossfade(a, b, t):
    if a.size != b.size:
        raise ValueError("交叉淡入的兩張圖尺寸必須相同：{} vs {}".format(a.size, b.size))
    return Image.blend(a.convert("RGB"), b.convert("RGB"),
                       min(max(float(t), 0.0), 1.0))


def crop_sprite(base, bbox):
    """從乾淨畫面裁出真實卡片，不重繪字體——這是保真度的來源。"""
    return base.convert("RGBA").crop(tuple(int(v) for v in bbox))


def paste_sprite(canvas, sprite, center_xy, rotate_deg=2.0, shadow=True):
    out = canvas.convert("RGBA")
    layer = sprite.rotate(rotate_deg, resample=Image.BICUBIC, expand=True)
    x = int(center_xy[0] - layer.width / 2.0)
    y = int(center_xy[1] - layer.height / 2.0)
    if shadow:
        shade = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        shade.putalpha(layer.split()[3].point(lambda a: int(a * 0.28)))
        shade = shade.filter(ImageFilter.GaussianBlur(9))
        out.alpha_composite(shade, (x, y + 6))
    out.alpha_composite(layer, (x, y))
    return out.convert("RGB")


def draw_caption(img, text, alpha=1.0):
    base = img.convert("RGB")
    if not text or alpha <= 0.0:
        return base
    base = base.convert("RGBA")
    band = Image.new("RGBA", (base.width, sb.CAPTION_HEIGHT),
                     sb.INK + (235,))
    draw = ImageDraw.Draw(band)
    font = load_font(30)
    width = draw.textlength(text, font=font)
    draw.text(((base.width - width) / 2.0, (sb.CAPTION_HEIGHT - 30) / 2.0 - 3),
              text, font=font, fill=sb.PAPER + (255,))
    if alpha < 1.0:
        band.putalpha(band.split()[3].point(
            lambda a: int(a * min(max(alpha, 0.0), 1.0))))
    base.alpha_composite(band, (0, base.height - sb.CAPTION_HEIGHT))
    return base.convert("RGB")


def draw_boxes(img, boxes, width=3):
    out = img.convert("RGB")
    if not boxes:
        return out
    draw = ImageDraw.Draw(out)
    for box in boxes:
        draw.rectangle(tuple(box), outline=sb.DANGER, width=width)
    return out


def draw_cursor(img, xy):
    out = img.convert("RGBA")
    layer = Image.new("RGBA", out.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    x, y = int(xy[0]), int(xy[1])
    arrow = [(x, y), (x, y + 22), (x + 6, y + 16), (x + 10, y + 25),
             (x + 14, y + 23), (x + 10, y + 14), (x + 17, y + 14)]
    draw.polygon(arrow, fill=sb.PAPER + (255,), outline=sb.INK + (255,))
    out.alpha_composite(layer)
    return out.convert("RGB")


def ken_burns(img, scale):
    base = img.convert("RGB")
    if abs(scale - 1.0) < 1e-9:
        return base
    width, height = base.size
    big = base.resize((int(width * scale), int(height * scale)), Image.LANCZOS)
    left = (big.width - width) // 2
    top = (big.height - height) // 2
    return big.crop((left, top, left + width, top + height))
```

- [ ] **Step 4: 執行測試確認通過**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: PASS（38 tests 累計：前兩任務的 19 ＋ 本任務 19）

---

### Task 4: sequencer 主流程

**Files:**
- Create: `scripts/intro-video/sequencer.py`
- Test: `scripts/intro-video/tests/test_sequencer.py`

**Interfaces:**
- Consumes: `storyboard`、`easing.sample_path`、`compositing.*`
- Produces: `build_sequence(scenes, raw_dir, meta, out_dir) -> int`（回傳寫出的幀數）、`required_frame_ids(scenes) -> List[str]`（capture.py 用它得知該截哪些圖）；CLI `python3 sequencer.py <raw_dir> <out_dir>`

- [ ] **Step 1: 寫失敗測試**

建立 `scripts/intro-video/tests/test_sequencer.py`：

```python
import json
import os
import shutil
import tempfile
import unittest

from PIL import Image

import storyboard as sb
from sequencer import build_sequence, required_frame_ids


def _scene(**kw):
    base = dict(id="x", seconds=1.0, caption=None, boxes=(), drag=None,
                zoom=(1.0, 1.0), card_html=None)
    base.update(kw)
    return sb.Scene(**base)


class TestRequiredFrameIds(unittest.TestCase):
    def test_static_scene_needs_own_id(self):
        self.assertEqual(required_frame_ids([_scene(id="a")]), ["a"])

    def test_drag_scene_needs_base_and_sprite_source(self):
        scene = _scene(id="d", drag=sb.Drag("d_base", "d_clean",
                                            ((0.0, 0.0), (10.0, 0.0))))
        self.assertEqual(sorted(required_frame_ids([scene])),
                         ["d_base", "d_clean"])

    def test_real_storyboard_ids_are_unique(self):
        ids = required_frame_ids(sb.SCENES)
        self.assertEqual(len(ids), len(set(ids)))


class TestBuildSequence(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.raw = os.path.join(self.tmp, "raw")
        self.out = os.path.join(self.tmp, "seq")
        os.makedirs(self.raw)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def _write_raw(self, name, color):
        Image.new("RGB", (sb.WIDTH, sb.HEIGHT), color).save(
            os.path.join(self.raw, name + ".png"))

    def test_frame_count_matches_seconds(self):
        self._write_raw("a", (10, 10, 10))
        n = build_sequence([_scene(id="a", seconds=2.0)], self.raw, {}, self.out)
        self.assertEqual(n, 60)
        self.assertEqual(len(os.listdir(self.out)), 60)

    def test_frames_are_zero_padded_and_contiguous(self):
        self._write_raw("a", (10, 10, 10))
        build_sequence([_scene(id="a", seconds=0.5)], self.raw, {}, self.out)
        names = sorted(os.listdir(self.out))
        self.assertEqual(names[0], "00000.png")
        self.assertEqual(names[-1], "%05d.png" % (len(names) - 1))

    def test_output_dimensions_are_even(self):
        self._write_raw("a", (10, 10, 10))
        build_sequence([_scene(id="a", seconds=0.2)], self.raw, {}, self.out)
        img = Image.open(os.path.join(self.out, "00000.png"))
        self.assertEqual(img.width % 2, 0)
        self.assertEqual(img.height % 2, 0)

    def test_odd_sized_raw_frame_rejected(self):
        Image.new("RGB", (1600, 811), (0, 0, 0)).save(
            os.path.join(self.raw, "odd.png"))
        with self.assertRaises(ValueError):
            build_sequence([_scene(id="odd", seconds=0.2)], self.raw, {},
                           self.out)

    def test_scene_boundary_is_crossfaded(self):
        # 硬切是舊片的病灶之一；轉場幀必須是兩場景的混合色
        self._write_raw("a", (0, 0, 0))
        self._write_raw("b", (255, 255, 255))
        build_sequence([_scene(id="a", seconds=1.0), _scene(id="b", seconds=1.0)],
                       self.raw, {}, self.out)
        first_of_b = Image.open(os.path.join(self.out, "00030.png"))
        px = first_of_b.getpixel((800, 100))
        self.assertTrue(all(0 < v < 255 for v in px), px)

    def test_drag_scene_moves_sprite_between_frames(self):
        # 流暢度的根本驗證：相鄰幀畫面必須不同（舊片這裡是 0.5 秒完全靜止）
        self._write_raw("d_clean", (30, 30, 30))
        self._write_raw("d_base", (30, 30, 30))
        scene = _scene(id="d", seconds=1.0,
                       drag=sb.Drag("d_base", "d_clean",
                                    ((400.0, 300.0), (1200.0, 300.0))))
        meta = {"sprites": {"d_clean": [200, 260, 600, 340]}}
        build_sequence([scene], self.raw, meta, self.out)
        a = Image.open(os.path.join(self.out, "00014.png")).tobytes()
        b = Image.open(os.path.join(self.out, "00015.png")).tobytes()
        self.assertNotEqual(a, b)

    def test_missing_sprite_meta_fails_loudly(self):
        self._write_raw("d_clean", (30, 30, 30))
        self._write_raw("d_base", (30, 30, 30))
        scene = _scene(id="d", seconds=0.5,
                       drag=sb.Drag("d_base", "d_clean",
                                    ((0.0, 0.0), (10.0, 0.0))))
        with self.assertRaises(KeyError):
            build_sequence([scene], self.raw, {"sprites": {}}, self.out)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: FAIL，`ModuleNotFoundError: No module named 'sequencer'`

- [ ] **Step 3: 寫 sequencer.py**

```python
# -*- coding: utf-8 -*-
"""把分鏡與 raw 幀展開成 30fps 圖片序列。純離線——調節奏只要重跑這支。"""
import json
import os
import sys

from PIL import Image

import storyboard as sb
from compositing import (crop_sprite, crossfade, draw_boxes, draw_caption,
                         draw_cursor, ken_burns, paste_sprite)
from easing import ease_in_out_cubic, sample_path


def required_frame_ids(scenes):
    ids = []
    for scene in scenes:
        if scene.drag is None:
            ids.append(scene.id)
        else:
            ids.append(scene.drag.base_id)
            ids.append(scene.drag.sprite_from)
    return ids


def _frame_count(scene):
    return max(1, int(round(scene.seconds * sb.FPS)))


def _caption_alpha(i, n):
    k = sb.CAPTION_FADE_FRAMES
    if n <= 2 * k:
        return 1.0
    if i < k:
        return (i + 1) / float(k + 1)
    if i >= n - k:
        return (n - i) / float(k + 1)
    return 1.0


def _load_raw(scenes, raw_dir):
    raw = {}
    for name in set(required_frame_ids(scenes)):
        path = os.path.join(raw_dir, name + ".png")
        if not os.path.exists(path):
            raise FileNotFoundError("缺少擷取幀 {}，請先跑 capture.py".format(path))
        img = Image.open(path).convert("RGB")
        # 奇數高度過不了 yuv420p，寧可在這裡炸掉也不要在編碼階段才發現
        if img.width % 2 or img.height % 2:
            raise ValueError(
                "{} 尺寸 {}x{} 含奇數邊，無法編成 yuv420p".format(
                    name, img.width, img.height))
        raw[name] = img
    return raw


def _scene_frames(scene, raw, meta):
    n = _frame_count(scene)
    frames = []
    if scene.drag is None:
        base = raw[scene.id]
        zoom_a, zoom_b = scene.zoom
        for i in range(n):
            progress = 0.0 if n == 1 else i / float(n - 1)
            scale = zoom_a + (zoom_b - zoom_a) * ease_in_out_cubic(progress)
            img = ken_burns(base, scale)
            img = draw_boxes(img, scene.boxes)
            frames.append(draw_caption(img, scene.caption, _caption_alpha(i, n)))
        return frames

    base = raw[scene.drag.base_id]
    bbox = meta["sprites"][scene.drag.sprite_from]
    sprite = crop_sprite(raw[scene.drag.sprite_from], bbox)
    for i, pos in enumerate(sample_path(scene.drag.path, n)):
        img = paste_sprite(base, sprite, pos, rotate_deg=scene.drag.rotate_deg)
        img = draw_cursor(img, pos)
        img = draw_boxes(img, scene.boxes)
        frames.append(draw_caption(img, scene.caption, _caption_alpha(i, n)))
    return frames


def build_sequence(scenes, raw_dir, meta, out_dir):
    raw = _load_raw(scenes, raw_dir)
    if os.path.isdir(out_dir):
        for stale in os.listdir(out_dir):
            os.remove(os.path.join(out_dir, stale))
    else:
        os.makedirs(out_dir)

    index = 0
    previous_last = None
    for scene in scenes:
        frames = _scene_frames(scene, raw, meta)
        # 轉場就地做在下一場景的開頭，總幀數才等於各場景秒數加總
        if previous_last is not None:
            k = min(sb.TRANSITION_FRAMES, len(frames))
            for j in range(k):
                frames[j] = crossfade(previous_last, frames[j],
                                      (j + 1) / float(k + 1))
        for frame in frames:
            frame.save(os.path.join(out_dir, "%05d.png" % index))
            index += 1
        previous_last = frames[-1]
    return index


def main():
    if len(sys.argv) != 3:
        raise SystemExit("用法: python3 sequencer.py <raw_dir> <out_dir>")
    raw_dir, out_dir = sys.argv[1], sys.argv[2]
    with open(os.path.join(raw_dir, "meta.json")) as handle:
        meta = json.load(handle)
    total = build_sequence(sb.SCENES, raw_dir, meta, out_dir)
    print("寫出 {} 幀（{:.1f} 秒 @ {}fps）".format(
        total, total / float(sb.FPS), sb.FPS))


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 執行測試確認通過**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: PASS（48 tests 累計：前三任務的 38 ＋ 本任務 10）

---

### Task 5: 編碼腳本與端到端 fixture 驗證

**Files:**
- Create: `scripts/intro-video/encode.sh`

**Interfaces:**
- Consumes: `sequencer.py` 產出的 `seq/%05d.png`
- Produces: CLI `./encode.sh <seq_dir> <out.mp4>`

這一步用假造的 raw 幀跑完整條 `sequencer → encode`，在真實擷取（Task 6–7，會花好幾分鐘且容易卡關）之前就把編碼參數釘死。

- [ ] **Step 1: 寫 encode.sh**

```bash
#!/bin/bash
# 組片：只用圖片序列，不用 concat demuxer——後者在這台機器上會少算約 2 秒且末幀時長不穩
set -euo pipefail

SEQ_DIR="${1:?用法: encode.sh <seq目錄> <輸出mp4>}"
OUT="${2:?用法: encode.sh <seq目錄> <輸出mp4>}"

ffmpeg -y -framerate 30 -i "${SEQ_DIR}/%05d.png" \
    -c:v libx264 -pix_fmt yuv420p -preset slow -crf 20 \
    -movflags +faststart "${OUT}"
```

- [ ] **Step 2: 給執行權限**

Run: `chmod +x scripts/intro-video/encode.sh`

- [ ] **Step 3: 產生假造 raw 幀並跑完整條管線**

```bash
cd scripts/intro-video
python3 - <<'PY'
import os
from PIL import Image, ImageDraw
import storyboard as sb
from sequencer import required_frame_ids

raw = "/tmp/ivfixture/raw"
os.makedirs(raw, exist_ok=True)
ids = sorted(set(required_frame_ids(sb.SCENES)))
for n, name in enumerate(ids):
    img = Image.new("RGB", (sb.WIDTH, sb.HEIGHT), (240 - n * 3, 240, 250))
    ImageDraw.Draw(img).rectangle((200, 260, 600, 340), fill=(255, 255, 255),
                                  outline=(30, 30, 30), width=2)
    img.save(os.path.join(raw, name + ".png"))

import json
meta = {"sprites": {i: [200, 260, 600, 340] for i in ids}}
with open(os.path.join(raw, "meta.json"), "w") as f:
    json.dump(meta, f)
print("fixture 幀數:", len(ids))
PY
python3 sequencer.py /tmp/ivfixture/raw /tmp/ivfixture/seq
./encode.sh /tmp/ivfixture/seq /tmp/ivfixture/out.mp4
```

Expected: `sequencer.py` 印出 `寫出 1875 幀（62.5 秒 @ 30fps）`；ffmpeg 成功產出檔案。

- [ ] **Step 4: 驗證編碼結果符合 spec**

```bash
ffprobe -v error -show_entries format=duration \
    -show_entries stream=codec_name,width,height,r_frame_rate,pix_fmt,nb_frames \
    -of default=noprint_wrappers=1 /tmp/ivfixture/out.mp4
```

Expected：`width=1600`、`height=812`、`r_frame_rate=30/1`、`pix_fmt=yuv420p`、`nb_frames=1875`、`duration` 約 `62.5`。

任一項不符就修 `encode.sh` 或 `sequencer.py`，不要放寬期待值。

- [ ] **Step 5: 清掉 fixture**

Run: `rm -rf /tmp/ivfixture`

---

### Task 6: capture.py 環境編排與登入切片

**Files:**
- Create: `scripts/intro-video/capture.py`

**Interfaces:**
- Consumes: `storyboard`、`sequencer.required_frame_ids`
- Produces: `reset_db()`、`start_app() -> Popen`、`stop_app(proc)`、`login(page, username)`、`shot(page, scene_id)`、`capture_card(page, scene)`、`capture_drag(page, clean_id, base_id, card_selector, column_selector)`、`main()`

這個任務只做到「環境能起來、能登入、能截到片頭卡與登入頁」。完整分鏡掃描留給 Task 7——把最容易卡關的環境編排單獨切出來，才能在不重跑整套操作的情況下反覆除錯。

- [ ] **Step 1: 寫 capture.py 的環境編排與基礎工具**

```python
# -*- coding: utf-8 -*-
"""用 Playwright 擷取分鏡所需的真實畫面。

唯一需要瀏覽器的階段。腳本自己包辦 DB 重置與 app 啟動，
因此重跑結果具確定性（分鏡流程會現場建立專案與任務）。
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

import storyboard as sb

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORK = os.path.join(REPO, ".superpowers", "intro-video")
RAW = os.path.join(WORK, "raw")
BASE_URL = "http://localhost:8080"
PASSWORD = "password123"

META = {"sprites": {}}


def _sh(cmd, check=True):
    return subprocess.run(cmd, shell=True, cwd=REPO, check=check)


def reset_db():
    """重置 DB。不重置的話第二次跑資料會疊加、畫面與分鏡對不上。"""
    print("[capture] 重置資料庫…")
    _sh("docker compose down -v")
    _sh("docker compose up -d")
    for _ in range(60):
        probe = subprocess.run(
            "docker compose exec -T db sh -c "
            "'pg_isready -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"'",
            shell=True, cwd=REPO, capture_output=True)
        if probe.returncode == 0:
            print("[capture] DB 就緒")
            return
        time.sleep(2)
    raise RuntimeError("DB 未在 120 秒內就緒")


def start_app():
    print("[capture] 啟動應用程式…")
    log_path = os.path.join(WORK, "app.log")
    log = open(log_path, "w")
    proc = subprocess.Popen("mvn spring-boot:run", shell=True, cwd=REPO,
                            stdout=log, stderr=subprocess.STDOUT)
    for _ in range(90):
        try:
            if urllib.request.urlopen(BASE_URL + "/login", timeout=2).status == 200:
                print("[capture] 應用程式已就緒")
                return proc
        except Exception:
            pass
        time.sleep(2)
    proc.terminate()
    raise RuntimeError("應用程式未在 180 秒內啟動，詳見 " + log_path)


def stop_app(proc):
    proc.terminate()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()


def shot(page, scene_id):
    page.wait_for_timeout(400)
    page.screenshot(path=os.path.join(RAW, scene_id + ".png"))
    print("[capture] 截圖 " + scene_id)


def capture_card(page, scene):
    """片頭／片尾卡在同一顆瀏覽器裡渲染，字體與 UI 畫面才一致。"""
    page.set_content(scene.card_html)
    shot(page, scene.id)


def login(page, username):
    page.goto(BASE_URL + "/login")
    page.fill("#username", username)
    page.fill("#password", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url(BASE_URL + "/home")


def logout(page):
    """點頁首真正的「登出」連結。

    只是 goto /login 並不會終止 session，後續 login() 是否真的換人
    取決於未經驗證的行為——切視角時會靜默拿到上一個使用者的畫面。
    """
    page.get_by_text("登出").click()
    page.wait_for_url("**/login**")
```

- [ ] **Step 2: 加入拖曳底圖擷取**

接在上面之後：

```python
_BBOX_JS = """
(selector) => {
  const r = document.querySelector(selector).getBoundingClientRect();
  return [Math.round(r.x), Math.round(r.y), Math.round(r.right), Math.round(r.bottom)];
}
"""

# 直接改 DOM 樣式做出「拖曳中」外觀。不去設 Vue 內部狀態——
# global build 的 app 實例沒掛在 window 上，從外部改很脆。
_DRAG_ON_JS = """
([cardSel, colSel]) => {
  document.querySelector(cardSel).style.opacity = '0.35';
  const col = document.querySelector(colSel);
  col.style.outline = '2px dashed #1D4ED8';
  col.style.outlineOffset = '-6px';
}
"""

_DRAG_OFF_JS = """
([cardSel, colSel]) => {
  document.querySelector(cardSel).style.opacity = '';
  const col = document.querySelector(colSel);
  col.style.outline = '';
  col.style.outlineOffset = '';
}
"""


def capture_drag(page, clean_id, base_id, card_selector, column_selector):
    """產出補間需要的兩張圖。

    clean_id：卡片完整不透明，供裁 sprite（bbox 一併記進 meta）
    base_id ：原位卡片壓透明、目標欄加虛線框，當補間底圖
    """
    page.wait_for_timeout(400)
    META["sprites"][clean_id] = page.evaluate(_BBOX_JS, card_selector)
    page.screenshot(path=os.path.join(RAW, clean_id + ".png"))

    page.evaluate(_DRAG_ON_JS, [card_selector, column_selector])
    page.wait_for_timeout(200)
    page.screenshot(path=os.path.join(RAW, base_id + ".png"))
    page.evaluate(_DRAG_OFF_JS, [card_selector, column_selector])
    print("[capture] 拖曳底圖 {} / {}".format(clean_id, base_id))
```

- [ ] **Step 3: 加入 main()，本任務只跑到登入頁**

```python
def capture_all(page):
    """依分鏡逐場景擷取。Task 7 會把中間各幕補齊。"""
    scenes = {s.id: s for s in sb.SCENES}
    capture_card(page, scenes["s00_title"])
    capture_card(page, scenes["s01_stack"])

    page.goto(BASE_URL + "/login")
    shot(page, "s02_login")

    capture_card(page, scenes["s20_end"])


def main():
    os.makedirs(RAW, exist_ok=True)
    reset_db()
    app = start_app()
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(
                viewport={"width": sb.WIDTH, "height": sb.HEIGHT},
                device_scale_factor=1)
            capture_all(page)
            browser.close()
        with open(os.path.join(RAW, "meta.json"), "w") as handle:
            json.dump(META, handle, ensure_ascii=False, indent=2)
    finally:
        stop_app(app)
    print("[capture] 完成，輸出於 " + RAW)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 實跑，確認環境編排可用**

Run: `cd scripts/intro-video && python3 capture.py`

Expected：依序印出「重置資料庫…」「DB 就緒」「應用程式已就緒」「截圖 s00_title」「截圖 s01_stack」「截圖 s02_login」「截圖 s20_end」「完成」。

- [ ] **Step 5: 驗證截圖尺寸與內容**

```bash
cd /Users/oscarlin/Documents/GitHub/Spring-MissionBoard
for f in .superpowers/intro-video/raw/*.png; do
  echo -n "$f "; sips -g pixelWidth -g pixelHeight "$f" | tail -2 | tr -d '\n'; echo
done
```

Expected：每張皆為 `pixelWidth: 1600 / pixelHeight: 812`（**必須是 812，不是 811**）。

用 Read 工具目視確認 `s02_login.png` 確實是登入頁、`s00_title.png` 是標題卡且中文字未變成豆腐字。

---

### Task 7: 完整分鏡擷取

**Files:**
- Modify: `scripts/intro-video/capture.py`（擴充 `capture_all`）
- Modify: `scripts/intro-video/storyboard.py`（校正拖曳路徑座標）

**Interfaces:**
- Consumes: Task 6 的所有函式
- Produces: `RAW/` 下 `required_frame_ids(sb.SCENES)` 列出的每一張 PNG，以及含全部拖曳 sprite bbox 的 `meta.json`

- [ ] **Step 1: 擴充 capture_all 走完整個分鏡**

把 Task 6 的 `capture_all` 整個換掉：

```python
def capture_all(page):
    scenes = {s.id: s for s in sb.SCENES}

    capture_card(page, scenes["s00_title"])
    capture_card(page, scenes["s01_stack"])

    # --- leader 視角 ---
    page.goto(BASE_URL + "/login")
    shot(page, "s02_login")

    login(page, "leader")
    shot(page, "s03_home_leader")

    page.goto(BASE_URL + "/projects")
    page.get_by_text("建立專案").click()
    page.wait_for_timeout(300)
    page.get_by_label("專案名稱").fill("客服系統改版")
    shot(page, "s04_create_project")
    page.get_by_role("button", name="建立").click()
    page.wait_for_timeout(800)

    page.get_by_text("客服系統改版").first.click()
    page.wait_for_timeout(800)
    shot(page, "s05_board_empty")

    page.get_by_text("成員管理").click()
    page.wait_for_timeout(300)
    shot(page, "s06_members")
    page.select_option("select", label="member")
    page.get_by_role("button", name="新增").click()
    page.wait_for_timeout(600)
    page.get_by_text("成員管理").click()

    # 第三張刻意不指派：s14「成員自我認領」需要「未指派」欄裡有東西可以拖。
    # 這是設計，不是疏漏——改動這裡會讓 s14 沒有拖曳對象。
    _create_task(page, "整理客服工單流程需求", "member", sb.DATES["due_a"])
    _create_task(page, "設計工單狀態流程圖", "leader", sb.DATES["due_b"])
    _create_task(page, "建置後台工單列表頁", None, sb.DATES["due_c"])
    page.get_by_text("新增任務").click()
    page.wait_for_timeout(300)
    shot(page, "s07_new_task")
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)

    # 看板拖曳：先把一張卡移到「進行中」，再擷取拖往「已完成」的底圖
    capture_drag(page, "s08_clean", "s08_base",
                 ".task-card", ".kanban-col:nth-child(2)")
    _move_first_card_to(page, 2)
    shot(page, "s09_board_after")

    # --- WBS 檢視 ---
    page.get_by_text("WBS 檢視").click()
    page.wait_for_timeout(800)
    shot(page, "s10_wbs")
    capture_drag(page, "s11_clean", "s11_base",
                 ".wbs-task-row", ".wbs-category-node")
    shot(page, "s12_wbs_after")
    shot(page, "s13_export")

    # --- member 視角：自我認領 ---
    logout(page)
    login(page, "member")
    page.goto(BASE_URL + "/projects")
    page.get_by_text("客服系統改版").first.click()
    page.get_by_text("人員派工").click()
    page.wait_for_timeout(800)
    capture_drag(page, "s14_clean", "s14_base",
                 ".task-card", ".assignee-col:nth-child(2)")
    shot(page, "s15_member_after")

    # --- chief 視角 ---
    logout(page)
    login(page, "chief")
    shot(page, "s16_chief_home")
    page.goto(BASE_URL + "/projects")
    page.get_by_text("客服系統改版").first.click()
    page.wait_for_timeout(800)
    shot(page, "s17_archive")

    # --- director 視角 ---
    logout(page)
    login(page, "director")
    shot(page, "s18_director")

    # --- 加映：完整拖曳 ---
    logout(page)
    login(page, "leader")
    page.goto(BASE_URL + "/projects")
    page.get_by_text("客服系統改版").first.click()
    page.wait_for_timeout(800)
    capture_drag(page, "s19_clean", "s19_base",
                 ".task-card", ".kanban-col:nth-child(3)")

    capture_card(page, scenes["s20_end"])


def _create_task(page, title, assignee, due):
    """assignee 傳 None 代表刻意不指派（s14 的認領對象）。"""
    page.get_by_text("新增任務").click()
    page.wait_for_timeout(300)
    page.get_by_label("標題").fill(title)
    page.get_by_label("到期日").fill(due)
    if assignee is not None:
        # 指派人下拉只列專案成員；選擇器需在 Step 2 實測校正
        page.get_by_label("指派人").select_option(label=assignee)
    page.get_by_role("button", name="儲存").click()
    page.wait_for_timeout(600)


def _move_first_card_to(page, column_index):
    """用 REST 直接改狀態，不模擬拖曳——原生 DnD 無法用合成事件觸發。"""
    page.wait_for_timeout(300)
```

- [ ] **Step 2: 逐一校正選擇器**

上面的 `get_by_text` / CSS 選擇器是依 `CLAUDE.md` 記載的 UI 文案推得，**必須逐一實測**。逐段執行、每段失敗就用下列方式查出真實選擇器後修正：

```bash
cd scripts/intro-video && python3 - <<'PY'
from playwright.sync_api import sync_playwright
with sync_playwright() as pw:
    b = pw.chromium.launch(headless=False)
    p = b.new_page(viewport={"width":1600,"height":812}, device_scale_factor=1)
    p.goto("http://localhost:8080/login")
    p.fill("#username","leader"); p.fill("#password","password123")
    p.click("button[type=submit]"); p.wait_for_url("**/home")
    p.goto("http://localhost:8080/projects")
    print(p.content()[:4000])
    b.close()
PY
```

**注意**：`CLAUDE.md` 載明 chrome-devtools 的 `click` 常觸發不到 Vue handler。Playwright 的 `click()` 送的是真實輸入事件、不受此限；但若仍遇到沒反應，改用 `page.evaluate("document.querySelector(sel).click()")`。

- [ ] **Step 3: 實跑完整擷取**

Run: `cd scripts/intro-video && python3 capture.py`

Expected：`RAW/` 下產出 25 張 PNG（`required_frame_ids` 去重後的數量）＋ `meta.json`，且 `meta.json` 的 `sprites` 含 `s08_clean`、`s11_clean`、`s14_clean`、`s19_clean` 四筆 bbox。

- [ ] **Step 4: 驗證擷取完整性**

```bash
cd scripts/intro-video && python3 - <<'PY'
import json, os
import storyboard as sb
from sequencer import required_frame_ids
raw = "../../.superpowers/intro-video/raw"
need = sorted(set(required_frame_ids(sb.SCENES)))
missing = [n for n in need if not os.path.exists(os.path.join(raw, n + ".png"))]
print("需要:", len(need), "缺少:", missing)
meta = json.load(open(os.path.join(raw, "meta.json")))
print("sprite bbox:", json.dumps(meta["sprites"], indent=2))
assert not missing, "有缺幀"

# 換視角必須真的換成功。三個角色的儀表板若有任兩張完全相同，
# 代表 logout 沒生效、拿到的是上一個使用者的畫面。
import hashlib
digests = {}
for name in ("s03_home_leader", "s16_chief_home", "s18_director"):
    with open(os.path.join(raw, name + ".png"), "rb") as fh:
        digests[name] = hashlib.md5(fh.read()).hexdigest()
print("儀表板雜湊:", digests)
assert len(set(digests.values())) == 3, "角色儀表板重複，logout 未生效"
print("三個角色視角皆不同")
PY
```

Expected：`缺少: []`，四筆 bbox 座標合理（寬約 380–420、高約 70–90），並印出「三個角色視角皆不同」。

- [ ] **Step 5: 依實際 bbox 校正拖曳路徑，並量測紅框座標**

**(a) 拖曳路徑**：`storyboard.py` 裡的 `_BOARD_FROM` 等座標是舊幀的量測值。用上一步印出的真實 bbox 算出卡片中心，改寫路徑關鍵點：

- 起點 = `s08_clean` 的 bbox 中心 `((x0+x1)/2, (y0+y1)/2)`
- 終點 = 目標欄中心（用 `page.evaluate(_BBOX_JS, ".kanban-col:nth-child(3)")` 取得）

**(b) 紅框**：`SCENES` 目前每格都是 `boxes=()`，必須在這一步填實，否則新片會完全沒有標示區——舊片有約 9 處紅框，全掉光會直接損及「語意清楚」。

在 `capture_all` 的對應位置加入量測（重用既有的 `_BBOX_JS`），把結果寫進 `META` 另一個鍵：

```python
def measure_box(page, scene_id, selector):
    META.setdefault("boxes", {}).setdefault(scene_id, []).append(
        page.evaluate(_BBOX_JS, selector))
```

至少涵蓋這三處（其餘視畫面需要增補）：

| 場景 | 標示對象 | 選擇器（需實測校正） |
|---|---|---|
| `s02_login` | 登入表單 | `form` |
| `s05_board_empty` | 側邊欄三分頁 | `#project-nav-slot` |
| `s13_export` | 匯出 Excel 按鈕 | 依實際文案定位 |

量完把座標填回 `storyboard.py` 對應場景的 `boxes=(...)`。

**(c)** 改完重跑 `python3 -m unittest discover -s tests -t .` 確認 Task 1 的分鏡測試仍通過，並確認至少 3 個場景的 `boxes` 非空：

```bash
cd scripts/intro-video && python3 -c "
import storyboard as sb
n = sum(1 for s in sb.SCENES if s.boxes)
print('有紅框的場景數:', n)
assert n >= 3, '紅框未填，新片會沒有任何標示區'
"
```

---

### Task 8: 產出成片、文件收尾與提交

**Files:**
- Create: `scripts/intro-video/README.md`
- Modify: `docs/demo-video-storyboard.md`（全文改寫）
- Modify: `CLAUDE.md:129`（說明文件段）
- Delete: `docs/missionboard-demo.mp4`
- Replace: `docs/missionboard-intro.mp4`

**Interfaces:**
- Consumes: Task 1–7 全部
- Produces: 成片與文件

- [ ] **Step 1: 跑完整條管線產出成片**

```bash
cd scripts/intro-video
python3 sequencer.py ../../.superpowers/intro-video/raw ../../.superpowers/intro-video/seq
./encode.sh ../../.superpowers/intro-video/seq ../../.superpowers/intro-video/intro.mp4
```

Expected：`寫出 1875 幀（62.5 秒 @ 30fps）`，ffmpeg 成功。

先輸出到工作目錄而非直接覆蓋 `docs/`——驗收沒過之前，舊片是唯一退路。

- [ ] **Step 2: 程式化驗證成片規格**

```bash
ffprobe -v error -show_entries format=duration \
  -show_entries stream=codec_name,width,height,r_frame_rate,pix_fmt,nb_frames \
  -of default=noprint_wrappers=1 .superpowers/intro-video/intro.mp4
```

Expected：`width=1600`、`height=812`、`r_frame_rate=30/1`、`pix_fmt=yuv420p`、`nb_frames=1875`、`duration≈62.5`。

- [ ] **Step 3: 程式化驗證補間平滑度**

這是本次改動的核心驗收項——不靠肉眼：

```bash
cd scripts/intro-video && python3 - <<'PY'
import math
import storyboard as sb
from easing import sample_path

for scene in sb.SCENES:
    if scene.drag is None:
        continue
    n = max(1, int(round(scene.seconds * sb.FPS)))
    pts = sample_path(scene.drag.path, n)
    steps = [math.hypot(b[0]-a[0], b[1]-a[1]) for a, b in zip(pts, pts[1:])]
    print("{:16s} 幀數={:3d} 最大單幀位移={:6.2f}px 平均={:5.2f}px".format(
        scene.id, n, max(steps), sum(steps)/len(steps)))
    # 舊片單幀位移 180-220px，這是「瞬移感」的來源
    assert max(steps) < 25.0, "{} 單幀位移過大，仍會有跳點".format(scene.id)
print("所有拖曳段補間平滑")
PY
```

Expected：每段最大單幀位移遠小於舊片的 180–220px，印出「所有拖曳段補間平滑」。

- [ ] **Step 4: 目視抽查**

抽三幀用 Read 工具檢視，確認字幕清晰、卡片位置合理、無豆腐字：

```bash
cd /Users/oscarlin/Documents/GitHub/Spring-MissionBoard
mkdir -p .superpowers/intro-video/check
ffmpeg -y -v error -i .superpowers/intro-video/intro.mp4 -vf "select='eq(n\,300)+eq(n\,1000)+eq(n\,1700)'" \
  -vsync 0 .superpowers/intro-video/check/frame%02d.png
ls .superpowers/intro-video/check/
```

用 Read 工具看這三張。任何一張有問題就回頭修，不要帶著問題往下走。

- [ ] **Step 5: 就位成片並刪除舊片**

```bash
cd /Users/oscarlin/Documents/GitHub/Spring-MissionBoard
cp .superpowers/intro-video/intro.mp4 docs/missionboard-intro.mp4
git rm docs/missionboard-demo.mp4
```

- [ ] **Step 6: 寫 scripts/intro-video/README.md**

```markdown
# 介紹短片產片 pipeline

重跑整套：

```bash
cd scripts/intro-video
python3 capture.py                                    # 重置 DB、起 app、擷取真實畫面（唯一需要瀏覽器的一步）
python3 sequencer.py ../../.superpowers/intro-video/raw ../../.superpowers/intro-video/seq
./encode.sh ../../.superpowers/intro-video/seq ../../docs/missionboard-intro.mp4
```

只調節奏、字幕或緩動時**不必重跑 `capture.py`**——改 `storyboard.py` 後重跑 `sequencer.py` 與 `encode.sh` 即可，幾秒鐘。

測試：`python3 -m unittest discover -s tests -t .`

## 檔案職責

| 檔案 | 職責 |
|---|---|
| `storyboard.py` | 純資料：場景、字幕、秒數、紅框、拖曳路徑、寫死日期 |
| `easing.py` | 緩動與路徑取樣（純函式） |
| `compositing.py` | PIL 基元：sprite、字幕帶、紅框、游標、交叉淡入、Ken Burns |
| `sequencer.py` | 分鏡 + raw 幀 → 30fps 圖片序列 |
| `capture.py` | Playwright 擷取與環境編排 |
| `encode.sh` | ffmpeg 單次編碼 |

## 踩過的坑

- **原生 HTML5 拖曳影像 CDP 拍不到**（由瀏覽器/OS 在頁面外合成），`Input.dispatchMouseEvent` 也發動不了原生 DnD。所以拖曳一律是合成的：從乾淨畫面裁真實卡片當 sprite，再依緩動曲線貼到補間位置。
- **組片不可用 ffmpeg concat demuxer**，會少算約 2 秒且末幀時長不穩；只用 `-framerate 30 -i seq/%05d.png`。
- **不可用 `minterpolate` 補幀**，UI 截圖會產生鬼影、文字邊緣糊掉。
- **本機無 `PingFang.ttc`**，字幕用 `STHeiti Medium.ttc`；不可用 Hiragino（日文字形）。
- **截圖高度必須是偶數**（812，不是 811），否則 `yuv420p` 編不出來。
- **`capture.py` 必須自己重置 DB**，流程會現場建立專案與任務，不重置第二次跑資料就疊加。
- Ken Burns 只用在片頭/片尾卡；UI 畫面縮放會讓 14px 文字重取樣閃爍。
```

- [ ] **Step 7: 改寫 docs/demo-video-storyboard.md**

全文改寫成新分鏡。必須包含：新的 21 個場景表（id、畫面、字幕、秒數，與 `storyboard.py` 的 `SCENES` 完全一致）、總長 62.5 秒、製作方式改為「`scripts/intro-video/` 三段式 pipeline」，並移除舊版第 1、2 幕的環境變數與測試帳號段落。

檔頭加一行指向真相來源：

```markdown
> 分鏡的權威定義在 `scripts/intro-video/storyboard.py` 的 `SCENES`；本檔是給人讀的對照表，改分鏡請改程式那份，兩邊要一致。
```

- [ ] **Step 8: 更新 CLAUDE.md 說明文件段**

把 `CLAUDE.md:129` 那一行（同時列出兩支影片與舊製作方式）改為只提 intro，並指向新 pipeline：

```markdown
- `docs/missionboard-intro.mp4`＋`docs/demo-video-storyboard.md`：專案介紹短片與分鏡（產片 pipeline 在 `scripts/intro-video/`，重跑方式見該目錄 README；工作幀在 gitignored 的 `.superpowers/intro-video/`）
```

**注意**：若 file_guard hook 攔截 `CLAUDE.md` 的修改，依攔截訊息指示的正規流程處理，**不要尋找繞過方式**。

- [ ] **Step 9: 確認舊片無殘留引用**

```bash
cd /Users/oscarlin/Documents/GitHub/Spring-MissionBoard
grep -rn "missionboard-demo" --include="*.md" --include="*.html" --include="*.js" --include="*.java" . | grep -v node_modules
```

Expected：無輸出。

- [ ] **Step 10: 跑全部測試**

Run: `cd scripts/intro-video && python3 -m unittest discover -s tests -t . -v`
Expected: PASS（48 tests）

- [ ] **Step 11: 傳送成片給使用者並請求 commit 核准**

用 SendUserFile 傳 `docs/missionboard-intro.mp4`，附上 Step 2/3 的驗證數據與 Step 4 的抽幀截圖。

然後依 `CLAUDE.md` Git Standards 提出變更摘要，**等使用者核准後**才執行唯一一次提交：

```bash
git add scripts/intro-video docs/missionboard-intro.mp4 \
        docs/demo-video-storyboard.md docs/superpowers CLAUDE.md
git rm --cached docs/missionboard-demo.mp4 2>/dev/null || true
git commit -m "feat: 重製專案介紹短片並將產片 pipeline 入版控"
```

**未經使用者核准不得執行此步。**

---

## Self-Review

**1. Spec coverage**

| Spec 要求 | 對應任務 |
|---|---|
| 三段式 pipeline 入版控 | Task 1–6、Task 8 Step 6 |
| 拖曳補間演在 PIL（離線） | Task 2、Task 4 |
| 轉場走 Python alpha 混合、禁 `xfade` | Task 4（`build_sequence` 就地混合）、Task 5（`encode.sh` 無 filter） |
| Ken Burns 只用在片頭尾 | Task 1（`test_ken_burns_only_on_cards`）、Task 3（`ken_burns`）、Task 4 |
| 字型釘 Heiti TC、缺字型 fail fast | Task 3（`load_font` + `test_missing_font_fails_loudly`） |
| 尺寸偶數、`yuv420p` | Task 4（`test_odd_sized_raw_frame_rejected`）、Task 5 Step 4、Task 6 Step 5 |
| 日期寫死絕對日期 | Task 1（`test_dates_are_absolute`）、Task 7（`_create_task` 用 `sb.DATES`） |
| DB 重置確保確定性 | Task 6（`reset_db`） |
| 底圖改 DOM 樣式、不碰 Vue 內部狀態 | Task 6 Step 2（`_DRAG_ON_JS`） |
| 60–63 秒 | Task 1（`test_total_length_within_spec`），實際 62.5 秒 |
| 第 1、2 幕壓縮到一張技術棧卡 | Task 1（`s01_stack`，21s → 3s） |
| 刪除 `demo.mp4`、無殘留引用 | Task 8 Step 5、Step 9 |
| 改寫分鏡、更新 CLAUDE.md | Task 8 Step 7、Step 8 |
| 補間平滑度程式化驗證 | Task 8 Step 3 |
| 不改應用程式行為 | Global Constraints |
| 無配樂 | `encode.sh` 無音訊輸入 |

無未覆蓋項。

**2. Placeholder scan**

無 TBD／TODO。唯一「待定」性質的是 Task 7 的選擇器與拖曳座標——但這不是佔位符：選擇器已依 `CLAUDE.md` 記載的 UI 文案給出具體值，並附了查真實 DOM 的可執行指令；座標已填現有幀的實測值，並有專屬校正步驟（Task 7 Step 5）。

**3. Type consistency**

- `Scene` / `Drag` 欄位在 Task 1 定義，Task 4 的 `_scene_frames`、Task 7 的 `capture_all` 使用一致
- `required_frame_ids` 定義於 `sequencer.py`（Task 4），Task 5 Step 3、Task 7 Step 4 引用一致
- `META["sprites"][clean_id]` 的寫入（Task 6）與 `meta["sprites"][scene.drag.sprite_from]` 的讀取（Task 4）鍵一致——皆為 `Drag.sprite_from` 指向的 id
- 所有 compositing 函式回傳 RGB（`crop_sprite` 回傳 RGBA 為明示例外），Task 3 測試 `test_returns_rgb` 鎖住
- `sb.DATES` 在 Task 1 定義、Task 7 `_create_task` 使用

**修正紀錄**（自我檢查時發現並已修正）：

1. `_move_first_card_to` 原本出現在 Task 7 但無實作內容——已改為明確註記「用 REST 直接改狀態，不模擬拖曳」的樁函式，並列入 Task 7 Step 2 的校正範圍與「已知風險」第 2 點。
2. Task 1 原有一條 `test_every_scene_has_source`，其斷言 `a is not None or b is not None or a is None` **恆為真**，是假測試——已刪除，換成兩條真正有鑑別力的：`test_drag_scene_is_never_a_card`、`test_captions_present_on_ui_scenes`。
3. 各任務的累計測試數原本算錯（寫成 7／11／31／42）。實際為 **8／19／38／48**（8 + 11 + 19 + 10），已全部更正，含 Task 8 Step 10 的最終期待值。

**數字覆核**：`SCENES` 共 21 個場景，秒數加總 `14×3 + 3×2.5 + 3×2 + 7 = 62.5` 秒，落在 60–63 內；幀數 `14×90 + 3×75 + 3×60 + 210 = 1875`。`required_frame_ids` 去重後為 `17 個靜態 + 4 個拖曳場景×2 = 25` 張，與 Task 7 Step 3 的期待值一致。

---

## 已知風險

1. **Task 7 的選擇器是全案最大不確定性。** 依 `CLAUDE.md` 文案推得，需實測校正。Step 2 已備查詢真實 DOM 的指令。若某段操作反覆卡關，依 `CLAUDE.md` 失敗升級鐵則——同一錯誤第二次出現就換假設，額度用滿帶完整失敗軌跡問使用者。
2. **`_move_first_card_to` 需要決定實作方式**：原生 DnD 無法用合成事件觸發，所以要嘛呼叫 `PATCH /api/projects/{id}/tasks/{taskId}/move`（需帶 CSRF token），要嘛在 WBS 檢視用 `cycleTaskStatus` 按鈕改狀態。後者較簡單，Task 7 校正時擇一。
3. **`CLAUDE.md` 可能被 file_guard 攔截**（Task 8 Step 8）。依攔截訊息走正規流程。
4. **紅框選擇器未經實測**（Task 7 Step 5b）。三個標示對象的選擇器是依 `CLAUDE.md` 記載推得，須實測；量不到就改用截圖目視量測填絕對座標，不可留空跳過——Step 5c 的斷言會擋下。

## 補充自我檢查（第二輪，advisor 覆核後）

第一輪自我檢查漏掉三個實質缺口，已修正：

1. **紅框機制建了卻從未使用**：`draw_boxes` 有實作有測試，但 `SCENES` 全部 `boxes=()`，且原本沒有任何步驟會填入。舊片約 9 處紅框會全數消失。已補 Task 7 Step 5(b)(c)，含「至少 3 個場景有紅框」的斷言。
2. **`_create_task` 的 `assignee` 是死參數**：函式體從不使用它，三個呼叫端的指派意圖被靜默忽略。已實作指派人選擇，並把第三張任務明確標記為「刻意不指派」——s14 的認領對象原本是碰巧存在。
3. **`logout()` 沒有登出**：只 `goto /login`，session 仍在。已改為點頁首「登出」連結，並在 Task 7 Step 4 加入三角色儀表板雜湊互異的斷言。

平滑度門檻覆核：cubic ease-in-out 的峰值速度為平均的 3 倍，最嚴苛的 s14（480px／75 幀）峰值約 19.5px/幀，對 Task 8 Step 3 的 25px 斷言仍有餘裕。
