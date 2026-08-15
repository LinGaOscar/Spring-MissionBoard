# WBS 檢視分頁 設計文件

## 背景與範圍

任務導向重構時（`docs/superpowers/specs/2026-08-08-missionboard-task-oriented-rewrite-design.md`），舊的樹編輯器／人員派工／甘特三個分頁因資料模型改變被整個移除，列為後續依新模型各自重做。人員派工分頁已依新模型重做完成（`docs/superpowers/specs/2026-08-12-assignment-view-design.md`）。這是第三個待補功能：**WBS 檢視分頁**，甘特分頁仍不在本次範圍。

舊 `Spring-WbsFlow` 的 WBS 樹編輯器是「先建 L1/L2 階層骨架、再往下長出可派工的 L3 細項」，三層語意由 DB CHECK 約束強制。這套模型已隨任務導向重構整個捨棄，**不可沿用**。本次是依現行扁平 `tasks` + 選配兩層 `task_categories` 模型全新設計，只是同一份資料的另一種檢視角度，不恢復舊的階層強制規則。

**不改動後端**：沿用既有 `GET/PUT /api/projects/{id}/tasks`、`GET /api/projects/{id}/task-categories`、`GET /api/projects/{id}/members` 端點，純前端功能。

## 兩個分頁的分工（設計前提）

- **看板（Mission 導向，`KanbanView`）**：欄位＝狀態（未開始/進行中/已完成），核心是快速決定「這個任務給誰做」，任務 modal 可編輯完整內容（標題、描述、指派、優先度、日期、所屬分類）
- **WBS 檢視（`WbsView`，本次新增）**：核心只做一件事——快速把任務**移動到哪個大類（例如 SIT/UAT/PROD 這類大區塊）或子類底下**，不重做狀態管理、不重做內容編輯。任務內容的完整編輯仍點開同一顆 modal，只是入口在樹狀節點上而非看板欄位裡

## 元件與程式碼共用

### 分頁結構

`project-detail.js` 根元件目前是 `activeTab`（`'kanban'` / `'assignment'`）雙分頁切換。本次加第三個值 `'wbs'`，畫面上方變成三顆分頁按鈕：`看板` / `人員派工` / `WBS 檢視`。沿用既有 `v-if`（非 `v-show`）切換，理由同人員派工分頁的既有結論：三個分頁各自獨立 fetch 一份 `tasks`，`v-show` 會讓多個元件同時常駐掛載造成資料不同步。

### 抽出 `taskModalMixin`

現有「新增/編輯/刪除任務」modal 邏輯（`openCreate`/`openEdit`/`saveTask`/`deleteTask`，約 140 行）目前寫死在 `KanbanView` 內部。人員派工分頁設計時曾因為「借用需複製一份邏輯或拆共用元件，超出當時範圍」而明確排除點卡片開 modal（見 `2026-08-12-assignment-view-design.md` 「互動」小節）。這次 `WbsView` 的核心互動就是「點任務節點開 modal 改分類」，非做這個重構不可，時機成熟：

- 把 `openCreate`/`openEdit`/`saveTask`/`deleteTask`、`modal` 狀態、modal 的 HTML 片段抽成 `taskModalMixin`，比照現有 `toastMixin` 的抽法（同檔案內、`mixins` 陣列引入）
- `KanbanView` 改用此 mixin（行為不變，純搬移程式碼）
- `WbsView` 引入同一份 mixin，任務節點的「編輯」入口呼叫 `openEdit(task)` 開同一顆 modal
- `AssignmentView` **不**引入此 mixin，維持 v1 範圍排除點卡片開 modal 的既有決定不變

### `WbsView` 新元件

與 `KanbanView`/`AssignmentView` 平行、各自獨立 `mounted()` 時 fetch 自己的 `tasks`/`categories`/`members`（`members` 是因為 modal 內指派人下拉需要）。

## 樹狀資料結構與畫面

節點三層：大類（`task_categories.parentCategoryId === null`）→ 子類 → 任務葉列。額外加一個固定置頂的虛擬節點「未歸類」，收容 `task.categoryId === null` 的任務，確保任務永遠可見（呼應「任務天生獨立、不依附分類」原則，不會有任務因為沒有分類而在這個檢視裡消失）。

```
WBS 檢視
┌───────────────────────────────────────────┐
│ ▾ 未歸類 (2)                    50%        │
│    · 任務X  [進行中]  王小明  08/20        │
│    · 任務Y  [未開始]  --      --           │
│ ▾ SIT (5)                       40%        │
│   ▾ 環境建置 (3)                33%        │
│      · 主機申請  [已完成] 陳大華 08/10     │
│      · 防火牆開通 [進行中] 陳大華 08/15    │
│      · 主機佈版  [未開始] --    --         │
│   ▸ 系統開發 (2)                50%        │
│ ▸ UAT (0)                        --        │
│ ▸ PROD (0)                       --        │
└───────────────────────────────────────────┘
```

