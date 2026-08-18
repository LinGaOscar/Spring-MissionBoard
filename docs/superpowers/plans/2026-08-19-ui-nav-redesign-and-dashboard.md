# 側邊欄導覽重構、白色簡約視覺、WBS 新增大項、首頁儀表板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把看板/人員派工/WBS 檢視三個分頁按鈕移入側邊欄第二層、全站視覺改為白色簡約 token 化風格、WBS 檢視補上「新增大項」、首頁改為依角色分三層（PERSONAL/SECTION/ORG）的儀表板。

**Architecture:** 前兩項是既有畫面的重構（CSS token 替換 + Vue 3 `<Teleport>` 把 tab 導覽渲染進靜態 Thymeleaf 側邊欄 fragment），後兩項是新功能（WBS 沿用看板既有的 preset picker 邏輯抽成共用 mixin；首頁新增一支依角色分流的 REST 端點 + 對應 Vue 頁面，比照 `project-list.js` 的既有轉換模式）。全程不改變看板/人員派工/WBS 既有的拖曳、任務 modal、分類管理面板互動邏輯。

**Tech Stack:** Java 21 + Spring Boot 3.4.x、Spring Data JPA、Vue 3（vendored `vue.global.prod.min.js`，無 build 工具）、Thymeleaf 3、JUnit 5 + AssertJ + H2（`MODE=PostgreSQL`）。

## Global Constraints

- 不改變看板/人員派工/WBS 檢視既有的拖曳、任務 modal、分類管理面板等互動邏輯與 API 契約，只調整視覺樣式與新增 WBS 的「+新增大項」入口
- WBS 檢視不做「新增子類別」「改名」「刪除」「排序」——這些維持只在看板「分類管理」面板操作
- 首頁儀表板不做「已完成任務」清單、不做跨專案任務的拖曳/編輯，純瀏覽用途，點擊導向對應專案詳情頁
- `SECTION`／`ORG` 視角的彙總列不做「點擊展開任務細節」；`ORG` 彙總列不可點擊，`SECTION` 彙總列可點擊導向專案詳情頁
- 不引入外部字型（Google Fonts 等）、不引入任何 build 工具，字體用系統字體堆疊，維持專案既有「全 vendored」慣例
- 不做深色模式、不做側邊欄可收合（v1 範圍外）
- `.task-card`／`.wbs-task-row` 的 `priority-HIGH/MEDIUM/LOW` 左側色條維持既有紅/橘/綠語意色（`#d63031`/`#e17055`/`#00b894`），這三個規則的色碼**不可**替換成 token
- `Project.getSection()`（非 `getDepartment()`）回傳 `com.missionboard.department.Department`；`User.getDepartment()` 回傳 `Department`（可為 null）；`Project.isArchived()`（非 `getArchived()`，Lombok 對 primitive boolean 的規則）
- `UserController` 位於 `com.missionboard.user` package（非 `com.missionboard.project`）
- 全專案 `api()` fetch 骨架**沒有共用檔案**，`project-list.js`／`project-detail.js`／新的 `home.js` 各自在自己的 IIFE 內複製一份幾乎相同的實作，這是既有慣例，不要為此新增共用模組
- 全專案**沒有 `<Teleport>` 的既有先例**，Task 2 是第一次使用；已確認 vendored 的 `vue.global.prod.min.js` 內建此功能

---

## Task 1: 視覺 Token 全站套用

**Files:**
- Modify: `src/main/resources/static/css/app.css`（全檔，共 120 行）

**Interfaces:**
- Produces: `:root`區塊定義的 7 個 CSS 自訂屬性（`--paper`／`--surface`／`--ink`／`--ink-muted`／`--line`／`--signal`／`--danger`）與 2 個字體變數（`--font-ui`／`--font-mono`），供 Task 2、Task 3、Task 5 新增的 CSS 規則使用

這是純 CSS 檔案，專案沒有 CSS 測試框架，本任務的「測試」是啟動應用程式後用瀏覽器截圖比對，不走 TDD 的 red-green 迴圈。

- [ ] **Step 1: 用下方完整內容覆寫 `app.css`**

這個檔案目前完全沒有 `:root` 自訂屬性，18 個色碼全部硬編在各規則裡（含兩處 `box-shadow` 卡片浮起效果、`priority-HIGH/MEDIUM/LOW` 各自重複定義兩次）。下方內容做了三件事：(1) 新增 `:root` token 區塊；(2) 逐一把色碼替換成對應 token（`priority-HIGH/MEDIUM/LOW` 六條規則的色碼**維持原樣不動**，這是 Global Constraints 的硬性要求）；(3) 移除所有 `box-shadow` 卡片浮起效果，改用 `border: 1px solid var(--line)` 表達邊界（`.login-box`／`.data-table`／`.modal`／`.project-card`／`.task-card`／`.wbs-task-row` 新增或已有的 1px 邊框；`.project-card:hover` 的陰影效果改為 `background: var(--surface)`；`.cursor-dot` 的陰影是presence 游標的可視性邊框、非卡片浮起效果，維持原樣不動）。同時套用字體 token：`body` 改用 `var(--font-ui)`；`--font-mono` 套用在任務到期日（`.task-due`／`.wbs-task-due`）、WBS 節點名稱與完成度數字（`.wbs-node-name`／`.wbs-node-summary`）、看板欄位任務數徽章（`.kanban-col-count`）這五處。

