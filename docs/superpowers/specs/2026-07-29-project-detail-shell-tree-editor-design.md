# 專案詳情頁外殼＋樹編輯器 設計文件

## 1. 目的與範圍

後端子專案 A–D 已完成，但登入後除了專案列表頁外沒有任何畫面能操作 WBS 節點，所有節點操作只能用 curl 打 API。這份設計要交付「第一個真正能用的畫面」：

- 專案詳情頁外殼：四個 tab（樹編輯器／看板／人員派工／甘特）的頁面骨架、路由、Vue 掛載、CSRF 串接
- 樹編輯器 tab：完整可用（節點 CRUD、狀態、指派、優先度、日期、同層排序、L1 骨架初始化）
- 看板／人員派工／甘特 三個 tab：只放版位文字（如「看板檢視開發中」），tab 按鈕可切但無內容——留給下一輪個別設計

不在這輪範圍：拖拉互動（用按鈕/下拉取代，體驗優化留待下一輪）、成員管理/選單管理/封存歷史等週邊頁面（先繼續用 API 操作）、XLSX 匯出、`GET /api/departments`。

參考總綱 `docs/superpowers/specs/2026-07-17-wbsflow-design.md` §5；本文件是其「前端：單頁四檢視」的第一階段落地。

## 2. 架構與路由

**新頁面路由**（`ProjectController` 新增方法）：

```java
@GetMapping("/projects/{id}")
public String detail(@PathVariable Long id, Model model, Principal principal) {
    User user = currentUser(principal);
    if (!projectService.canRead(id, user)) return "redirect:/projects";
    model.addAttribute("projectId", id);
    model.addAttribute("canWrite", projectService.canWrite(id, user));
    return "project/detail";
}
```

無讀權限直接 302 導回 `/projects`，不拋例外——`GlobalExceptionHandler` 是 `@RestControllerAdvice`，會把例外轉成 JSON 回應，對頁面路由是錯的行為（瀏覽器會顯示裸 JSON 而非網頁）。

**新樣板** `templates/project/detail.html`：沿用 `list.html` 的 header/sidebar/footer fragment 外殼。掛載用根 `<div id="detail-app" th:data-project-id="${projectId}" th:data-can-write="${canWrite}">`，比照舊專案 `board.js` 讀 `el.dataset.*` 的模式，避免前端多一次 API 往返才知道權限。

**CSRF**：`fragments/header.html` 目前只有登出表單的 hidden input，沒有給 JS 讀的 meta tag。新增：

```html
<meta name="_csrf" th:content="${_csrf.token}"/>
<meta name="_csrf_header" th:content="${_csrf.headerName}"/>
```

**Vue 掛載**：新增 `static/js/project-detail.js`，`Vue.createApp` 掛在 `#detail-app`。進頁時 `GET /api/projects/{projectId}/nodes` 一次抓回**扁平清單**（後端 `getTree` 不回巢狀結構，`parentId` 靠前端自己組樹），存進共用響應式陣列 `nodes`。四個 tab 共用同一份 `nodes`，用 `activeTab` ref 搭配 `v-show` 切換（不重新掛載、不重新 fetch）。

## 3. 元件切分

- `project-detail.js`：app 進入點，`loadAll()`（nodes + members + presets）、`activeTab`、共用 `api()` fetch 封裝（帶 CSRF header）、共用 toast 狀態
- `TreeEditorView`（`defineComponent`）：這輪唯一有實作內容的 tab，內部用遞迴 `WbsNodeRow` 元件畫樹（`buildTree(nodes)` 把扁平清單依 `parentId` 組巢狀，並計算階層編號 1/1.1/1.1.1）
- `KanbanView` / `AssignmentView` / `GanttView`：這輪只回傳一段「開發中」文字的空殼元件，之後各自獨立設計時再填內容，共用同一份 `nodes` 資料的介面在這輪就先定好（都吃 `props: ['nodes', 'canWrite']`），避免下一輪要改資料流

