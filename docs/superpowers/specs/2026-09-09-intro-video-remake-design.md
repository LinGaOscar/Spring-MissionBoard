# 專案介紹短片重製（產片 pipeline 入版控） 設計文件

> 前置閱讀：`docs/demo-video-storyboard.md`（現行分鏡，本次會全文改寫）。
> 本檔不改動應用程式任何行為，只重做影片與產片工具。

## 背景

現行 `docs/missionboard-intro.mp4` 被評為「不夠流暢」。實測診斷出兩個獨立成因：

1. **產片方式是投影片，不是影片**：80.3 秒的成片只由 **34 張定格截圖**組成，有效動畫率 0.42 fps，平均每張停留 2.4 秒；場景之間全硬切、無聲。拖曳段最傷——關鍵幀之間卡片一次位移約 180–220px，中間 0.5 秒完全靜止，觀感是「瞬移」而非「拖曳」。
2. **UI 本身零動畫**：`app.css` 全檔 147 行只有一處 `transition`（`.project-card` 的 background），拖曳是原生 HTML5 drag & drop，卡片落下沒有位移動畫、modal 沒有淡入。

本次只處理成因 1（使用者已核准的「路線一」）。成因 2 屬應用程式行為變更，若要做需另開 spec 走 brainstorming，不在本檔範圍。

另有一個結構性問題：**原始產片腳本完全沒有留存**（全 repo 無任何 `.py`／`.sh`），原始幀放在 gitignored 的 `.superpowers/`，字幕排版與紅框座標只存在於烘焙後的 PNG 裡。整套產片是一次性手工，無法重跑、無法微調。本次一併解決。

## 目標

重製介紹短片，讓畫面**語意清楚且流暢**，並把產片流程固化成可重跑、入版控的腳本。

## 使用者已確認的前提

- **觀眾**：外部使用者與長官。門檻是「語意畫面清楚流暢」即可
- **不要配樂**：整條音訊工作移除，成片維持無聲
- **素材全部重新擷取**：不沿用現有成品幀
- **第 1、2 幕大幅壓縮但保留**：保留一張技術棧概覽（約 3 秒），環境變數表與測試帳號表全部拿掉（原 21 秒 → 3 秒）

## 關鍵技術事實（決定了整個架構）

**原生 HTML5 拖曳的「拖曳影像」由瀏覽器／作業系統在頁面之外合成，CDP 的截圖與 screencast 都拍不到它**，且 `Input.dispatchMouseEvent` 也發動不了原生 DnD。原作者用 PIL 疊圖、分鏡表第 68 行寫明「畫面疊加游標與卡片殘影」，正是撞到這個限制。

**因此拖曳畫面一定是「演」出來的，差別只在演在哪一層。** 這也讓「改用真實螢幕錄影」這條路徑直接出局（拍不到拖曳影像，加上 UI 零動畫，真錄下來一樣是硬跳）。

## 已決定的三個問題

1. **拖曳合成演在哪一層 → 演在 PIL（離線），不是演在 DOM（逐幀截圖）**
   Playwright 只負責產出少量「拖曳中」底圖並記錄卡片 bbox，補間全部在 Python 離線算。兩者畫面幾乎相同（卡片 sprite 是從真實截圖裁下來的，不是重繪），但離線補間讓**調緩動曲線或秒數只要重跑 sequencer，幾秒鐘，不必碰瀏覽器**，且瀏覽器往返次數少一個數量級、卡關風險低很多。

2. **轉場做在 Python，不用 ffmpeg `xfade`**
   補間、場景淡入、字幕淡入淡出全部在產生圖片序列時以 alpha 混合算好，最後單一次 `-framerate 30 -i seq/%05d.png` 編碼。理由：25 個場景的 `xfade` filtergraph 難維護；且分鏡表第 85 行已驗證這台機器上圖片序列組片才穩（concat demuxer 會少算約 2 秒且末幀時長不穩）。

3. **Ken Burns 只用在片頭／片尾卡，UI 畫面一律不縮放**
   1.0→1.02 的緩慢縮放會讓 14px 的 UI 文字每幀非整數重取樣，產生文字閃爍抖動——那是變糊，不是變流暢，直接牴觸「清楚」這個門檻。拖曳補間加上場景交叉淡入已足以消除投影片感。

## 交付範圍

| 檔案 | 動作 |
|---|---|
| `scripts/intro-video/` | **新增，入版控**——本次核心，讓產片不再是一次性手工 |
| `docs/missionboard-intro.mp4` | 重製（1600×812、30fps、無聲、約 60–63 秒） |
| `docs/missionboard-demo.mp4` | **刪除**——內容已被 intro 涵蓋，且品質更差（17 幀／34.8 秒） |
| `docs/demo-video-storyboard.md` | 全文改寫（新分鏡＋新製作方式） |
| `CLAUDE.md` 說明文件段 | 更新（現於 129 行同時列出兩支影片與舊製作方式） |