```css
:root {
  --paper: #FFFFFF;
  --surface: #F7F7F8;
  --ink: #1A1A1A;
  --ink-muted: #6B6B70;
  --line: #E4E4E4;
  --signal: #1D4ED8;
  --danger: #B91C1C;
  --font-ui: -apple-system, "PingFang TC", "Segoe UI", "Microsoft JhengHei", sans-serif;
  --font-mono: ui-monospace, "SF Mono", "Cascadia Code", monospace;
}
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: var(--font-ui); background: var(--paper); color: var(--ink); }
.navbar { display: flex; justify-content: space-between; align-items: center; padding: 0 1.5rem; height: 56px; background: var(--paper); border-bottom: 1px solid var(--line); color: var(--ink); }
.navbar a { color: var(--ink); text-decoration: none; font-weight: 600; font-size: 1.1rem; }
.navbar-user { display: flex; align-items: center; gap: 1rem; font-size: 0.9rem; }
.btn-link { background: none; border: none; color: var(--ink); cursor: pointer; font-size: 0.9rem; text-decoration: underline; }
.layout { display: flex; min-height: calc(100vh - 56px - 48px); }
.sidebar { width: 220px; background: var(--surface); border-right: 1px solid var(--line); padding: 1rem 0; flex-shrink: 0; }
.sidebar ul { list-style: none; }
.sidebar ul li a { display: block; padding: 0.65rem 1.5rem; color: var(--ink); text-decoration: none; font-size: 0.9rem; }
.sidebar ul li a:hover { background: var(--paper); color: var(--signal); }
.sidebar-group-label { padding: 0.6rem 1.5rem 0.3rem; font-size: 0.72rem; font-weight: 700; color: var(--ink-muted); text-transform: uppercase; letter-spacing: 0.06em; margin-top: 0.5rem; }
.main-content { flex: 1; padding: 2rem; }
.footer { height: 48px; background: var(--surface); display: flex; align-items: center; justify-content: center; font-size: 0.85rem; color: var(--ink-muted); }
.login-page { display: flex; justify-content: center; align-items: center; min-height: 100vh; }
.login-box { background: var(--paper); padding: 2.5rem; border-radius: 8px; border: 1px solid var(--line); width: 380px; }
.login-box h1 { text-align: center; margin-bottom: 1.5rem; font-size: 1.4rem; }
.form-group { margin-bottom: 1rem; }
.form-group label { display: block; margin-bottom: 0.4rem; font-size: 0.9rem; font-weight: 500; }
.form-group input, .form-group select { width: 100%; padding: 0.6rem 0.8rem; border: 1px solid var(--line); border-radius: 4px; font-size: 0.95rem; }
.form-group input:focus, .form-group select:focus { outline: none; border-color: var(--signal); }
.btn { padding: 0.6rem 1.2rem; border: 1px solid var(--line); border-radius: 4px; cursor: pointer; font-size: 0.95rem; background: var(--paper); }
.btn-primary { background: var(--signal); color: var(--paper); border-color: var(--signal); }
.btn-primary:hover { filter: brightness(0.88); }
.btn-block { width: 100%; margin-top: 0.5rem; }
.sidebar ul li a.active { background: var(--surface); color: var(--signal); font-weight: 600; border-left: 3px solid var(--signal); padding-left: calc(1.5rem - 3px); }
.btn-sm { padding: 0.3rem 0.6rem; font-size: 0.8rem; margin-right: 0.25rem; }
.btn-danger { border-color: var(--danger); color: var(--danger); }
.btn-danger:hover { background: var(--danger); color: var(--paper); }
.alert { padding: 0.75rem 1rem; border-radius: 4px; margin-bottom: 1rem; font-size: 0.9rem; }
.alert-error { background: #ffe0e0; color: var(--danger); }
.alert-info { background: var(--surface); color: var(--signal); }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.5rem; }
.data-table { width: 100%; border-collapse: collapse; background: var(--paper); border-radius: 6px; overflow: hidden; border: 1px solid var(--line); }
.data-table th, .data-table td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid var(--line); font-size: 0.9rem; }
.data-table th { background: var(--surface); font-weight: 600; color: var(--ink-muted); }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: var(--paper); border-radius: 8px; padding: 2rem; width: 440px; border: 1px solid var(--line); }
.modal h3 { margin-bottom: 1.5rem; font-size: 1.1rem; }
.modal-actions { display: flex; gap: 0.75rem; margin-top: 1.5rem; }
.project-grid { display: grid; grid-template-columns: repeat(auto-fill,minmax(260px,1fr)); gap: 1rem; }
.project-card { display:block; background:var(--paper); border-radius:8px; padding:1.25rem 1.5rem; cursor:pointer; border:1px solid var(--line); transition:background 0.15s; text-decoration:none; color:inherit; }
.project-card:hover { background: var(--surface); }
.project-card-name { font-size:1.05rem; font-weight:600; margin-bottom:0.5rem; }
.project-card-meta { font-size:0.85rem; color:var(--ink-muted); }
.presence-bar { display:flex; gap:6px; align-items:center; }
.presence-avatar { width:32px; height:32px; border-radius:50%; display:flex; align-items:center; justify-content:center; color:var(--paper); font-weight:700; font-size:0.85rem; cursor:default; }
.cursor-indicators { display:flex; gap:3px; margin-left:4px; }
.cursor-dot { width:10px; height:10px; border-radius:50%; display:inline-block; border:2px solid var(--paper); box-shadow:0 0 0 1px rgba(0,0,0,0.2); }
/* 匯出下拉選單（純 CSS hover） */
.export-dropdown { position:relative; display:inline-block; }
.export-dropdown .dropdown-menu { display:none; position:absolute; right:0; top:calc(100% + 2px); background:var(--paper); border:1px solid var(--line); border-radius:4px; z-index:200; min-width:100px; }
.export-dropdown:hover .dropdown-menu { display:block; }
.dropdown-item { display:block; width:100%; padding:0.45rem 1rem; text-align:left; background:none; border:none; cursor:pointer; font-size:0.85rem; color:var(--ink); }
.dropdown-item:hover { background:var(--surface); }
.toast { position:fixed; top:70px; right:1.5rem; background:var(--ink); color:var(--paper); padding:0.75rem 1.25rem; border-radius:6px; z-index:2000; font-size:0.9rem; }
.kanban-toolbar { margin-bottom: 1rem; }
.kanban { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; align-items: start; }
.kanban-col { background: var(--surface); border-radius: 8px; padding: 0.75rem; min-height: 320px; }
.kanban-col-header { display: flex; justify-content: space-between; align-items: center; font-weight: 700; font-size: 0.9rem; color: var(--ink-muted); padding: 0.25rem 0.5rem 0.75rem; }
.kanban-col-count { background: var(--ink-muted); color: var(--paper); border-radius: 10px; padding: 0 0.55rem; font-size: 0.75rem; font-family: var(--font-mono); }
.kanban-col.drag-over { outline: 2px dashed var(--signal); outline-offset: -4px; }
.task-card { background: var(--paper); border: 1px solid var(--line); border-radius: 6px; padding: 0.75rem 0.9rem; margin-bottom: 0.6rem; cursor: grab; border-left: 4px solid var(--line); }
.task-card:active { cursor: grabbing; }
.task-card.priority-HIGH { border-left-color: #d63031; }
.task-card.priority-MEDIUM { border-left-color: #e17055; }
.task-card.priority-LOW { border-left-color: #00b894; }
.task-card.dragging { opacity: 0.4; }
.task-card-title { font-size: 0.95rem; font-weight: 600; margin-bottom: 0.4rem; }
.task-card-meta { display: flex; justify-content: space-between; align-items: center; font-size: 0.8rem; color: var(--ink-muted); }
.task-due { font-family: var(--font-mono); }
.task-due.overdue { color: var(--danger); font-weight: 700; }
.category-panel { background:var(--paper); border:1px solid var(--line); border-radius:8px; padding:1rem 1.25rem; margin-bottom:1.5rem; }
.category-row-group { margin-bottom:0.4rem; }
.category-row { display:flex; align-items:center; gap:0.5rem; padding:0.4rem 0.25rem; border-radius:4px; cursor:grab; }
.category-row:active { cursor:grabbing; }
.category-row-child { padding-left:1.75rem; }
.category-handle { color:var(--ink-muted); font-size:0.9rem; }
.category-name { flex:1; }
.category-name-input { flex:1; padding:0.2rem 0.4rem; border:1px solid var(--signal); border-radius:4px; }
.category-row-actions { display:flex; align-items:center; gap:0.25rem; margin-left:auto; }
.preset-picker-anchor { position:relative; display:inline-block; }
.preset-picker-popover { position:absolute; top:calc(100% + 4px); left:0; background:var(--paper); border:1px solid var(--line); border-radius:6px; z-index:300; min-width:180px; padding:0.6rem; }
.preset-picker-popover p { font-size:0.8rem; color:var(--ink-muted); margin-bottom:0.4rem; }
.preset-picker-popover ul { list-style:none; margin:0 0 0.4rem; padding:0; }
.preset-picker-popover li { margin-bottom:0.25rem; }
.detail-tabs { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.assignment-toolbar { display: flex; justify-content: flex-end; margin-bottom: 1rem; }
.assignment-toggle { display: flex; align-items: center; gap: 0.4rem; font-size: 0.9rem; cursor: pointer; }
.assignment-board { display: flex; gap: 1rem; overflow-x: auto; padding-bottom: 0.5rem; align-items: start; }
.assignment-col { flex: 0 0 240px; }
.wbs-tree { display: flex; flex-direction: column; gap: 0.4rem; }
.wbs-node { background: var(--paper); border: 1px solid var(--line); border-radius: 6px; padding: 0.5rem 0.75rem; }
.wbs-node.drag-over { outline: 2px dashed var(--signal); outline-offset: -4px; }
.wbs-node-header { display: flex; align-items: center; gap: 0.5rem; cursor: pointer; font-weight: 600; padding: 0.25rem 0; }
.wbs-node-toggle { width: 1rem; color: var(--ink-muted); }
.wbs-node-name { flex: 1; font-family: var(--font-mono); }
.wbs-node-summary { font-size: 0.85rem; color: var(--ink-muted); font-family: var(--font-mono); }
.wbs-node-body { margin-left: 1.5rem; margin-top: 0.4rem; display: flex; flex-direction: column; gap: 0.4rem; }
.wbs-node-child { margin-top: 0.4rem; }
.wbs-task-list { margin-left: 1.5rem; margin-top: 0.3rem; display: flex; flex-direction: column; gap: 0.3rem; }
.wbs-task-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.4rem 0.6rem; border-radius: 4px; background: var(--surface); border-left: 4px solid var(--line); cursor: grab; font-size: 0.88rem; }
.wbs-task-row:active { cursor: grabbing; }
.wbs-task-row.priority-HIGH { border-left-color: #d63031; }
.wbs-task-row.priority-MEDIUM { border-left-color: #e17055; }
.wbs-task-row.priority-LOW { border-left-color: #00b894; }
.wbs-task-title { flex: 1; font-weight: 500; }
.wbs-task-status { color: var(--ink-muted); }
.wbs-task-assignee { color: var(--ink-muted); }
.wbs-task-due { font-family: var(--font-mono); }
.wbs-task-due.overdue { color: var(--danger); font-weight: 700; }
.project-toolbar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; }
.archived-badge { background: #ffe0e0; color: var(--danger); padding: 0.25rem 0.6rem; border-radius: 4px; font-size: 0.8rem; font-weight: 600; }
.member-panel { background: var(--paper); border: 1px solid var(--line); border-radius: 8px; padding: 1rem 1.25rem; margin-bottom: 1.5rem; }
.member-list { list-style: none; margin-bottom: 0.75rem; }
.member-row { display: flex; justify-content: space-between; align-items: center; padding: 0.4rem 0.25rem; border-bottom: 1px solid var(--line); font-size: 0.9rem; }
.member-owner-badge { background: var(--surface); color: var(--signal); border-radius: 10px; padding: 0.1rem 0.5rem; font-size: 0.75rem; margin-left: 0.5rem; }
.member-panel-actions { display: flex; flex-direction: column; gap: 0.6rem; padding-top: 0.6rem; border-top: 1px solid var(--line); }
.member-add-row, .member-owner-row { display: flex; gap: 0.5rem; align-items: center; }
.member-add-row select, .member-owner-row select { flex: 1; padding: 0.4rem 0.6rem; border: 1px solid var(--line); border-radius: 4px; font-size: 0.85rem; }
.member-owner-row label { font-size: 0.85rem; color: var(--ink-muted); white-space: nowrap; }
.wbs-toolbar { margin-bottom: 1rem; }
```

- [ ] **Step 2: 啟動應用程式，手動驗證視覺變更**

```bash
docker compose up -d
mvn spring-boot:run
```

用 `leader`/`password123` 登入，依序截圖：`/login`（白底、無深色 navbar 殘留）、`/projects`（專案卡片無陰影、有細邊框）、`/projects/{id}`（看板任務卡片、`.btn-primary` 為 `--signal` 藍、`priority-HIGH` 左側色條仍是紅色）。用瀏覽器開發者工具的 Elements 面板檢查 `.navbar` 的 `background-color` 計算值應為 `rgb(255, 255, 255)`（即 `--paper`）。

