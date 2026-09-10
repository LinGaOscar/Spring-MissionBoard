# 專案介紹短片分鏡腳本

> 分鏡的權威定義在 `scripts/intro-video/storyboard.py` 的 `SCENES`；本檔是給人讀的對照表，改分鏡請改程式那份，兩邊要一致。

成品：`docs/missionboard-intro.mp4`（1600×812、30fps、無聲、無配樂）。

製作方式：`scripts/intro-video/` 下的三段式 pipeline——`capture.py`（Playwright 重置 DB、起應用程式、擷取真實畫面）→ `sequencer.py`（依 `storyboard.py` 的分鏡資料，把 raw 幀合成拖曳補間、字幕、紅框、Ken Burns，輸出 30fps 圖片序列）→ `encode.sh`（ffmpeg 單次編碼，不用 concat demuxer、不用 `minterpolate`）。重跑方式與踩過的坑見該目錄 `README.md`。

拖曳動畫皆為合成：原生 HTML5 拖曳影像瀏覽器不會回傳給 CDP，改用真實卡片裁切成 sprite，依緩動曲線（`easing.py`）逐幀貼到補間位置，取代舊版「僅前/中/後三張定格」的作法，補間後每段最大單幀位移 < 25px，不再有瞬移感。

## 場景表（21 個場景，總長 62.5 秒）

| # | id | 畫面 | 字幕 | 秒 |
|---|---|---|---|---|
| 1 | `s00_title` | 白底標題卡「MissionBoard 任務管理系統」／「扁平任務模型 × 看板拖曳的任務派工系統」（Ken Burns 輕微放大） | （文字卡本身即文案，無字幕列） | 3.0 |
| 2 | `s01_stack` | 白底技術棧卡「技術棧」／「Spring Security 表單登入・Thymeleaf 頁殼・純 REST」（Ken Burns） | Java 21 + Spring Boot 3.4／PostgreSQL 16／Vue 3 離線版 | 3.0 |
| 3 | `s02_login` | 登入頁（紅框標示登入表單） | 以 leader／password123 登入 | 3.0 |
| 4 | `s03_home_leader` | leader 首頁儀表板 | 個人視角：我的專案與指派給我的任務 | 3.0 |
| 5 | `s04_create_project` | 建立專案 Modal | 建立專案，科別與負責人自動代入 | 3.0 |
| 6 | `s05_board_empty` | 新專案空看板＋側邊欄（紅框標示側邊欄三分頁） | 側邊欄切換：看板／人員派工／WBS 檢視 | 3.0 |
| 7 | `s06_members` | 成員管理面板（紅框標示面板） | 成員管理：把 member 加入專案 | 3.0 |
| 8 | `s07_new_task` | 新增任務 Modal | 新增任務，指派人限專案成員 | 3.0 |
| 9 | `s08_drag_board` | 看板拖曳合成動畫：卡片從「未開始」拖到「進行中」 | 拖曳卡片跨欄改狀態 | 2.5 |
| 10 | `s09_board_after` | 放開後看板即時更新 | 放開後狀態即時更新 | 2.0 |
| 11 | `s10_wbs` | WBS 檢視建立大項與子類別 | WBS 建立大項與子類別 | 3.0 |
| 12 | `s11_drag_wbs` | WBS 拖曳合成動畫：任務從「未歸類」拖到子類別「前端開發」 | 拖曳任務即可歸類 | 2.5 |
| 13 | `s12_wbs_after` | 放開後節點完成度即時重算 | 節點完成度即時重算 | 2.0 |
| 14 | `s13_export` | WBS「匯出 Excel」按鈕（紅框） | 一鍵匯出 Excel，欄位對齊 WBS 樹狀順序 | 3.0 |
| 15 | `s14_member_assign` | 人員派工拖曳合成動畫：任務從「未指派」欄拖到「專案成員」欄 | 成員自我認領任務 | 2.5 |
| 16 | `s15_member_after` | 放開後任務落到自己欄位 | 認領完成，任務落到自己欄位 | 2.0 |
| 17 | `s16_chief_home` | chief 首頁：本科所有專案進度 | 科長視角：本科所有專案進度 | 3.0 |
| 18 | `s17_archive` | 封存後專案工具列（紅框標示工具列） | 封存後全員唯讀 | 3.0 |
| 19 | `s18_director` | director 首頁跨科彙總 | 主任視角：跨科總覽，一律唯讀 | 3.0 |
| 20 | `s19_drag_full` | 看板加映：完整往返拖曳一次（拖過頭再拖回，縱向往返避免卡片出界） | 加映：完整拖曳一次 | 7.0 |
| 21 | `s20_end` | 白底片尾卡「感謝觀看」／「docs/user-guide.md ・ docs/dev.md」（Ken Burns） | （文字卡本身即文案，無字幕列） | 3.0 |

秒數加總：`8×3.0 + 2.5 + 2.0 + 3.0 + 2.5 + 2.0 + 3.0 + 2.5 + 2.0 + 3.0×3 + 7.0 + 3.0 = 62.5` 秒，與 `storyboard.total_seconds()` 一致；對應 `1875` 幀（30fps）。

## 場次涵蓋的角色視角

- **leader**（第 3–14、20 場）：登入、建立專案、成員管理、新增任務、看板拖曳、WBS 歸類、匯出 Excel；第 20 場加映完整拖曳前會重新登入 leader
- **member**（第 15–16 場）：人員派工分欄拖曳認領任務
- **chief**（第 17–18 場）：本科總覽、封存專案
- **director**（第 19 場）：跨科彙總，一律唯讀

## 紅框標示的場景

`s02_login`、`s05_board_empty`、`s06_members`、`s13_export`、`s17_archive` 五場帶紅框標示操作區，座標定義於 `storyboard.py` 的 `Scene.boxes`。