`demo.mp4` 的引用點已全數盤點，只有兩處：`CLAUDE.md:129` 與 `docs/demo-video-storyboard.md:3`。`docs/專案介紹.pptx` 內只有 PNG 圖片、未嵌入影片，刪除不影響簡報。

## 架構：三段式 pipeline

```
storyboard.py                                  設定檔：場景清單、字幕、秒數、紅框座標、拖曳路徑關鍵點
    ↓
capture.py    → frames/raw/*.png + meta.json   Playwright 擷取（唯一需要瀏覽器、唯一會卡關的階段）
    ↓
sequencer.py  → frames/seq/%05d.png            PIL 補間／轉場／字幕（純離線，可反覆重跑）
    ↓
encode.sh     → docs/missionboard-intro.mp4    ffmpeg 單次編碼
```

分成三支而非一支，是因為只有第一段慢且脆。這個切法把「調整節奏」的成本從「重跑整套瀏覽器操作」降到「重跑一支純運算腳本」。

各單元的職責邊界：
- `storyboard.py` 只有資料，沒有邏輯——改分鏡不必動程式
- `capture.py` 只產出「未加工的真實畫面」與座標中繼資料，不做任何美化
- `sequencer.py` 只讀檔案與設定，不碰網路與瀏覽器，因此可離線重跑與程式化驗證
- `encode.sh` 只做編碼，不做任何視覺決策

### capture.py：可重跑的確定性擷取

腳本自己包辦整個環境，不依賴當下狀態：

1. `docker compose down -v && docker compose up -d`，輪詢等 DB ready
2. 啟動 `mvn spring-boot:run`，輪詢 `:8080` 直到起來；擷取結束後關閉
3. 釘死 viewport `1600×812`、`deviceScaleFactor: 1`
4. 依 `storyboard.py` 逐場景操作、截圖
5. 拖曳段額外產出「拖曳中」底圖：注入 JS 直接改 DOM 樣式（原位卡片壓 `opacity`、目標欄加虛線外框），並把卡片 bbox 寫進 `meta.json` 供裁 sprite

第 1 步的 DB 重置是關鍵：分鏡流程會**現場建立專案與任務**，不重置的話第二次跑資料就疊加、畫面與分鏡對不上。

同理，**流程中輸入的任務起迄日一律在 `storyboard.py` 寫死絕對日期，不得用「今天 +N 天」這類相對日期**，這樣至少保證內容與版面的確定性（同一組帳號、同一批建立的專案與任務、同一個拖曳落點）；但寫死絕對日期換不到像素級一致，其保存期限與原因見下方「驗證重點」該項與 `scripts/intro-video/README.md`「踩過的坑」。

兩個實作限制（皆已在 `CLAUDE.md` 載明或本次實測確認）：
- 操作實際採用 **Playwright**（`page.click()`／`get_by_text().click()`），不是 `CLAUDE.md` 原本載明的 chrome-devtools MCP `evaluate_script` 的 `element.click()`／`form.submit()`。
  **實作備註**：`CLAUDE.md` 那條限制是針對 chrome-devtools MCP 的 `click` 工具——它常觸發不到 Vue handler 與表單 submit，因此才要求繞道 `evaluate_script`。Playwright 送的是真實輸入事件（非 CDP `click` 工具的合成點擊），不受此限，本次改用 Playwright 是刻意的技術決策（可重跑、可入版控），不是誤用或漏改
- **底圖用直接改 DOM 樣式的方式產生，不去設 Vue 內部狀態**：Vue global build 的 app 實例沒有掛在 `window` 上，從外部改 `dragOverCategoryId` 很脆。同樣的像素，但不依賴 app 內部實作

**尺寸修正**：現有原始幀是 1600×**811**，而成片是 812——**奇數高度過不了 `yuv420p`**。擷取階段釘死尺寸，並在 sequencer 輸出時確保為偶數。

### sequencer.py：所有動態都在這裡

30fps 逐幀輸出：

- **拖曳補間**：cubic ease-in-out，依 `storyboard.py` 的路徑關鍵點取樣。第 4 幕的「拖過頭 → 往回拖」往返照樣走同一條曲線，不特例化
- **卡片 sprite**：依 `meta.json` 的 bbox **從乾淨底圖裁下真實卡片矩形**（不重繪字體），貼到補間後的位置，加 2° 旋轉與柔和陰影
- **場景轉場**：相鄰場景 alpha 混合 8 幀（≈0.27 秒）
- **字幕**：依 `app.css` 的 `:root` token（`--ink #1A1A1A`／`--paper #FFFFFF`／`--ink-muted #6B6B70`）重新設計字幕帶，獨立 alpha 淡入淡出 5 幀
- **紅框**：`--danger #B91C1C`，座標來自設定檔