- [ ] **Step 3: grep 確認舊色碼只殘留在允許的例外規則**

```bash
grep -n "#[0-9a-fA-F]\{3,6\}" src/main/resources/static/css/app.css
```

這個指令也會比對到 `:root` 區塊裡 token 定義本身的 7 個色碼，所以預期共 15 行：7 行 token 定義＋6 行 `priority-HIGH/MEDIUM/LOW`（`.task-card` 與 `.wbs-task-row` 各 3 行）＋2 行 `.alert-error`/`.archived-badge` 的 `#ffe0e0` 背景（Global Constraints 未涵蓋的危險提示底色，刻意不 token 化，保留原有淡紅色視覺）。若非這 15 行的其他色碼出現，回頭修正對應規則。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/css/app.css
git commit -m "style: 全站視覺改為白色簡約 token 化色彩與字體"
```

---

## Task 2: 側邊欄導覽重構

**Files:**
- Modify: `src/main/java/com/missionboard/project/ProjectController.java:28-42`
- Modify: `src/main/resources/templates/project/detail.html`（全檔 25 行）
- Modify: `src/main/resources/templates/fragments/sidebar.html`（全檔 12 行）
- Modify: `src/main/resources/static/js/project-detail.js`（第 1-11 行常數宣告、第 892-899 行 root app `data()`、第 992-1041 行 root app `template`）
- Modify: `src/main/resources/static/css/app.css`（新增規則，延續 Task 1 的 token）
- Test: `src/test/java/com/missionboard/project/ProjectDetailPageTest.java`

**Interfaces:**
- Consumes: Task 1 產出的 `--line`／`--signal`／`--surface`／`--ink`／`--ink-muted` token
- Produces: `detail.html` 的 `#detail-app` 掛載點新增 `data-project-name` 屬性；`project-detail.js` root app 的 `activeTab` 資料驅動的側邊欄導覽（後續 Task 3 會在同一段 template 裡對 `<wbs-view>` 標籤追加 `:section-id` 屬性）

- [ ] **Step 1: 寫失敗的後端 pinning test**

在 `src/test/java/com/missionboard/project/ProjectDetailPageTest.java` 現有 `detailPageExposesArchiveRelatedModelAttributes` 測試方法後面新增：

```java
@Test
void detailPageExposesProjectName() throws Exception {
    Cookie session = loginAs("leaderX");

    mockMvc.perform(get("/projects/" + project.getId()).cookie(session))
        .andExpect(status().isOk())
        .andExpect(model().attribute("projectName", project.getName()));
}
```

- [ ] **Step 2: 執行測試確認失敗**

```bash
mvn test -Dtest=ProjectDetailPageTest#detailPageExposesProjectName
```

預期失敗，因為 model 目前沒有 `projectName` 屬性。

- [ ] **Step 3: 修改 `ProjectController.detail()`，加入 `projectName`**

把 `src/main/java/com/missionboard/project/ProjectController.java` 第 28-42 行的 `detail()` 方法改成：

```java
@GetMapping("/projects/{id}")
public String detail(@PathVariable Long id, org.springframework.ui.Model model, Principal principal) {
    User user = currentUser(principal);
    if (!projectService.canRead(id, user)) {
        return "redirect:/projects";
    }
    Project project = projectService.getById(id);
    model.addAttribute("projectId", id);
    model.addAttribute("projectName", project.getName());
    model.addAttribute("canWrite", projectService.canWrite(id, user));
    model.addAttribute("canArchive", projectService.canArchive(id, user));
    model.addAttribute("archived", project.isArchived());
    model.addAttribute("ownerId", project.getOwner().getId());
    model.addAttribute("sectionId", project.getSection().getId());
    return "project/detail";
}
```

- [ ] **Step 4: 執行測試確認通過**

```bash
mvn test -Dtest=ProjectDetailPageTest
```

預期全部通過（含既有的其他 pinning tests）。

- [ ] **Step 5: `detail.html` 的掛載點新增 `data-project-name`**

把 `src/main/resources/templates/project/detail.html` 的 `#detail-app` div 改成：

```html
<div id="detail-app" th:data-project-id="${projectId}" th:data-project-name="${projectName}" th:data-can-write="${canWrite}"
     th:data-can-archive="${canArchive}" th:data-archived="${archived}" th:data-owner-id="${ownerId}"
     th:data-section-id="${sectionId}">
  <p>載入中...</p>
</div>
```

- [ ] **Step 6: `sidebar.html` 新增 Teleport 目標**

把 `src/main/resources/templates/fragments/sidebar.html` 改成：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<nav th:fragment="sidebar" class="sidebar">
  <ul>
    <li><a th:href="@{/home}">首頁</a></li>
    <li><a th:href="@{/projects}">專案列表</a></li>
  </ul>
  <div id="project-nav-slot"></div>
</nav>
</body>
</html>
```

這個 fragment 是所有頁面共用的靜態元件，`#project-nav-slot` 在非專案詳情頁（首頁、專案列表頁）永遠是空的——那些頁面沒有掛載 `#detail-app` 的 Vue app，不會有人傳送內容進來，視覺上不會多出任何東西。

- [ ] **Step 7: `project-detail.js` 讀取 `projectName`**

在 `src/main/resources/static/js/project-detail.js` 第 5-10 行的常數宣告區塊，`sectionId` 那行後面新增一行：

```js
const projectName = el.dataset.projectName;
```

- [ ] **Step 8: root app `data()` 加入 `projectName`**

把第 892-899 行的 root app `data()` 改成：

```js
data() {
  return {
    projectId, projectName, canWrite, canArchive, archived, sectionId, activeTab: 'kanban',
    ownerId, dataVersion: 0,
    memberPanelOpen: false, membersLoaded: false, membersLoading: false,
    members: [], allUsers: [], addingUserId: null, changingOwnerId: null,
  };
},
```

- [ ] **Step 9: root app template 移除舊分頁按鈕、改用 Teleport**

把第 992-1041 行 root app 的 `template` 字串裡，第 1031-1035 行的這一段：

```html
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'wbs' }" @click="activeTab = 'wbs'">WBS 檢視</button>
        </div>
```

整段替換成：

```html
        <Teleport to="#project-nav-slot">
          <div class="sidebar-project-nav">
            <div class="sidebar-project-name">▾ {{ projectName }}</div>
            <ul>
              <li :class="{ active: activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</li>
              <li :class="{ active: activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</li>
              <li :class="{ active: activeTab === 'wbs' }" @click="activeTab = 'wbs'">WBS 檢視</li>
            </ul>
          </div>
        </Teleport>
```

`▾` 是純裝飾用的固定符號，不是可點擊的展開/收合控制項——v1 側邊欄固定展開（見 Global Constraints）。`activeTab` 資料本身不變，繼續驅動下方三個檢視元件的 `v-if`/`v-else-if` 切換，這段替換不影響 `<kanban-view>`／`<assignment-view>`／`<wbs-view>` 那三行。

- [ ] **Step 10: 新增側邊欄專案導覽的 CSS，並延伸 `.project-toolbar`**

在 `src/main/resources/static/css/app.css` 檔尾新增：

```css
.sidebar-project-nav { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid var(--line); }
.sidebar-project-name { font-size: 0.85rem; font-weight: 600; color: var(--ink-muted); padding: 0 1.5rem 0.5rem; }
.sidebar-project-nav ul { list-style: none; }
.sidebar-project-nav li { padding: 0.5rem 1.5rem 0.5rem 2.25rem; font-size: 0.9rem; cursor: pointer; color: var(--ink); }
.sidebar-project-nav li:hover { background: var(--surface); }
.sidebar-project-nav li.active { color: var(--signal); font-weight: 600; border-left: 3px solid var(--signal); padding-left: calc(2.25rem - 3px); background: var(--surface); }
```

並把 Task 1 建立的 `.project-toolbar` 規則（單一行 `.project-toolbar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; }`）改成加上底部分隔線，取代舊的膠囊邊框按鈕視覺：

```css
.project-toolbar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; border-bottom: 1px solid var(--line); padding-bottom: 0.75rem; }
```

- [ ] **Step 11: 啟動應用程式，手動驗證**

```bash
mvn spring-boot:run
```

用 `leader`/`password123` 登入，進入 `/projects/{id}`：確認側邊欄「首頁／專案列表」下方出現專案節點（顯示專案名稱）＋三個子項，預設展開；點擊「人員派工」子項，確認主內容區切換到人員派工檢視、該子項變成 `--signal` 藍字＋左側強調線；點擊「WBS 檢視」子項確認同樣正確切換。截圖存證。離開專案回到 `/projects`，確認側邊欄的專案節點消失，只剩「首頁／專案列表」兩個連結。檢查瀏覽器 console 無 Vue Teleport 相關警告或錯誤。

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/missionboard/project/ProjectController.java \
  src/main/resources/templates/project/detail.html \
  src/main/resources/templates/fragments/sidebar.html \
  src/main/resources/static/js/project-detail.js \
  src/main/resources/static/css/app.css \
  src/test/java/com/missionboard/project/ProjectDetailPageTest.java