- 每個分類節點（大類/子類/未歸類）旁顯示彙總：任務數 + 完成度（`DONE` 數 / 總數，無任務時顯示 `--`），前端用當下 `this.tasks` 即時算，不需新後端欄位
- 節點展開/收合：元件內 local state（例如每個節點一個 `expanded` boolean），預設全展開。**v1 不做**全域「全展開/全收合」按鈕（資料量小，YAGNI，之後有需要再加）
- 任務列顯示：標題、狀態小徽章（沿用看板既有樣式）、指派人、到期日
- 同分類底下任務排序：沿用人員派工分頁既有規則——依 `dueDate` 升冪，無到期日排最後，同值時依 `id` 升冪穩定排序。**不使用 `sort_order`、不呼叫 `move` 端點**

## 互動

### 拖曳任務改分類（核心功能）

- 任務列可拖曳，放到任一分類節點（大類、子類、或「未歸類」）上即改變該任務的 `categoryId`
- 呼叫既有 `PUT /api/projects/{id}/tasks/{taskId}`，帶入該任務**目前的完整欄位**（`title`/`description`/`priority`/`startDate`/`dueDate`）＋新的 `categoryId`，其餘不變——與 `taskModalMixin` 的 `saveTask()` 組 payload 方式相同，不新增後端端點
- 本地樂觀更新：先改 `task.categoryId`，畫面上任務即刻移動到新節點下；失敗則回滾＋`showToast`
- 沿用 `taskWriteQueue`：同一任務的連續寫入序列化，避免拖曳與 modal 編輯併發時後完成者用舊快照蓋掉新資料
- `canWrite === false`（唯讀角色／封存專案）時任務列 `draggable` 關閉，僅供瀏覽，沿用看板既有 pattern

### 點任務開 modal

- 點任務列 → 呼叫 `taskModalMixin` 的 `openEdit(task)`，開啟與看板完全相同的編輯 modal（含所屬分類下拉——modal 內改分類與樹狀拖曳改分類是同一個 `categoryId` 欄位，兩種操作方式互通）
- Modal 存檔後（`saveTask()`）觸發 `loadAll()` 重新整理本分頁的樹狀資料

### 分類的新增/改名/刪除/排序

**不**在 WBS 分頁做，維持只在看板既有「分類管理」面板操作。原因：這套邏輯（`loadCategoryPresets`/`createCategoryFromPreset`/`commitCategoryName`/`deleteCategory`/`onCategoryDragStart`/`onCategoryDrop`，約 120 行）已經在 `KanbanView` 跑得穩定，WBS 分頁若重複一份會變成兩處維護同一份分類 CRUD 邏輯；WBS 分頁的定位是「移動任務」，不是「管理分類骨架」，兩者職責分開。

## 資料流與 API 使用

**載入**（`mounted()`，`v-if` 每次切回都重新觸發）：
- `GET /api/projects/{id}/tasks`
- `GET /api/projects/{id}/task-categories`
- `GET /api/projects/{id}/members`

**拖曳改分類 / modal 存檔**：`PUT /api/projects/{id}/tasks/{taskId}`（沿用既有 `TaskController`/`TaskService.updateTask`，無需改動）

## 錯誤處理

沿用既有 `api()` 骨架（CSRF、網路錯誤攔截、`showToast`）；拖曳失敗時回滾 `categoryId` 到拖曳前的值＋toast 錯誤訊息，不整頁重抓。

## 範圍外（明確不做）

- 分類的新增/改名/刪除/排序——回看板「分類管理」面板做
- 樹狀列內直接編輯負責人/日期/狀態（不開 modal）——先都走既有 modal，行為與看板一致
- 全域「全展開/全收合」按鈕、搜尋節點——資料量小，v1 不需要
- 任務在同分類底下的手動拖曳排序——固定依到期日排序即可
- 甘特分頁——獨立規格，不在本次範圍

## 測試重點

本次不改後端，`TaskController`/`TaskService` 既有測試維持不動、不需新增。

前端沒有自動化測試框架，驗證方式是實際啟動應用程式、瀏覽器操作一輪並截圖存證：
- 三分頁切換（看板 ↔ 人員派工 ↔ WBS 檢視），確認資料一致（在看板改分類，切到 WBS 分頁看得到最新結果；反之亦然）
- WBS 分頁樹狀結構正確：大類/子類/任務三層、「未歸類」節點收容無分類任務
- 節點彙總數字正確（任務數、完成度）
- 拖曳任務到不同大類/子類節點，確認後端已寫入（重新整理後仍在新分類下）
- 點任務列開 modal，確認與看板開出的是同一顆 modal、存檔後樹狀資料同步更新
- 唯讀角色（如封存專案）任務列不可拖曳
- `KanbanView` 抽出 `taskModalMixin` 後，看板原有的新增/編輯/刪除任務行為不受影響（回歸測試）
- console 無錯誤

## 後續清理

實作完成後，`CLAUDE.md` 需補上 WBS 檢視分頁的說明（三分頁現況、`taskModalMixin` 共用邏輯），並同步更新「舊的樹編輯器／人員派工／甘特三個分頁曾隨重構移除」那句，反映樹編輯器已依新模型重做完成，僅剩甘特待補。