**字型**：`PingFang.ttc` 在這台機器上不存在（`/System/Library/Fonts/PingFang.ttc` 無此檔）。釘 `/System/Library/Fonts/STHeiti Medium.ttc`（Heiti TC Medium）。**不可用 Hiragino**——那是日文字形，部分繁中字會走日文寫法。字型路徑寫在設定檔，缺字型時 fail fast 並明確報錯，不要靜默 fallback。

## 新分鏡（約 60–63 秒）

| 幕 | 內容 | 秒 | 對比舊版 |
|---|---|---|---|
| 0 | 片頭標題卡 | 3.5 | — |
| 1 | 技術棧概覽一張卡 | 3 | **21s → 3s**（環境變數／測試帳號全刪） |
| 2 | 登入 | 3 | — |
| 3 | 使用：建立專案 → 加成員 → 建任務 → 看板拖曳 → WBS 建分類與歸類 → 匯出 → 成員自我認領 → 科長封存 → 主任跨科總覽 | ~40 | 含 3 段補間拖曳 |
| 4 | 加映：完整拖曳一次（含拖過頭往返） | ~9 | 補間後才真的看得出是「拖」 |
| 5 | 片尾 | 3 | — |

第 3 幕的角色視角切換（leader → member → chief → director）與現行分鏡一致，內容不變，只是重截並套用新的轉場與節奏。

## 版控範圍

- **入版控**：`scripts/intro-video/`（`storyboard.py`、`capture.py`、`sequencer.py`、`encode.sh`、`README.md`）、`docs/missionboard-intro.mp4`、改寫後的分鏡
- **不入版控**：擷取與序列的工作幀（raw 與 seq 都可由腳本重生，6MB+ PNG 不該進 git），沿用現有 gitignored 的 `.superpowers/` 工作目錄

## 不做的事（YAGNI）

- **不改應用程式任何行為**——不加 CSS transition、不加 FLIP 落下動畫、不加 modal 淡入。那是「路線二」，需另開 spec
- **不做配樂與音軌**（使用者明確排除）
- **不用 `minterpolate` 補幀**——UI 截圖上會產生鬼影、文字邊緣糊掉，比定格更難看
- **不用 CDP 逐幀截圖演拖曳**（方案 A）——保真度差異極小，但每次調曲線都要重跑瀏覽器
- **不改用真實 screencast 錄製**——拍不到拖曳影像，且 UI 零動畫
- **不做旁白、字幕檔（SRT）、多語系版本**
- 不重畫既有 `docs/專案介紹.pptx`

## 驗證重點

- `ffprobe` 確認成片：30fps、時長落在 60–63 秒、`pix_fmt=yuv420p`、寬高皆為偶數
- **補間平滑度程式化檢查**：抽查每段拖曳的連續幀，卡片位移量須符合 ease-in-out 曲線（單調遞增後遞減、無跳點），不靠肉眼判斷
- 唯一畫面數檢查：成片的有效動畫率須遠高於舊版的 0.42 fps（拖曳段須達 30fps）
- 擷取的確定性由「`docker compose down -v` 重置 DB ＋ `storyboard.py` 寫死絕對日期」的設計保證，**但不等於逐位元組一致**：前端 `project-detail.js` 的 `isOverdueDate` 是拿 `new Date()`（執行當下）去比對到期日，`DATES` 雖是絕對日期，一旦過期（見 `scripts/intro-video/README.md`「踩過的坑」），到期日就會從一般樣式變成逾期紅字粗體，同一份 `storyboard.py` 在不同執行時間點就會拍出不同像素。此項原寫「連續跑兩次應一致」從未實際執行過，且用絕對日期換不到「逐位元組一致」的保證，因此改寫為：DB 重置與寫死日期保證的是**內容與版面的確定性**（同一組帳號、同一批建立的專案與任務、同一個拖曳落點），而非**像素級一致**——後者僅在 `DATES` 尚未過期的窗口內成立
- 缺字型時 `sequencer.py` 須明確報錯而非產出豆腐字
- 依 `CLAUDE.md` 完成定義：app 實際啟動記錄、擷取過程 console 無錯、成片抽幀截圖附在回報中
- `demo.mp4` 刪除後全 repo 無殘留引用（`grep` 驗證）