git commit -m "feat: 側邊欄改為含專案子導覽的樹狀結構，取代原本內容區頂端的分頁按鈕"
```

---

## Task 3: WBS 檢視新增「+ 新增大項」

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`（第 251-567 行 `KanbanView`、第 719-888 行 `WbsView`、root app template 內 `<wbs-view>` 標籤）

**Interfaces:**
- Consumes: Task 2 完成後的 `project-detail.js`（root app template 已改用 Teleport，但 `<wbs-view>` 標籤本身在 Task 2 未變動）；既有 `POST /api/projects/{id}/task-categories` 與 `GET /api/task-category-presets?type=STAGE&sectionId=xxx` 端點（皆已存在，不新增）
- Produces: 新的 `categoryPresetMixin`（`stagePresets`／`categoryPresets`／`presetsLoaded`／`presetPicker`／`loadCategoryPresets()`／`openPresetPicker(parentCategoryId)`／`closePresetPicker()`／`createCategoryFromPreset(presetId)`），供 `KanbanView` 與 `WbsView` 共用；`WbsView` 新增 `sectionId` prop

本任務是把 `KanbanView` 現有一組邏輯原封不動搬到共用 mixin（純重構，不改變任何行為），加上讓 `WbsView` 也能使用它、並新增一顆按鈕。沒有新的後端契約，不需要新的後端測試；驗證方式是手動操作確認行為與看板一致。

- [ ] **Step 1: 抽出 `categoryPresetMixin`**

在 `src/main/resources/static/js/project-detail.js` 找到 `taskBoardMixin` 定義（第 163-195 行）,在它後面、`TaskModal` 定義（第 201 行）之前，新增：

```js
// KanbanView 與 WbsView 共用：新增大類/子類的選單挑選器邏輯（從 task_category_presets 選單挑選後建立 task_categories）。
// 依賴 host 元件已混入 taskBoardMixin（提供 categories 陣列）與 toastMixin（提供 showToast）
const categoryPresetMixin = {
  data() {
    return {
      presetsLoaded: false,
      stagePresets: [],
      categoryPresets: [],
      presetPicker: null, // { parentCategoryId: null|number } 開啟中的選單挑選器；null 表示未開啟
    };
  },
  methods: {
    async loadCategoryPresets() {
      const [stageRes, categoryRes] = await Promise.all([
        api(`/api/task-category-presets?type=STAGE&sectionId=${this.sectionId}`),
        api(`/api/task-category-presets?type=CATEGORY&sectionId=${this.sectionId}`),
      ]);
      this.stagePresets = stageRes.success ? stageRes.data : [];
      this.categoryPresets = categoryRes.success ? categoryRes.data : [];
      this.presetsLoaded = true;
      if (!stageRes.success || !categoryRes.success) {
        this.showToast(stageRes.message || categoryRes.message || '選單載入失敗');
      }
    },
    async openPresetPicker(parentCategoryId) {
      this.presetPicker = { parentCategoryId };
      if (!this.presetsLoaded) {
        await this.loadCategoryPresets();
      }
    },
    closePresetPicker() {
      this.presetPicker = null;
    },
    async createCategoryFromPreset(presetId) {
      const parentCategoryId = this.presetPicker.parentCategoryId;
      this.presetPicker = null;
      const result = await api(`/api/projects/${this.projectId}/task-categories`, {
        method: 'POST',
        body: JSON.stringify({ parentCategoryId, presetId, sortOrder: null }),
      });
      if (result.success) {
        this.categories.push(result.data);
      } else {
        this.showToast(result.message || '新增分類失敗');
      }
    },
  },
};
```

- [ ] **Step 2: `KanbanView` 移除搬出去的欄位，改混入 `categoryPresetMixin`**

把 `KanbanView` 的 `mixins` 那行（原第 253 行）：

```js
mixins: [toastMixin, taskBoardMixin, taskModalMixin],
```

改成：

```js
mixins: [toastMixin, taskBoardMixin, taskModalMixin, categoryPresetMixin],
```

把 `KanbanView` 的 `data()`（原第 265-282 行）：

```js
data() {
  return {
    columns: [
      { status: 'NOT_STARTED', label: '未開始' },
      { status: 'IN_PROGRESS', label: '進行中' },
      { status: 'DONE', label: '已完成' },
    ],
    dragging: null, dragOverCol: null, dragIndex: 0,
    categoryPanelOpen: false,
    presetsLoaded: false,
    stagePresets: [],
    categoryPresets: [],
    presetPicker: null,       // { parentCategoryId: null|number } 開啟中的選單挑選器；null 表示未開啟
    editingCategoryId: null,
    categoryNameDraft: '',
    draggingCategoryId: null,
  };
},
```

改成（移除 `presetsLoaded`／`stagePresets`／`categoryPresets`／`presetPicker` 四個欄位，現在由 mixin 提供）：

```js
data() {
  return {
    columns: [
      { status: 'NOT_STARTED', label: '未開始' },
      { status: 'IN_PROGRESS', label: '進行中' },
      { status: 'DONE', label: '已完成' },
    ],
    dragging: null, dragOverCol: null, dragIndex: 0,
    categoryPanelOpen: false,
    editingCategoryId: null,
    categoryNameDraft: '',
    draggingCategoryId: null,
  };
},
```

在 `KanbanView` 的 `methods` 區塊裡，刪除原本的 `loadCategoryPresets`／`openPresetPicker`／`closePresetPicker`／`createCategoryFromPreset` 四個方法定義（原第 355-388 行一帶，現在由 mixin 提供，`methods` 裡留下的其他方法如 `deleteCategory`／`onCategoryDragStart` 等不動）。`KanbanView` 的 template 完全不需要改，因為方法名稱與資料欄位名稱都保持一致，mixin 是透明替換。

- [ ] **Step 3: `WbsView` 加入 `categoryPresetMixin` 與 `sectionId` prop**

把 `WbsView` 的 `mixins`／`props`（原第 407-412 行）：

```js
mixins: [toastMixin, taskBoardMixin, taskModalMixin],
props: {
  projectId: { type: Number, required: true },
  canWrite: { type: Boolean, default: false },
  dataVersion: { type: Number, default: 0 },
},
```

改成：

```js
mixins: [toastMixin, taskBoardMixin, taskModalMixin, categoryPresetMixin],
props: {
  projectId: { type: Number, required: true },
  canWrite: { type: Boolean, default: false },
  sectionId: { type: Number, default: null },
  dataVersion: { type: Number, default: 0 },
},
```

- [ ] **Step 4: `WbsView` template 加入「+ 新增大項」按鈕**

在 `WbsView` 的 template 裡，找到 `v-for="stage in categoryTree"` 那個 `.wbs-node` 區塊（大類節點）的結束 `</div>`，它後面緊接著 `.wbs-tree` 的結束 `</div>`。在這兩者之間插入按鈕區塊——也就是把：

```html
        <div v-for="stage in categoryTree" :key="stage.id" class="wbs-node"
             :class="{ 'drag-over': draggingTask && dragOverCategoryId === stage.id }"
             @dragover.prevent="dragOverCategoryId = stage.id" @drop="onCategoryNodeDrop(stage.id)">
          <div class="wbs-node-header" @click="toggleExpanded(stage.id)">
            <span class="wbs-node-toggle">{{ isExpanded(stage.id) ? '▾' : '▸' }}</span>
            <span class="wbs-node-name">{{ stage.name }} ({{ taskCount(stageIds(stage)) }})</span>
            <span class="wbs-node-summary">{{ completionLabel(stageIds(stage)) }}</span>
          </div>
          <div v-if="isExpanded(stage.id)" class="wbs-node-body">
            <wbs-task-row v-for="t in directTasks(stage.id)" :key="t.id" :task="t" :can-write="canWrite"
                          @dragstart="onTaskDragStart(t, $event)"
                          @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
            <div v-for="child in stage.children" :key="child.id" class="wbs-node wbs-node-child"
                 :class="{ 'drag-over': draggingTask && dragOverCategoryId === child.id }"
                 @dragover.prevent.stop="dragOverCategoryId = child.id" @drop.stop="onCategoryNodeDrop(child.id)">
              <div class="wbs-node-header" @click="toggleExpanded(child.id)">
                <span class="wbs-node-toggle">{{ isExpanded(child.id) ? '▾' : '▸' }}</span>
                <span class="wbs-node-name">{{ child.name }} ({{ taskCount([child.id]) }})</span>
                <span class="wbs-node-summary">{{ completionLabel([child.id]) }}</span>
              </div>
              <div v-if="isExpanded(child.id)" class="wbs-task-list">
                <wbs-task-row v-for="t in directTasks(child.id)" :key="t.id" :task="t" :can-write="canWrite"
                              @dragstart="onTaskDragStart(t, $event)"
                              @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
              </div>
            </div>
          </div>
        </div>
      </div>
      <task-modal v-if="modal.open" :modal="modal" :members="members" :categories="categories" :can-write="canWrite"
                  @save="saveTask" @delete="deleteTask" @close="modal.open = false" />
```

改成（在大類節點 `v-for` 結束、`.wbs-tree` 結束之前插入按鈕）：

