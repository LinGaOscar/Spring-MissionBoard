# -*- coding: utf-8 -*-
"""介紹短片分鏡資料。純資料，不含邏輯——改分鏡不需要動其他程式。"""
from dataclasses import dataclass
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


# 拖曳路徑座標：Task 7 Step 5 用 capture.py 實際擷取出的 meta.json（sprites bbox）
# 與現場量測的目標欄 bbox 換算而得，取代原本沿用舊影片幀的量測值。
# 卡片／列中心＝(bbox 對角中心)，欄位中心＝目標欄 bbox 中心。
_BOARD_CARD = (466.0, 301.0)          # 看板「未開始」欄第一張卡片中心（s08_clean／s19_clean bbox 相同）
_KANBAN_IN_PROGRESS = (910.0, 377.0)  # 「進行中」欄中心
_KANBAN_DONE = (1354.0, 377.0)        # 「已完成」欄中心
_WBS_ROW_FROM = (922.0, 288.5)        # 未歸類任務列中心（s11_clean bbox）
_WBS_SUBCATEGORY = (922.0, 374.0)     # 「前端開發」子類別節點中心
_ASSIGN_UNASSIGNED_CARD = (372.0, 279.0)  # 人員派工「未指派」欄第一張卡片中心（s14_clean bbox）
_ASSIGN_MEMBER_COL = (884.0, 355.0)       # 人員派工「專案成員」欄中心

SCENES: Tuple[Scene, ...] = (
    Scene("s00_title", 3.0, zoom=(1.0, 1.03),
          card_html=_card("MissionBoard 任務管理系統",
                          "扁平任務模型 × 看板拖曳的任務派工系統")),
    Scene("s01_stack", 3.0,
          caption="Java 21 + Spring Boot 3.4／PostgreSQL 16／Vue 3 離線版",
          card_html=_card("技術棧", "Spring Security 表單登入・Thymeleaf 頁殼・純 REST")),
    # 實測 bbox + 8px 留白（原始實測值 (651, 328, 949, 540) 上緣貼著「帳號」標籤，
    # 這裡各邊外擴 8px 才有呼吸空間；與 meta.json 的紀錄值不同屬預期，不是抄錯）
    Scene("s02_login", 3.0, caption="以 leader／password123 登入",
          boxes=((643, 320, 957, 548),)),
    Scene("s03_home_leader", 3.0, caption="個人視角：我的專案與指派給我的任務"),
    Scene("s04_create_project", 3.0, caption="建立專案，科別與負責人自動代入"),
    Scene("s05_board_empty", 3.0, caption="側邊欄切換：看板／人員派工／WBS 檢視",
          boxes=((0, 178, 219, 330),)),
    Scene("s06_members", 3.0, caption="成員管理：把 member 加入專案",
          boxes=((273, 242, 1547, 274),)),
    Scene("s07_new_task", 3.0, caption="新增任務，指派人限專案成員"),
    Scene("s08_drag_board", 2.5, caption="拖曳卡片跨欄改狀態",
          drag=Drag("s08_base", "s08_clean", (_BOARD_CARD, _KANBAN_IN_PROGRESS))),
    Scene("s09_board_after", 2.0, caption="放開後狀態即時更新"),
    Scene("s10_wbs", 3.0, caption="WBS 建立大項與子類別"),
    # WBS 任務列（.wbs-task-row）幾乎跟整個內容區一樣寬（實測 1266px，遠寬於看板卡片的
    # ~400px），預設 2° 旋轉在這麼寬的矩形上會讓兩端位移十幾 px、把底下列的文字「削」出來，
    # 疊圖看起來像重影；這裡關掉旋轉，讓寬版 sprite 精準疊在原位置上，不會漏出底圖
    Scene("s11_drag_wbs", 2.5, caption="拖曳任務即可歸類",
          drag=Drag("s11_base", "s11_clean", (_WBS_ROW_FROM, _WBS_SUBCATEGORY),
                    rotate_deg=0.0)),
    Scene("s12_wbs_after", 2.0, caption="節點完成度即時重算"),
    Scene("s13_export", 3.0, caption="一鍵匯出 Excel，欄位對齊 WBS 樹狀順序",
          boxes=((252, 159, 365, 201),)),
    Scene("s14_member_assign", 2.5, caption="成員自我認領任務",
          drag=Drag("s14_base", "s14_clean", (_ASSIGN_UNASSIGNED_CARD, _ASSIGN_MEMBER_COL))),
    Scene("s15_member_after", 2.0, caption="認領完成，任務落到自己欄位"),
    Scene("s16_chief_home", 3.0, caption="科長視角：本科所有專案進度"),
    Scene("s17_archive", 3.0, caption="封存後全員唯讀",
          boxes=((252, 88, 1568, 143),)),
    Scene("s18_director", 3.0, caption="主任視角：跨科總覽，一律唯讀"),
    # 加映：含「拖過頭再拖回」的往返，補間後才看得出是「拖」而不是瞬移。
    # 卡片寬 404px，「已完成」欄中心 x=1354 離畫面右緣（1600）只剩 246px 空間，
    # 往右超出約 44px 卡片右緣就會被裁掉畫面外——往返改走縱向（欄內下衝再回彈），
    # 一樣讀得出「拖過頭」的手感，又不會讓卡片跑出 1600×812 的畫布
    Scene("s19_drag_full", 7.0, caption="加映：完整拖曳一次",
          drag=Drag("s19_base", "s19_clean",
                    (_BOARD_CARD, _KANBAN_DONE, (1360.0, 500.0),
                     (1348.0, 340.0), _KANBAN_DONE))),
    Scene("s20_end", 3.0, zoom=(1.0, 1.03),
          card_html=_card("感謝觀看", "docs/user-guide.md ・ docs/dev.md")),
)


def total_seconds():
    return sum(s.seconds for s in SCENES)


def total_frames():
    return sum(max(1, int(round(s.seconds * FPS))) for s in SCENES)