## 4. 樹編輯器行為

**工具列**：
- 專案目前無節點（`nodes.length === 0`）時顯示「初始化階段骨架」按鈕 → `POST .../nodes/init`（不帶 body，用專案科別預設 STAGE 選單）
- 每個 L1 節點旁「新增類別」按鈕 → 開小表單選 CATEGORY 選單項目 → `POST .../nodes`（`parentId` + `presetId`）
- 每個 L2 節點旁「新增細項」按鈕 → 開小表單填標題（可選填指派人/優先度/日期）→ `POST .../nodes`（`parentId` + `title`）

**節點列**（`WbsNodeRow`，遞迴）：
- 縮排＋階層編號，來自前端 `buildTree()` 計算，不倚賴後端
- 狀態徽章：L1/L2 唯讀顯示彙總狀態（後端已算好，不可點）；L3 點擊循環 `NOT_STARTED → IN_PROGRESS → DONE`，呼叫 `PATCH .../nodes/{id}/status`
- 標題：雙擊進入行內編輯，`blur`/`Enter` 提交 `PUT .../nodes/{id}`（L1/L2 標題雖是選單快照，但快照本來就允許獨立修改而不影響選單本身，這是既有設計決策）
- L3 專屬欄位：指派人下拉（選項來自 `GET .../members`，只能選專案成員，符合「指派人必須是專案成員」的既有規則）、優先度下拉（HIGH/MEDIUM/LOW）、起訖日 `<input type="date">`，各自對應 `PATCH .../assignee` 或 `PUT .../nodes/{id}`
- 同層「上移」「下移」按鈕：取代拖拉，計算新 `sortOrder` 後呼叫 `PATCH .../nodes/reorder`
- 刪除按鈕＋`confirm()`：有子節點時 confirm 文字要明講「將一併刪除所有子節點」（對應後端 `deleteRecursively`）

**統計列**：L3 總數、完成率（`DONE 數 / L3 總數`），前端就地計算，資料都已在 `nodes` 裡

**權限**：根用 `detail-app` 的 `data-can-write` 決定 `canWrite`；`canWrite === false` 時隱藏所有新增/編輯/刪除/狀態切換/表單控制項，只保留唯讀顯示（服務 DIRECTOR 跨科唯讀、非成員唯讀等情境）

## 5. 錯誤處理與樂觀更新

- 所有寫入操作先更新本地 `nodes`（樂觀更新）再送 fetch；回應 `success:false` 或 fetch 本身失敗，回滾該筆變更並顯示 toast
- 新增共用 toast 元件（畫面右上角浮動訊息，幾秒後自動消失）——專案目前沒有這個元件，這是本輪唯一新建的通用 UI 元件，供之後三個 tab 共用

## 6. 測試計畫

**後端**（比照既有 `ProjectPageTest` 模式）：
- 已登入且有讀權限 → `GET /projects/{id}` 回 200，view 為 `project/detail`
- 已登入但無讀權限 → 302 導回 `/projects`
- 未登入 → 302 導向 `/login`

**前端**（專案沒有 JS 測試框架，走既定的實機驗證流程）：
- 啟動應用，以至少兩種角色登入（`leader` 可寫、`director` 唯讀）
- 樹編輯器實測：初始化骨架、新增類別/細項、改標題/狀態/指派/優先度/日期、上移下移、刪除（含有子節點的情況）
- 確認 `canWrite=false` 時控制項確實隱藏、畫面即時更新無需重整、console 無錯誤
- chrome-devtools 截圖存證（依 CLAUDE.md「有畫面就有截圖」）

## 7. 下一輪預告（不在本輪實作）

看板／人員派工／甘特三個 tab 各自需要獨立的 brainstorming → design → plan 循環，資料流介面（`props: ['nodes', 'canWrite']`）已在本輪定好，之後接續即可，不需重新設計資料共用機制。