```html
        <div v-for="stage in categoryTree" :key="stage.id" class="wbs-node"
             :class="{ 'drag-over': draggingTask && dragOverCategoryId === stage.id }"
             @dragover.prevent="dragOverCategoryId = stage.id" @drop="onCategoryNodeDrop(stage.id)">
          <div class="wbs-node-header" @click="toggleExpanded(stage.id)">
            <span class="wbs-node-toggle">{{ isExpanded(stage.id) ? '▾' : '▸' }}</span>
            <span class="wbs-node-name">{{ stage.name }} ({{ taskCount(stageIds(stage)) }})</span>
            <span class="wbs-node-summary">{{ completionLabel(stageIds(stage)) }}</span>
          </div>
          <div v-if="isExpanded(stage.id)" class="wbs-node-body">
            <wbs-task-row v-for="t in directTasks(stage.id)" :key="t.id" :task="t" :can-write="canWrite"
                          @dragstart="onTaskDragStart(t, $event)"
                          @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
            <div v-for="child in stage.children" :key="child.id" class="wbs-node wbs-node-child"
                 :class="{ 'drag-over': draggingTask && dragOverCategoryId === child.id }"
                 @dragover.prevent.stop="dragOverCategoryId = child.id" @drop.stop="onCategoryNodeDrop(child.id)">
              <div class="wbs-node-header" @click="toggleExpanded(child.id)">
                <span class="wbs-node-toggle">{{ isExpanded(child.id) ? '▾' : '▸' }}</span>
                <span class="wbs-node-name">{{ child.name }} ({{ taskCount([child.id]) }})</span>
                <span class="wbs-node-summary">{{ completionLabel([child.id]) }}</span>
              </div>
              <div v-if="isExpanded(child.id)" class="wbs-task-list">
                <wbs-task-row v-for="t in directTasks(child.id)" :key="t.id" :task="t" :can-write="canWrite"
                              @dragstart="onTaskDragStart(t, $event)"
                              @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
              </div>
            </div>
          </div>
        </div>

        <span class="preset-picker-anchor" v-if="canWrite">
          <button class="btn btn-sm" @click="openPresetPicker(null)">+ 新增大項</button>
          <div v-if="presetPicker && presetPicker.parentCategoryId === null" class="preset-picker-popover">
            <p>選擇階段選單項目</p>
            <ul>
              <li v-for="p in stagePresets" :key="p.id">
                <button class="btn btn-sm" @click="createCategoryFromPreset(p.id)">{{ p.name }}</button>
              </li>
            </ul>
            <button class="btn btn-sm" @click="closePresetPicker">取消</button>
          </div>
        </span>
      </div>
      <task-modal v-if="modal.open" :modal="modal" :members="members" :categories="categories" :can-write="canWrite"
                  @save="saveTask" @delete="deleteTask" @close="modal.open = false" />
```

按鈕沿用既有 `.btn`／`.preset-picker-anchor`／`.preset-picker-popover` 樣式，不需要新增 CSS class。WBS 檢視不做「+子類別」「改名」「刪除」「排序」，所以只複製看板 preset-picker-anchor 那段裡「新增階段」的部分，不含「+子類別」按鈕。

- [ ] **Step 5: root app template 的 `<wbs-view>` 標籤加上 `:section-id`**

`categoryPresetMixin` 的 `loadCategoryPresets()` 依賴 `this.sectionId`，但 root app template 呼叫 `<wbs-view>` 時原本沒有傳這個 prop（只有 `<kanban-view>` 有）。把（Task 2 完成後的）root app template 裡這一行：

```html
        <wbs-view v-else :project-id="projectId" :can-write="canWrite" :data-version="dataVersion" />
```

改成：

```html
        <wbs-view v-else :project-id="projectId" :can-write="canWrite" :section-id="sectionId" :data-version="dataVersion" />
```

- [ ] **Step 6: 啟動應用程式，手動驗證**

```bash
mvn spring-boot:run
```

用 `leader`/`password123` 登入既有的「MissionBoard 範例專案」，切到 WBS 檢視分頁：確認樹狀結構最下方（所有大類節點之後）出現「+ 新增大項」按鈕；點擊後確認彈出選單、列出階段選單項目（與看板「分類管理」面板「+新增階段」看到的選單內容相同）；選一項後確認樹狀結構新增一個大類節點（任務數 0、完成度 `--`）。切到看板分頁，開「分類管理」面板，確認同一個新大類也出現在那裡（因為切換分頁是 `v-if`/`v-else-if`，會重新掛載元件並重新 `loadAll()`，不需要額外的 `dataVersion` 同步）。截圖存證，檢查 console 無錯誤。

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/project-detail.js
git commit -m "feat: WBS 檢視新增「+ 新增大項」，抽出 categoryPresetMixin 與看板共用選單挑選邏輯"
```

---

## Task 4: 首頁儀表板——後端

**Files:**
- Create: `src/main/java/com/missionboard/task/DashboardDto.java`
- Create: `src/main/java/com/missionboard/task/DashboardService.java`
- Modify: `src/main/java/com/missionboard/task/TaskRepository.java`
- Modify: `src/main/java/com/missionboard/user/UserController.java`
- Test: `src/test/java/com/missionboard/task/DashboardServiceTest.java`（新檔）
- Test: `src/test/java/com/missionboard/user/UserControllerTest.java`

**Interfaces:**
- Consumes: 既有 `ProjectRepository.findByMemberUserIdAndArchived(Long, boolean)`／`findBySectionIdAndArchived(Long, boolean)`／`findByArchived(boolean)`；既有 `TaskRepository.findByProjectId(Long)`；既有 `ProjectDto.Response.from(Project)`；`User.getRole()` 回傳 `User.Role`、`User.getDepartment()` 回傳 `Department`（可為 null，但本任務只在 `SECTION_CHIEF` 分支使用，該角色種子資料保證有科別）；`Project.getSection()` 回傳 `Department`
- Produces: `DashboardService.getDashboard(User user)` → `DashboardDto.Response`；`GET /api/users/me/dashboard` 端點；`TaskRepository.findActiveByAssigneeId(Long assigneeId, Task.Status excludedStatus)` → `List<Task>`

- [ ] **Step 1: 寫 `DashboardServiceTest` 的第一個失敗測試（PERSONAL 視角）**

建立 `src/test/java/com/missionboard/task/DashboardServiceTest.java`：

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectMember;
import com.missionboard.project.ProjectMemberId;
import com.missionboard.project.ProjectMemberRepository;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class DashboardServiceTest {

    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private TaskRepository taskRepository;

    private Department sectionA;
    private Department sectionB;
    private User leaderA;
    private User memberA;
    private User chiefA;
    private User director;
    private Project projectA;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        director = userRepository.save(newUser("directorX", User.Role.DIRECTOR, sectionA));
        chiefA = userRepository.save(newUser("chiefX", User.Role.SECTION_CHIEF, sectionA));
        leaderA = userRepository.save(newUser("leaderX", User.Role.PROJECT_LEADER, sectionA));
        memberA = userRepository.save(newUser("memberX", User.Role.PROJECT_MEMBER, sectionA));

        Project p = new Project();
        p.setName("專案A");
        p.setSection(sectionA);
        p.setOwner(leaderA);
        p.setCreatedBy(leaderA);
        projectA = projectRepository.save(p);
        addMember(projectA, leaderA);
        addMember(projectA, memberA);
    }

    @Test
    void projectLeaderGetsPersonalViewWithOwnProjectsAndAssignedTasks() {
        Task assigned = newTask(projectA, "指派給我的任務", leaderA, Task.Status.NOT_STARTED, LocalDate.now().plusDays(3));
        taskRepository.save(assigned);
        Task others = newTask(projectA, "別人的任務", memberA, Task.Status.NOT_STARTED, LocalDate.now().plusDays(1));
        taskRepository.save(others);

        DashboardDto.Response response = dashboardService.getDashboard(leaderA);

        assertThat(response.viewType()).isEqualTo("PERSONAL");
        assertThat(response.activeProjects()).extracting("id").containsExactly(projectA.getId());
        assertThat(response.myTasks()).extracting("title").containsExactly("指派給我的任務");
    }

    private Task newTask(Project project, String title, User assignee, Task.Status status, LocalDate dueDate) {
        Task t = new Task();
        t.setProject(project);
        t.setTitle(title);
        t.setAssignee(assignee);
        t.setStatus(status);
        t.setDueDate(dueDate);
        return t;
    }

    private void addMember(Project project, User user) {
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), user.getId()));
        pm.setAssignedBy(user);
        projectMemberRepository.save(pm);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private User newUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("hash");
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return u;
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

```bash
mvn test -Dtest=DashboardServiceTest
```

預期編譯失敗或找不到 `DashboardService`／`DashboardDto` bean，因為這兩個類別還不存在。

- [ ] **Step 3: 建立 `DashboardDto`**

建立 `src/main/java/com/missionboard/task/DashboardDto.java`：

```java
package com.missionboard.task;

import com.missionboard.project.ProjectDto;

import java.time.LocalDate;
import java.util.List;

public class DashboardDto {

