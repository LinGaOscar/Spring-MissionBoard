# 分類管理畫面 設計文件

## 背景與範圍

`docs/superpowers/plans/2026-08-09-task-oriented-rewrite.md` 完成後，`TaskCategory`／`TaskCategoryPreset` 的 CRUD API 已經存在且有完整測試（`TaskCategoryController`/`TaskCategoryService`），但畫面上完全沒有能建立/管理分類的地方——專案一開始沒有任何分類資料時，看板卡片的「所屬類別」下拉選單永遠是空的，只能靠 SQL 種子資料才有分類可選。CLAUDE.md「下一步」明列這項缺口，本規格補上。

這是三個獨立待補功能中的第一個（分類管理畫面 → 人員派工分頁 → 甘特分頁，依序各自成一份 spec／plan／實作循環）。本規格只涵蓋分類管理畫面。

**不改動後端**：全部使用既有、已測試過的 API（分類 CRUD、選單查詢），純前端功能。

## UI 位置與畫面結構

不做浮動視窗（modal）。維持專案詳情頁「內嵌、非覆蓋式」的既有設計語言（看板本身就是頁面內容的一部分，不是彈出視窗）：

- 看板工具列（`project-detail.js` 的 `KanbanView` 模板，現有「新增任務」按鈕旁）新增一顆「分類管理」切換按鈕
- 點下去在看板上方展開一個內嵌區塊（`v-show`／`v-if` 切換，不是覆蓋在畫面上的浮層），再點一次收合
- 區塊內容：兩層縮排列表（階段 → 類別），每個階段列右側有「+子類別」「刪除」，每個類別列右側有「刪除」；階段列下方一顆「+ 新增階段」

```
[分類管理 ▾]  ← 切換按鈕，展開/收合
┌─────────────────────────────────┐
│ ⠿ SIT                    [+子類別][刪除] │
│   ⠿ 程式開發                    [刪除] │
│   ⠿ 環境建置                    [刪除] │
│ ⠿ UAT                    [+子類別][刪除] │
│ [+ 新增階段]                          │
└─────────────────────────────────┘
```

- `⠿` 是拖曳把手：排序用拖曳，沿用看板卡片既有的原生 HTML5 Drag and Drop 技巧（`draggable`／`@dragstart`／`@dragover.prevent`／`@drop`），不另外實作一套拖曳機制
- **拖曳只能在同一層內重新排序**（階段跟階段之間、同一階段下的子類別之間）。跨層搬移（例如把子類別拖到另一個階段底下、或拖成階段）不支援——後端 `TaskCategoryDto.UpdateRequest` 目前只有 `name`／`sortOrder`，沒有 `parentCategoryId`，不在本次範圍
- 新增階段／子類別：點按鈕跳出一個小型錨定選單（貼著按鈕定位，不是全螢幕浮窗），列出對應型別（`STAGE`／`CATEGORY`）的選單項目供挑選；選了才送出 `POST`，沒選不能送出
- 改名：點分類名稱直接原地變成輸入框編輯（`blur`／`Enter` 送出，`Escape` 取消），互動模式跟看板卡片標題編輯一致

## 資料流與 API 使用

**載入**（展開面板時，若尚未載入過）：
- `GET /api/projects/{id}/task-categories`（沿用 `KanbanView` 已有的 `categories` 狀態，不重複打）
- `GET /api/task-category-presets?type=STAGE&sectionId={sectionId}`
- `GET /api/task-category-presets?type=CATEGORY&sectionId={sectionId}`

`sectionId` 需要從 `#detail-app` 的 `data-section-id` 重新讀取——Task 9 的看板重寫拿掉了這個變數的讀取（當時沒有地方用到），這裡要加回來，供選單查詢用（沿用舊 `WbsNodeRow` 的既有規則：明確帶入專案的 `sectionId`，因為選單可見性驗證是比對「呼叫者所屬科別」，跨科可寫成員需要專案本身的科別才能看到對的選單）。

**新增**：`POST /api/projects/{id}/task-categories`，body `{parentCategoryId, presetId, sortOrder: null}`（沿用既有 `TaskCategoryDto.CreateRequest`，不用改動）。

**改名**：`PUT /api/projects/{id}/task-categories/{categoryId}`，只送 `{name}`（`TaskCategoryService.update` 本來就是欄位各自可省略的局部更新，不用連 `sortOrder` 一起送）。

**刪除**：`DELETE /api/projects/{id}/task-categories/{categoryId}`。刪除前跳確認訊息，依有無子類別調整措辭：
- 階段（有子類別）：「確定刪除「{name}」？其下所有子類別將一併刪除，相關任務會變成未歸類。」
- 類別／無子類別的階段：「確定刪除「{name}」？相關任務會變成未歸類。」

**排序**：後端目前沒有批次搬移端點（不像 `tasks` 有 `/move`）。拖曳放開後，對「同層內順序有變動」的每個分類各自發一支 `PUT .../task-categories/{id}`，body 只送 `{sortOrder: newIndex}`。分類數量通常不多（幾個階段、每階段幾個子類別），用多支請求換取不需新增後端端點。

**畫面同步**：分類管理面板跟看板卡片共用同一份 `KanbanView` 元件內的 `categories` 陣列（同一元件，不是跨元件事件），任何異動後更新這份共用狀態即可，看板卡片編輯 modal 的「所屬類別」下拉選單會自動反映最新的分類清單，不用整頁重新整理。

## 錯誤處理

- 所有操作（新增／改名／刪除／排序）失敗都呼叫既有 `showToast(message)`，沿用 `api()` 的錯誤處理慣例（`{success:false, message}` 或網路例外都走同一條路徑）
- 拖曳排序若批次更新中途失敗（例如 3 支 `PUT` 只有 2 支成功），**不手動回滾個別欄位**，直接重新呼叫 `GET .../task-categories` 同步回伺服器真實狀態並 toast 錯誤訊息——跟看板卡片拖曳失敗時的復原策略一致（`sendMove` 的既有模式）
- 刪除一個仍有任務歸類其下的分類是**預期行為**（`ON DELETE SET NULL`），不是錯誤情境，不需要額外攔截或二次確認（確認訊息本身已經講清楚後果）

## 測試重點

本次不改後端，`TaskCategoryController`/`TaskCategoryService`/`TaskCategoryPresetController` 既有測試維持不動、不需新增。

前端沒有自動化測試框架（先前重構時已確認 repo 內無 `package.json`/`*.config.js`），驗證方式是實際啟動應用程式、瀏覽器操作一輪，並截圖存證：
- 展開／收合面板
- 新增階段（挑 STAGE 選單）、新增子類別（挑 CATEGORY 選單）
- 改名（原地編輯）
- 拖曳排序（同層內，含階段層與子類別層各測一次）
- 刪除有子類別的階段（確認訊息文字正確、子類別一併消失）
- 刪除後，原本歸類在該分類下的任務，在看板卡片編輯 modal 裡「所屬類別」變回「未歸類」
- console 無錯誤

## 範圍外（明確不做）

- 跨層拖曳（改變分類的父層）——後端 API 不支援，需要時另立規格
- 批次搬移端點——先用多支 `PUT` 頂著，數量沒問題就不優化
- 人員派工分頁、甘特分頁——各自獨立規格，不在本次範圍
