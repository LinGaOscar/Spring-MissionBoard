# WBS 檢視：大項啟用/停用切換、未歸類快速新增任務 設計文件

> 真相來源補充。前置閱讀：`docs/superpowers/specs/2026-08-15-wbs-view-design.md`（WBS 檢視原始設計）、專案根 `CLAUDE.md` 前端模式章節。

## 背景與問題

WBS 檢視底部原本的「+ 新增大項」按鈕，點下去彈出一個選單清單（列出 `task_category_presets` 中 STAGE 型別的項目，如 DEV/SIT/UAT/PROD），選一個就建立對應大類節點。但這個互動方式有兩個問題：

1. 選單只能「新增」，看不出這個專案目前已經啟用了哪些階段、也沒辦法直接把不要的階段停用（只能去看板的「分類管理」面板刪除，入口分散）。
2. WBS 樹狀結構固定的「未歸類」節點，目前沒有任何方式可以直接在 WBS 檢視快速建立一筆任務——要新增任務只能切到看板頁籤用「新增任務」開完整 modal。

## 目標

在 WBS 檢視新增兩個小功能，兩者都只重用既有後端 API，不新增後端端點、不改資料庫：

1. 把「+ 新增大項」按鈕改成一排「啟用/停用」切換鈕，一次看清此專案已啟用哪些階段，並可直接停用
2. 「未歸類」節點旁加一個「+ 新增」，可直接輸入標題快速建立一筆未歸類任務

## 範圍限制

- 只動 WBS 檢視這一個入口。看板「分類管理」面板的「+ 新增階段」「+ 子類別」「改名」「排序」「刪除」全部維持原樣不動
- 只處理 STAGE 層級（大項／parentCategoryId 為 null）的啟用/停用，不處理子類別
- 快速新增任務只在「未歸類」節點提供；其餘大項/子類別節點不加這個入口（要在特定分類底下建任務，走既有看板「新增任務」+ modal 選分類的路徑）

## 設計 A：大項啟用/停用切換

### 判斷「已啟用」

一個 STAGE preset 若在目前專案的 `categories`（`parentCategoryId === null` 的節點）中，存在**同名**節點，視為「已啟用」；否則視為「未啟用」。用名稱比對，因為 preset 選取後名稱是快照存進 `task_categories.name`，兩者本來就該同名。

### 互動

- 未啟用 → 按鈕顯示「啟用」（outline 樣式），點擊呼叫既有的建立分類 API（`POST /api/projects/{id}/task-categories`，body `{ parentCategoryId: null, presetId, sortOrder: null }`），成功後把回傳的分類插入 `this.categories`
- 已啟用 → 按鈕顯示「已啟用」（實心樣式），點擊先跳 `confirm()` 確認框：
  - 有子類別：「確定停用「{name}」？其下所有子類別將一併刪除，相關任務會變成未歸類。」
  - 無子類別：「確定停用「{name}」？相關任務會變成未歸類。」
  （文案與看板分類管理面板的刪除提示一致）
  確認後呼叫既有的刪除分類 API（`DELETE /api/projects/{id}/task-categories/{categoryId}`）。後端 `parent_category_id` 是 `ON DELETE CASCADE`（子類別隨刪）、`tasks.category_id` 是 `ON DELETE SET NULL`（任務落回未歸類），這兩個行為都已存在，前端只需要跟看板既有的 `deleteCategory()` 一樣，在成功後把本地 `categories`/`tasks` 快照同步更新（移除節點、相關任務 `categoryId` 設回 null），避免要重新整理頁面才會顯示正確

### 程式碼異動

- `deleteCategory()` 目前是 `KanbanView` 元件內的方法，改搬到共用的 `categoryPresetMixin`（邏輯完全不變，只是搬家），讓 `WbsView` 也能呼叫同一份實作；`KanbanView` 模板既有的 `@click="deleteCategory(stage)"` / `@click="deleteCategory(cat)"` 呼叫不用改
- `createCategoryFromPreset(presetId)` 加一個可選的第二參數 `parentCategoryId`（預設沿用 `this.presetPicker?.parentCategoryId ?? null`），讓 WBS 的啟用按鈕可以直接呼叫 `createCategoryFromPreset(preset.id, null)`，不用像原本的彈出選單那樣先設定 `presetPicker` 狀態。看板既有呼叫方式 `createCategoryFromPreset(p.id)`（不帶第二參數）行為不變
- `WbsView` 移除原本的 `preset-picker-anchor` 按鈕 + 彈出選單 markup，改成一段 `v-for="p in stagePresets"` 的按鈕列，每個按鈕依「是否已啟用」決定樣式與點擊行為
- CSS 新增啟用/未啟用兩種按鈕樣式（沿用既有 token，不新增顏色）

## 設計 B：未歸類快速新增任務

### 互動

- 「未歸類」節點標題列右側新增一個「+ 新增」按鈕（僅 `canWrite` 時顯示）
- 點擊後在同一列展開一個 inline 文字輸入框（不是 modal），autofocus；按 Enter 或點確認送出，Esc 或點取消關閉
- 送出時呼叫既有的建立任務 API（`POST /api/projects/{id}/tasks`，body `{ categoryId: null, title, description: null }`），成功後把回傳的任務插入 `this.tasks`（比照 `createCategoryFromPreset` 用本地插入取代整頁 `loadAll()`），清空輸入框、收起輸入列
- 標題空白時不送出，顯示既有的 toast 提示（沿用 `taskModalMixin.saveTask()` 的「標題不可為空」文案）
- 建立失敗時顯示錯誤 toast，輸入框保留使用者已輸入的文字，不清空、不收起，讓使用者可以重試

### 程式碼異動

- `WbsView` 新增 local state：`quickAdd: { open: false, title: '' }`
- 新增 method `openQuickAdd()` / `closeQuickAdd()` / `submitQuickAdd()`（邏輯類似上面的互動描述）
- 未歸類節點的 `wbs-node-header` 旁新增按鈕與 inline input 的 markup

## 不做的事（YAGNI）

- 不支援「未歸類」以外節點的快速新增（其餘節點仍走看板新增任務 + modal 選分類）
- 不支援快速新增時順便設定指派人/優先度/日期——只有標題，其餘照 `taskModalMixin` 既有的空白預設值，後續要編輯就點開任務列開 modal
- 不改動 STAGE 以外（子類別層）的啟用/停用 UI
- 不新增後端端點、不改 DDL

## 測試重點

- 啟用一個尚未建立的 STAGE preset → WBS 樹多一個大項節點，按鈕變成「已啟用」樣式
- 停用一個已建立、底下有子類別與任務的 STAGE 節點 → 確認框文案正確、確認後子類別與大項節點一併從樹狀結構消失，原本掛在其下（含子類別下）的任務全部出現在「未歸類」節點，不用重新整理頁面
- 在「未歸類」節點用快速新增建立任務 → 樹狀結構「未歸類」節點的任務數與清單即時更新，不用重新整理頁面；空白標題不送出並顯示提示
- 唯讀角色（`canWrite === false`）看不到啟用/停用按鈕與未歸類的「+ 新增」按鈕
- 封存專案（`canWrite` 恆為 false）同上，兩個入口都不顯示
- 看板「分類管理」面板的既有新增/改名/刪除/排序功能不受影響（`deleteCategory` 搬家後行為不變）