    public record Response(String viewType,
                            List<ProjectDto.Response> activeProjects, List<TaskItem> myTasks,
                            String sectionName, List<ProjectSummary> projectSummaries,
                            List<SectionSummary> sectionSummaries) {
        public static Response personal(List<ProjectDto.Response> projects, List<TaskItem> tasks) {
            return new Response("PERSONAL", projects, tasks, null, null, null);
        }

        public static Response section(String sectionName, List<ProjectSummary> summaries) {
            return new Response("SECTION", null, null, sectionName, summaries, null);
        }

        public static Response org(List<SectionSummary> summaries) {
            return new Response("ORG", null, null, null, null, summaries);
        }
    }

    public record TaskItem(Long id, Long projectId, String projectName, String title,
                            String status, LocalDate dueDate) {
    }

    public record ProjectSummary(Long projectId, String projectName,
                                  int taskCount, int overdueCount, String completionLabel) {
    }

    public record SectionSummary(Long sectionId, String sectionName,
                                  int projectCount, int overdueCount, String completionLabel) {
    }
}
```

- [ ] **Step 4: 新增 `TaskRepository.findActiveByAssigneeId`**

把 `src/main/java/com/missionboard/task/TaskRepository.java` 現有的 `clearAssigneeForUserInProject` 方法後面（`interface` 結束的 `}` 之前）新增：

```java

    // 首頁儀表板 PERSONAL 視角：跨所有專案抓「指派給我、尚未完成、專案未封存」的任務，
    // 不含已封存專案（那些已凍結不需要再關注）
    @Query("SELECT t FROM Task t WHERE t.assignee.id = :assigneeId AND t.status <> :excludedStatus "
        + "AND t.project.archived = false")
    List<Task> findActiveByAssigneeId(@Param("assigneeId") Long assigneeId, @Param("excludedStatus") Task.Status excludedStatus);
```

（`@Query`／`@Param` 已在檔案頂端 import，不需要新增 import）

- [ ] **Step 5: 建立 `DashboardService`**

建立 `src/main/java/com/missionboard/task/DashboardService.java`：

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectDto;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

    // 唯讀查詢比照 ProjectService/TaskService 既有慣例標 readOnly；ProjectDto.Response.from() 會觸發
    // section/owner 的 lazy load，沒有交易邊界時只靠 spring.jpa.open-in-view 撐住、不應依賴這個全域設定
    @Transactional(readOnly = true)
    public DashboardDto.Response getDashboard(User user) {
        return switch (user.getRole()) {
            case PROJECT_LEADER, PROJECT_MEMBER -> buildPersonalView(user);
            case SECTION_CHIEF -> buildSectionView(user);
            case DIRECTOR -> buildOrgView();
        };
    }

    // 操作視角：我是成員的進行中專案＋指派給我、尚未完成、專案未封存的任務
    private DashboardDto.Response buildPersonalView(User user) {
        List<Project> projects = projectRepository.findByMemberUserIdAndArchived(user.getId(), false);
        List<Task> tasks = taskRepository.findActiveByAssigneeId(user.getId(), Task.Status.DONE);
        List<DashboardDto.TaskItem> taskItems = tasks.stream()
            .sorted(Comparator.comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getId))
            .map(t -> new DashboardDto.TaskItem(
                t.getId(), t.getProject().getId(), t.getProject().getName(),
                t.getTitle(), t.getStatus().name(), t.getDueDate()))
            .toList();
        return DashboardDto.Response.personal(
            projects.stream().map(ProjectDto.Response::from).toList(), taskItems);
    }

    // 管理視角：本科所有未封存專案，每個專案帶任務數／逾期數／完成度（在記憶體中聚合，
    // 科內專案數量通常是個位數到十幾，不值得為此寫聚合 SQL）
    private DashboardDto.Response buildSectionView(User user) {
        List<Project> projects = projectRepository.findBySectionIdAndArchived(user.getDepartment().getId(), false);
        List<DashboardDto.ProjectSummary> summaries = projects.stream()
            .map(p -> summarize(p.getId(), p.getName(), taskRepository.findByProjectId(p.getId())))
            .toList();
        return DashboardDto.Response.section(user.getDepartment().getName(), summaries);
    }

    // 總覽視角：全公司所有未封存專案，依科別分組聚合
    private DashboardDto.Response buildOrgView() {
        List<Project> projects = projectRepository.findByArchived(false);
        Map<Department, List<Project>> bySection = projects.stream()
            .collect(Collectors.groupingBy(Project::getSection));
        List<DashboardDto.SectionSummary> summaries = bySection.entrySet().stream()
            .map(entry -> {
                List<Task> sectionTasks = entry.getValue().stream()
                    .flatMap(p -> taskRepository.findByProjectId(p.getId()).stream())
                    .toList();
                DashboardDto.ProjectSummary agg = summarize(null, null, sectionTasks);
                return new DashboardDto.SectionSummary(
                    entry.getKey().getId(), entry.getKey().getName(),
                    entry.getValue().size(), agg.overdueCount(), agg.completionLabel());
            })
            .toList();
        return DashboardDto.Response.org(summaries);
    }

    private DashboardDto.ProjectSummary summarize(Long projectId, String projectName, List<Task> tasks) {
        int total = tasks.size();
        int done = (int) tasks.stream().filter(t -> t.getStatus() == Task.Status.DONE).count();
        int overdue = (int) tasks.stream().filter(this::isOverdue).count();
        String completionLabel = total == 0 ? "--" : Math.round(done * 100.0 / total) + "%";
        return new DashboardDto.ProjectSummary(projectId, projectName, total, overdue, completionLabel);
    }

    private boolean isOverdue(Task t) {
        return t.getDueDate() != null && t.getStatus() != Task.Status.DONE
            && t.getDueDate().isBefore(LocalDate.now());
    }
}
```

- [ ] **Step 6: 執行測試確認第一個測試通過**

```bash
mvn test -Dtest=DashboardServiceTest
```

- [ ] **Step 7: 補上 SECTION／ORG 視角與邊界案例測試**

在同一個 `DashboardServiceTest.java` 裡，`projectLeaderGetsPersonalViewWithOwnProjectsAndAssignedTasks` 測試方法後面，依序新增以下測試方法（都放在 helper 方法之前）：

```java
    @Test
    void personalViewExcludesDoneTasksAndArchivedProjectTasks() {
        Task done = newTask(projectA, "已完成任務", memberA, Task.Status.DONE, null);
        taskRepository.save(done);

        Project archived = new Project();
        archived.setName("已封存專案");
        archived.setSection(sectionA);
        archived.setOwner(leaderA);
        archived.setCreatedBy(leaderA);
        archived.setArchived(true);
        archived = projectRepository.save(archived);
        addMember(archived, memberA);
        Task fromArchived = newTask(archived, "封存專案任務", memberA, Task.Status.NOT_STARTED, LocalDate.now());
        taskRepository.save(fromArchived);

        DashboardDto.Response response = dashboardService.getDashboard(memberA);

        assertThat(response.myTasks()).isEmpty();
        assertThat(response.activeProjects()).extracting("id").containsExactly(projectA.getId());
    }

    @Test
    void personalViewSortsTasksByDueDateWithNullsLast() {
        Task noDueDate = newTask(projectA, "無到期日", memberA, Task.Status.NOT_STARTED, null);
        taskRepository.save(noDueDate);
        Task earlier = newTask(projectA, "較早到期", memberA, Task.Status.NOT_STARTED, LocalDate.now().plusDays(1));
        taskRepository.save(earlier);
        Task later = newTask(projectA, "較晚到期", memberA, Task.Status.NOT_STARTED, LocalDate.now().plusDays(5));
        taskRepository.save(later);

        DashboardDto.Response response = dashboardService.getDashboard(memberA);

        assertThat(response.myTasks()).extracting("title")
            .containsExactly("較早到期", "較晚到期", "無到期日");
    }

    @Test
    void sectionChiefGetsSectionViewWithAllSectionProjectsRegardlessOfMembership() {
        Task overdueTask = newTask(projectA, "逾期任務", memberA, Task.Status.NOT_STARTED, LocalDate.now().minusDays(1));
        taskRepository.save(overdueTask);
        Task doneTask = newTask(projectA, "完成任務", memberA, Task.Status.DONE, LocalDate.now().plusDays(1));
        taskRepository.save(doneTask);

        Project projectB = new Project();
        projectB.setName("科長不是成員的專案");
        projectB.setSection(sectionA);
        projectB.setOwner(leaderA);
        projectB.setCreatedBy(leaderA);
        projectRepository.save(projectB);

        DashboardDto.Response response = dashboardService.getDashboard(chiefA);

        assertThat(response.viewType()).isEqualTo("SECTION");
        assertThat(response.sectionName()).isEqualTo("系統科");
        assertThat(response.projectSummaries()).hasSize(2);
        DashboardDto.ProjectSummary summaryA = response.projectSummaries().stream()
            .filter(s -> s.projectId().equals(projectA.getId())).findFirst().orElseThrow();
        assertThat(summaryA.taskCount()).isEqualTo(2);
        assertThat(summaryA.overdueCount()).isEqualTo(1);
        assertThat(summaryA.completionLabel()).isEqualTo("50%");
    }

    @Test
    void sectionViewExcludesOtherSectionsAndArchivedProjects() {
        Project projectB = new Project();
        projectB.setName("網路科專案");
        projectB.setSection(sectionB);
        projectB.setOwner(leaderA);
        projectB.setCreatedBy(leaderA);
        projectRepository.save(projectB);

        DashboardDto.Response response = dashboardService.getDashboard(chiefA);

        assertThat(response.projectSummaries()).extracting("projectId").containsExactly(projectA.getId());
    }

    @Test
    void directorGetsOrgViewGroupedBySection() {
        Task task = newTask(projectA, "系統科任務", memberA, Task.Status.NOT_STARTED, LocalDate.now().minusDays(1));
        taskRepository.save(task);

        Project projectB = new Project();
        projectB.setName("網路科專案");
        projectB.setSection(sectionB);
        projectB.setOwner(leaderA);
        projectB.setCreatedBy(leaderA);
        projectRepository.save(projectB);

        DashboardDto.Response response = dashboardService.getDashboard(director);

        assertThat(response.viewType()).isEqualTo("ORG");
        assertThat(response.sectionSummaries()).hasSize(2);
        DashboardDto.SectionSummary sectionASummary = response.sectionSummaries().stream()
            .filter(s -> s.sectionId().equals(sectionA.getId())).findFirst().orElseThrow();
        assertThat(sectionASummary.projectCount()).isEqualTo(1);
        assertThat(sectionASummary.overdueCount()).isEqualTo(1);
    }

    @Test
    void orgViewExcludesArchivedProjects() {
        Project archived = new Project();
        archived.setName("已封存專案");
        archived.setSection(sectionB);
        archived.setOwner(leaderA);
        archived.setCreatedBy(leaderA);
        archived.setArchived(true);
        projectRepository.save(archived);

        DashboardDto.Response response = dashboardService.getDashboard(director);

        assertThat(response.sectionSummaries()).extracting("sectionId").containsExactly(sectionA.getId());
    }

    @Test
    void emptyResultsReturnEmptyListsNotNullAndCompletionLabelIsDashWhenNoTasks() {
        Project emptyProject = new Project();
        emptyProject.setName("無任務專案");
        emptyProject.setSection(sectionA);
        emptyProject.setOwner(leaderA);
        emptyProject.setCreatedBy(leaderA);
        projectRepository.save(emptyProject);

        DashboardDto.Response personalResponse = dashboardService.getDashboard(memberA);
        assertThat(personalResponse.myTasks()).isNotNull().isEmpty();

        DashboardDto.Response sectionResponse = dashboardService.getDashboard(chiefA);
        DashboardDto.ProjectSummary emptySummary = sectionResponse.projectSummaries().stream()
            .filter(s -> s.projectId().equals(emptyProject.getId())).findFirst().orElseThrow();
        assertThat(emptySummary.completionLabel()).isEqualTo("--");
    }
```

- [ ] **Step 8: 執行測試確認全部通過**

```bash
mvn test -Dtest=DashboardServiceTest
```

- [ ] **Step 9: `UserController` 新增 `GET /api/users/me/dashboard`**

把 `src/main/java/com/missionboard/user/UserController.java` 整檔改成：

```java
package com.missionboard.user;

import com.missionboard.common.ApiResponse;
import com.missionboard.task.DashboardDto;
import com.missionboard.task.DashboardService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final DashboardService dashboardService;

    @GetMapping("/api/users/me")
    public ApiResponse<UserSummary> me(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        return ApiResponse.ok(UserSummary.from(user));
    }

    @GetMapping("/api/users/me/dashboard")
    public ApiResponse<DashboardDto.Response> dashboard(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        return ApiResponse.ok(dashboardService.getDashboard(user));
    }

    // 供成員新增等下拉選單使用；帶 departmentId 只回同科人員，不帶則回全部
    @GetMapping("/api/users")
    public ApiResponse<List<UserSummary>> list(@RequestParam(required = false) Long departmentId) {
        List<User> users = departmentId != null
            ? userRepository.findByDepartmentId(departmentId)
            : userRepository.findAll();
        return ApiResponse.ok(users.stream().map(UserSummary::from).toList());
    }

    public record UserSummary(Long id, String username, String displayName, String role) {
        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name());
        }
    }
}
```

- [ ] **Step 10: 更新 `UserControllerTest`——新增 director 帳號、修正既有斷言、新增 dashboard 測試**

`UserControllerTest.java` 現有 `setUp()` 只建立 `leader`／`chief`／`memberB` 三個使用者，`listReturnsAllUsersWithoutFilter` 測試斷言 `$.data.length()` 為 `3`。本步驟新增 `director` 帳號後，該斷言的總數會變成 4，必須一併修正，否則會破壞既有測試。`director` 要建在 `sectionB`（不是 `sectionA`）——`listFiltersByDepartmentId` 測試斷言 `sectionA` 篩選結果恰好是 `leader`／`chief` 兩人（`containsInAnyOrder("leader", "chief")`），把 `director` 放進 `sectionA` 會讓這個既有斷言連帶失敗；放 `sectionB` 則兩個既有測試只需改前者的總數。

把 `setUp()` 方法：

```java
@BeforeEach
void setUp() {
    sectionA = departmentRepository.save(newDept("系統科"));
    sectionB = departmentRepository.save(newDept("網路科"));

    saveUser("leader", "負責人", User.Role.PROJECT_LEADER, sectionA);
    saveUser("chief", "科長", User.Role.SECTION_CHIEF, sectionA);
    saveUser("memberB", "另科成員", User.Role.PROJECT_MEMBER, sectionB);
}
```

改成：

```java
@BeforeEach
void setUp() {
    sectionA = departmentRepository.save(newDept("系統科"));
    sectionB = departmentRepository.save(newDept("網路科"));

    saveUser("leader", "負責人", User.Role.PROJECT_LEADER, sectionA);
    saveUser("chief", "科長", User.Role.SECTION_CHIEF, sectionA);
    saveUser("memberB", "另科成員", User.Role.PROJECT_MEMBER, sectionB);
    saveUser("director", "主任", User.Role.DIRECTOR, sectionB);
}
```

把 `listReturnsAllUsersWithoutFilter` 測試方法：

```java
@Test
void listReturnsAllUsersWithoutFilter() throws Exception {
    Cookie session = loginAs("chief");

    mockMvc.perform(get("/api/users").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3));
}
```

改成（總數 `3` 改為 `4`）：

```java
@Test
void listReturnsAllUsersWithoutFilter() throws Exception {
    Cookie session = loginAs("chief");

    mockMvc.perform(get("/api/users").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(4));
}
```

在 `listFiltersByDepartmentId` 測試方法（檔案最後一個測試）後面新增：

```java

    @Test
    void dashboardRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me/dashboard"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void dashboardReturnsPersonalViewTypeForProjectLeader() throws Exception {
        Cookie session = loginAs("leader");

        mockMvc.perform(get("/api/users/me/dashboard").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.viewType").value("PERSONAL"));
    }

    @Test
    void dashboardReturnsSectionViewTypeForSectionChief() throws Exception {
        Cookie session = loginAs("chief");

        mockMvc.perform(get("/api/users/me/dashboard").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewType").value("SECTION"));
    }

    @Test
    void dashboardReturnsOrgViewTypeForDirector() throws Exception {
        Cookie session = loginAs("director");

        mockMvc.perform(get("/api/users/me/dashboard").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewType").value("ORG"));
    }
```

- [ ] **Step 11: 執行完整測試套件確認通過**

```bash
mvn test -Dtest=UserControllerTest
mvn test -Dtest=DashboardServiceTest
```

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/missionboard/task/DashboardDto.java \
  src/main/java/com/missionboard/task/DashboardService.java \
  src/main/java/com/missionboard/task/TaskRepository.java \
  src/main/java/com/missionboard/user/UserController.java \
  src/test/java/com/missionboard/task/DashboardServiceTest.java \
  src/test/java/com/missionboard/user/UserControllerTest.java
git commit -m "feat: 新增 GET /api/users/me/dashboard，依角色回傳 PERSONAL/SECTION/ORG 三種儀表板內容"
```

---

## Task 5: 首頁儀表板——前端

**Files:**
- Create: `src/main/resources/static/js/home.js`
- Modify: `src/main/resources/templates/home.html`（全檔 19 行）
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: Task 4 的 `GET /api/users/me/dashboard`，回應格式 `{ success, message, data: { viewType, activeProjects, myTasks, sectionName, projectSummaries, sectionSummaries } }`
- Produces: `#home-app` 掛載點渲染依 `viewType` 分流的三種畫面

本任務無新後端契約，純前端渲染，無自動化測試，驗證方式是啟動應用程式後用四個測試帳號實際登入檢查畫面。

- [ ] **Step 1: `home.html` 新增 Vue 掛載點與 `home.js` script 標籤**

把 `src/main/resources/templates/home.html` 整檔改成：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <title>首頁 - MissionBoard 任務管理系統</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<div th:replace="~{fragments/header :: header}"></div>
<div class="layout">
  <div th:replace="~{fragments/sidebar :: sidebar}"></div>
  <main class="main-content">
    <h1 th:text="'歡迎，' + ${displayName}"></h1>
    <div id="home-app">
      <p>載入中...</p>
    </div>
  </main>
</div>
<div th:replace="~{fragments/footer :: footer}"></div>
<script th:src="@{/js/vue.global.prod.min.js}"></script>
<script th:src="@{/js/home.js}"></script>
</body>
</html>
```

`AuthController.home()` 已經傳 `displayName` 給 model，這段不需要修改後端。

- [ ] **Step 2: 建立 `home.js`**

建立 `src/main/resources/static/js/home.js`：

```js
(function () {
  const { createApp } = Vue;

  async function api(url, options = {}) {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
    try {
      const headers = { [csrfHeader]: csrfToken };
      if (options.body) headers['Content-Type'] = 'application/json';
      const res = await fetch(url, { ...options, headers: { ...headers, ...(options.headers || {}) } });
      return await res.json();
    } catch (e) {
      return { success: false, message: '網路錯誤，請稍後再試' };
    }
  }

  // 已完成的任務不再警示逾期，避免歷史卡片一片紅；用本地日期字串比對，避免 toISOString 的 UTC 誤差
  function isOverdueDate(dueDate, status) {
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    return !!dueDate && status !== 'DONE' && dueDate < today;
  }

  const STATUS_LABELS = { NOT_STARTED: '未開始', IN_PROGRESS: '進行中', DONE: '已完成' };

  const app = createApp({
    data() {
      return {
        loading: true,
        errorMessage: '',
        viewType: null,
        activeProjects: [],
        myTasks: [],
        sectionName: '',
        projectSummaries: [],
        sectionSummaries: [],
      };
    },
    methods: {
      isOverdue(task) {
        return isOverdueDate(task.dueDate, task.status);
      },
      statusLabel(status) {
        return STATUS_LABELS[status];
      },
      async loadDashboard() {
        this.loading = true;
        const result = await api('/api/users/me/dashboard');
        if (result.success) {
          this.viewType = result.data.viewType;
          this.activeProjects = result.data.activeProjects || [];
          this.myTasks = result.data.myTasks || [];
          this.sectionName = result.data.sectionName || '';
          this.projectSummaries = result.data.projectSummaries || [];
          this.sectionSummaries = result.data.sectionSummaries || [];
          this.errorMessage = '';
        } else {
          this.errorMessage = result.message || '載入失敗，請重新整理';
        }
        this.loading = false;
      },
      goToProject(projectId) {
        window.location.href = '/projects/' + projectId;
      },
    },
    mounted() {
      this.loadDashboard();
    },
    template: `
      <div>
        <p v-if="loading">載入中...</p>
        <p v-else-if="errorMessage" class="alert alert-error">{{ errorMessage }}</p>
        <template v-else-if="viewType === 'PERSONAL'">
          <div class="dashboard-section">
            <h2>進行中的專案</h2>
            <p v-if="!activeProjects.length" style="color:var(--ink-muted)">目前沒有進行中的專案</p>
            <div v-else class="project-grid">
              <a v-for="p in activeProjects" :key="p.id" class="project-card" :href="'/projects/' + p.id">
                <div class="project-card-name">{{ p.name }}</div>
                <div class="project-card-meta">{{ p.sectionName }} · 負責人：{{ p.ownerDisplayName }}</div>
              </a>
            </div>
          </div>
          <div class="dashboard-section">
            <h2>指派給我的任務</h2>
            <p v-if="!myTasks.length" style="color:var(--ink-muted)">目前沒有指派給你的任務</p>
            <div v-else>
              <div v-for="t in myTasks" :key="t.id" class="dashboard-task-row">
                <span>{{ t.title }}</span>
                <span class="dashboard-task-meta" :class="{ overdue: isOverdue(t) }">
                  <span>{{ t.projectName }}</span>
                  <span>{{ statusLabel(t.status) }}</span>
                  <span>{{ t.dueDate || '--' }}</span>
                </span>
              </div>
            </div>
          </div>
        </template>
        <template v-else-if="viewType === 'SECTION'">
          <div class="dashboard-section">
            <h2>{{ sectionName }} 進行中專案總覽</h2>
            <p v-if="!projectSummaries.length" style="color:var(--ink-muted)">本科目前沒有進行中的專案</p>
            <div v-else>
              <div v-for="p in projectSummaries" :key="p.projectId" class="dashboard-summary-row clickable" @click="goToProject(p.projectId)">
                <span class="dashboard-summary-name">{{ p.projectName }}</span>
                <span class="dashboard-summary-stats" :class="{ 'has-overdue': p.overdueCount > 0 }">
                  {{ p.taskCount }} 個任務 · {{ p.overdueCount }} 逾期 · {{ p.completionLabel }} 完成
                </span>
              </div>
            </div>
          </div>
        </template>
        <template v-else-if="viewType === 'ORG'">
          <div class="dashboard-section">
            <h2>全公司進行中專案總覽</h2>
            <p v-if="!sectionSummaries.length" style="color:var(--ink-muted)">目前沒有進行中的專案</p>
            <div v-else>
              <div v-for="s in sectionSummaries" :key="s.sectionId" class="dashboard-summary-row">
                <span class="dashboard-summary-name">{{ s.sectionName }}</span>
                <span class="dashboard-summary-stats" :class="{ 'has-overdue': s.overdueCount > 0 }">
                  {{ s.projectCount }} 個專案 · {{ s.overdueCount }} 逾期 · {{ s.completionLabel }} 完成
                </span>
              </div>
            </div>
          </div>
        </template>
      </div>
    `,
  });

  app.mount('#home-app');
})();
```

- [ ] **Step 3: 新增儀表板 CSS**

在 `src/main/resources/static/css/app.css` 檔尾新增：

```css
.dashboard-section { margin-bottom: 2rem; }
.dashboard-section h2 { font-size: 1rem; font-weight: 600; margin-bottom: 0.75rem; color: var(--ink); }
.dashboard-task-row { display: flex; justify-content: space-between; align-items: center; padding: 0.75rem 1rem; border: 1px solid var(--line); border-radius: 4px; margin-bottom: 0.5rem; font-size: 0.9rem; }
.dashboard-task-meta { display: flex; gap: 1rem; align-items: center; font-family: var(--font-mono); font-size: 0.82rem; color: var(--ink-muted); }
.dashboard-task-meta.overdue { color: var(--danger); font-weight: 600; }
.dashboard-summary-row { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1.1rem; border: 1px solid var(--line); border-radius: 4px; margin-bottom: 0.5rem; }
.dashboard-summary-row.clickable { cursor: pointer; }
.dashboard-summary-row.clickable:hover { background: var(--surface); }
.dashboard-summary-name { font-size: 0.95rem; font-weight: 600; color: var(--ink); }
.dashboard-summary-stats { font-family: var(--font-mono); font-size: 0.82rem; color: var(--ink-muted); text-align: right; }
.dashboard-summary-stats.has-overdue { color: var(--danger); }
```

- [ ] **Step 4: 啟動應用程式，用四個帳號逐一手動驗證**

```bash
docker compose up -d
mvn spring-boot:run
```

依序用 `leader`／`chief`／`director`／`member2`（密碼皆 `password123`）登入，每個都先進 `/home`：

- `leader`：確認 `viewType` 為 `PERSONAL`，看到「進行中的專案」卡片與「指派給我的任務」清單，逾期任務日期顯示為紅字粗體
- `chief`：確認 `viewType` 為 `SECTION`，看到本科（系統科）進行中專案總覽彙總列，每列可點擊且 hover 有底色變化，點擊後導向對應專案詳情頁
- `director`：確認 `viewType` 為 `ORG`，看到跨科彙總列（依科別分組），滑鼠移到列上確認**不是** `clickable` 樣式（不可點擊，無 hover 效果）
- `member2`：確認 `viewType` 為 `PERSONAL`，且因為 `member2` 種子資料裡不屬於任何專案、也沒有指派任務，兩個區塊都顯示對應空狀態文字（「目前沒有進行中的專案」／「目前沒有指派給你的任務」）

截圖涵蓋三種 `viewType`（`leader`／`chief`／`director` 各一張，共 3 張，符合每輪 ≤3 張的規則）；`member2` 的空狀態文字用文字回報描述即可,不必額外截圖。檢查每個畫面的瀏覽器 console 均無錯誤。

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/home.js \
  src/main/resources/templates/home.html \
  src/main/resources/static/css/app.css
git commit -m "feat: 首頁改為依角色分三層的儀表板（PERSONAL/SECTION/ORG）"
```

---

## 全域驗證清單（Task 5 完成後，宣稱整體完成前）

- [ ] `mvn test` 全數通過（含本計畫新增的 `DashboardServiceTest`、修改過的 `UserControllerTest`／`ProjectDetailPageTest`）
- [ ] `docker compose up -d` 成功，`mvn spring-boot:run` 啟動成功
- [ ] 依 `docs/user-guide.md` 與 `CLAUDE.md` 既有的角色測試帳號表，五個帳號（`director`／`chief`／`leader`／`member`／`member2`）逐一登入，確認：登入頁、首頁儀表板、專案列表頁、專案詳情頁（三分頁＋側邊欄導覽＋WBS 新增大項）視覺與互動皆正常
- [ ] console 無錯誤、版面無異常
- [ ] 依專案 CLAUDE.md「有畫面就有截圖」規則，重要畫面截圖存證
- [ ] 提醒使用者可執行 `/sync-docs` 同步 `docs/dev.md` 與 `README.md`（僅提醒，不自動執行）
