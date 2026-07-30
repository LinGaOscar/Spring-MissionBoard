# 專案詳情頁外殼＋樹編輯器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓使用者登入後能在瀏覽器操作 WBS 節點——新增專案詳情頁（`/projects/{id}`），內含四個 tab 的外殼，樹編輯器 tab 完整可用（CRUD、狀態、指派、優先度、日期、同層排序、L1 骨架初始化），其餘三個 tab 先放版位文字。

**Architecture:** Spring MVC 頁面路由回傳 Thymeleaf 樣板（沿用 `project/list.html` 的 header/sidebar/footer 外殼），單一 Vue 3（`vue.global.prod.min.js`，無 build 工具）app 掛載在頁面根 div，進頁一次 `GET .../nodes` 抓回扁平節點清單存進共用響應式陣列，四個 tab 元件共用同一份資料、用 `v-show` 切換不重新掛載。所有寫入呼叫既有 REST 端點（`WbsNodeController`），欄位級編輯（狀態/標題/指派/優先度/日期）走樂觀更新＋失敗回滾，結構性操作（新增/刪除/排序/初始化）呼叫後直接 `loadAll()` 重新拉取，不手動兜父子關係。

**Tech Stack:** Spring Boot 3.4 Controller + Thymeleaf、Vue 3 全域建置（CDN-less vendor 檔案，含 template 執行期編譯器）、原生 `fetch`、既有 `ApiResponse` 信封、JUnit + MockMvc（僅涵蓋新頁面路由，前端無測試框架，走實機瀏覽器驗證）。

## Global Constraints

- 不做拖拉互動，同層排序用上移/下移按鈕（design §4，已核准）
- 這輪只做樹編輯器，看板／人員派工／甘特三個 tab 只放版位文字，之後個別設計（design §7）
- L1/L2 節點的新增走選單項目（`presetId`），只有 L3 用自由標題（`WbsNodeService.createNode` 既有規則，見設計文件 §4）
- 指派人下拉只能選專案成員（`GET .../members` 的結果），不可選任意使用者
- `canWrite === false` 時隱藏所有寫入控制項，只留唯讀顯示（design §4「權限」）
- 前端沒有 JS 測試框架，驗證一律用 chrome-devtools 實機操作＋截圖（CLAUDE.md 既定驗證紀律）
- CSRF：所有非 GET fetch 都要帶 `X-CSRF-TOKEN`（實際 header 名稱來自 `_csrf_header` meta tag），否則 Spring Security 預設會擋掉寫入請求

---

## Task 1: 專案詳情頁路由＋最小樣板＋頁面測試

**Files:**
- Modify: `src/main/java/com/wbsflow/project/ProjectController.java`
- Create: `src/main/resources/templates/project/detail.html`
- Create: `src/test/java/com/wbsflow/project/ProjectDetailPageTest.java`

**Interfaces:**
- Produces: `GET /projects/{id}` 頁面路由，回傳 view `project/detail`，Model 帶 `projectId`（Long）、`canWrite`（boolean）。無讀權限時 302 redirect 到 `/projects`。

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.project;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectDetailPageTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;

    @BeforeEach
    void setUp() {
        Department sectionA = departmentRepository.save(newDept("系統科"));
        Department sectionB = departmentRepository.save(newDept("網路科"));

        User leader = saveUser("leaderX", User.Role.PROJECT_LEADER, sectionA);
        saveUser("memberY", User.Role.PROJECT_MEMBER, sectionB);

        Project p = new Project();
        p.setName("樹編輯器測試專案");
        p.setSection(sectionA);
        p.setOwner(leader);
        p.setCreatedBy(leader);
        project = projectRepository.save(p);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), leader.getId()));
        pm.setAssignedBy(leader);
        projectMemberRepository.save(pm);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private User saveUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void detailPageRendersForMemberWithReadAccess() throws Exception {
        Cookie session = loginAs("leaderX");

        mockMvc.perform(get("/projects/" + project.getId()).cookie(session))
            .andExpect(status().isOk())
            .andExpect(view().name("project/detail"));
    }

    @Test
    void detailPageRedirectsForUserWithoutReadAccess() throws Exception {
        Cookie session = loginAs("memberY");

        mockMvc.perform(get("/projects/" + project.getId()).cookie(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/projects"));
    }

    @Test
    void unauthenticatedAccessRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId()))
            .andExpect(status().is3xxRedirection());
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectDetailPageTest`
Expected: FAIL（404，因為 `/projects/{id}` 路由還不存在）

- [ ] **Step 3: 在 `ProjectController` 新增頁面路由**

在 `ProjectController` 的 `projectsPage()` 方法後面新增：

```java
@GetMapping("/projects/{id}")
public String detail(@PathVariable Long id, org.springframework.ui.Model model, Principal principal) {
    User user = currentUser(principal);
    if (!projectService.canRead(id, user)) {
        return "redirect:/projects";
    }
    model.addAttribute("projectId", id);
    model.addAttribute("canWrite", projectService.canWrite(id, user));
    return "project/detail";
}
```

- [ ] **Step 4: 建立最小樣板**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <title>專案詳情 - WBS 管理系統</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<div th:replace="~{fragments/header :: header}"></div>
<div class="layout">
  <div th:replace="~{fragments/sidebar :: sidebar}"></div>
  <main class="main-content">
    <div id="detail-app" th:data-project-id="${projectId}" th:data-can-write="${canWrite}">
      <p>載入中...</p>
    </div>
  </main>
</div>
<div th:replace="~{fragments/footer :: footer}"></div>
</body>
</html>
```

- [ ] **Step 5: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectDetailPageTest`
Expected: PASS（3 個測試全過）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wbsflow/project/ProjectController.java src/main/resources/templates/project/detail.html src/test/java/com/wbsflow/project/ProjectDetailPageTest.java
git commit -m "feat: 新增專案詳情頁路由"
```

---

## Task 2: CSRF meta tag ＋ tab 外殼骨架 ＋ 共用 CSS

**Files:**
- Modify: `src/main/resources/templates/fragments/header.html`
- Modify: `src/main/resources/templates/project/detail.html`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Produces: `<meta name="_csrf">` / `<meta name="_csrf_header">`（供 Task 3 的 JS 讀取）；`#detail-app` 內含四個 tab 按鈕（`data-tab="tree|kanban|assignment|gantt"` 不需要，直接由 Vue 接管，這裡只放掛載容器與 script 標籤）

- [ ] **Step 1: header fragment 加上 CSRF meta tag**

在 `fragments/header.html` 的 `<header th:fragment="header">` 內、`<nav>` 之前新增：

```html
<meta name="_csrf" th:content="${_csrf.token}"/>
<meta name="_csrf_header" th:content="${_csrf.headerName}"/>
```

- [ ] **Step 2: detail.html 補上 Vue vendor 與樣式**

`<head>` 內 `link` 之後新增（頁面需要 Vue，其他頁面不需要，故只在此頁引入）：

```html
<script th:src="@{/js/vue.global.prod.min.js}"></script>
```

`</body>` 前新增（放在 footer 之後）：

```html
<script th:src="@{/js/project-detail.js}"></script>
```

- [ ] **Step 3: 補上共用 CSS class**

在 `app.css` 末尾新增：

```css
.tabs { display:flex; gap:0.5rem; margin-bottom:1.5rem; }
.tabs .btn { border-radius:4px 4px 0 0; }
.toast { position:fixed; top:70px; right:1.5rem; background:#2d3436; color:#fff; padding:0.75rem 1.25rem; border-radius:6px; box-shadow:0 4px 16px rgba(0,0,0,0.2); z-index:2000; font-size:0.9rem; }
.placeholder-tab { color:#636e72; padding:2rem 0; text-align:center; }
```

- [ ] **Step 4: 手動驗證樣板可渲染（尚無 JS 檔，預期看到「載入中...」與 404 console 錯誤）**

Run: `mvn spring-boot:run`（背景執行），瀏覽器登入後訪問 `/projects/{任一既有專案id}`
Expected: 頁面顯示 header/sidebar/footer 正常，主內容顯示「載入中...」；瀏覽器 console 會有 `project-detail.js` 404（Task 3 才建立此檔，屬預期中）

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/fragments/header.html src/main/resources/templates/project/detail.html src/main/resources/static/css/app.css
git commit -m "feat: 專案詳情頁加上 CSRF meta tag 與 Vue 掛載骨架"
```

---

## Task 3: project-detail.js 基礎架構（api/loadAll/toast/tab 切換＋三個空殼元件）

**Files:**
- Create: `src/main/resources/static/js/project-detail.js`

**Interfaces:**
- Consumes: `GET /api/projects/{projectId}/nodes`、`GET /api/projects/{projectId}/members`（既有端點，回傳 `ApiResponse` 信封）
- Produces（供 Task 4-9 使用，之後任務只會新增/修改此檔內容，不改變這些名稱）：
  - 模組層函式 `api(url, options)`：回傳 `Promise<ApiResponse>`
  - 全域註冊元件：`tree-editor-view`、`kanban-view`、`assignment-view`、`gantt-view`
  - Root app `data`：`projectId, canWrite, nodes, members, activeTab, toastMessage, toastTimer`
  - Root app `methods`：`loadAll()`、`showToast(message)`（Task 5-9 會陸續新增 `cycleStatus/updateTitle/updateAssignee/updatePriority/updateDates/createNode/deleteNode/moveNode/initStages`，本任務先不實作這些，`tree-editor-view` 先不 emit 對應事件）

- [ ] **Step 1: 建立檔案骨架**

```js
(function () {
  const { createApp, defineComponent } = Vue;

  const el = document.getElementById('detail-app');
  const projectId = Number(el.dataset.projectId);
  const canWrite = el.dataset.canWrite === 'true';
  const csrfToken = document.querySelector('meta[name="_csrf"]').content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

  const STATUS_LABEL = { NOT_STARTED: '未開始', IN_PROGRESS: '進行中', DONE: '已完成' };
  const STATUS_CYCLE = { NOT_STARTED: 'IN_PROGRESS', IN_PROGRESS: 'DONE', DONE: 'NOT_STARTED' };
  const PRIORITY_LABEL = { HIGH: '高', MEDIUM: '中', LOW: '低' };

  async function api(url, options = {}) {
    const headers = { [csrfHeader]: csrfToken };
    if (options.body) headers['Content-Type'] = 'application/json';
    const res = await fetch(url, { ...options, headers: { ...headers, ...(options.headers || {}) } });
    return res.json();
  }

  const KanbanView = defineComponent({
    name: 'KanbanView',
    props: { nodes: { type: Array, default: () => [] }, canWrite: { type: Boolean, default: false } },
    template: `<p class="placeholder-tab">看板檢視開發中</p>`,
  });

  const AssignmentView = defineComponent({
    name: 'AssignmentView',
    props: { nodes: { type: Array, default: () => [] }, canWrite: { type: Boolean, default: false } },
    template: `<p class="placeholder-tab">人員派工檢視開發中</p>`,
  });

  const GanttView = defineComponent({
    name: 'GanttView',
    props: { nodes: { type: Array, default: () => [] }, canWrite: { type: Boolean, default: false } },
    template: `<p class="placeholder-tab">甘特檢視開發中</p>`,
  });

  const TreeEditorView = defineComponent({
    name: 'TreeEditorView',
    props: { nodes: { type: Array, default: () => [] }, members: { type: Array, default: () => [] }, canWrite: { type: Boolean, default: false } },
    template: `<p class="placeholder-tab">樹編輯器開發中（Task 4 起會實作）</p>`,
  });

  const app = createApp({
    data() {
      return {
        projectId, canWrite,
        nodes: [], members: [],
        activeTab: 'tree',
        toastMessage: '', toastTimer: null,
      };
    },
    methods: {
      async loadAll() {
        const [nodesRes, membersRes] = await Promise.all([
          api(`/api/projects/${this.projectId}/nodes`),
          api(`/api/projects/${this.projectId}/members`),
        ]);
        this.nodes = nodesRes.success ? nodesRes.data : [];
        this.members = membersRes.success ? membersRes.data : [];
      },
      showToast(message) {
        this.toastMessage = message;
        clearTimeout(this.toastTimer);
        this.toastTimer = setTimeout(() => { this.toastMessage = ''; }, 3000);
      },
    },
    mounted() {
      this.loadAll();
    },
    template: `
      <div>
        <div class="tabs">
          <button class="btn" :class="{ 'btn-primary': activeTab==='tree' }" @click="activeTab='tree'">樹編輯器</button>
          <button class="btn" :class="{ 'btn-primary': activeTab==='kanban' }" @click="activeTab='kanban'">看板</button>
          <button class="btn" :class="{ 'btn-primary': activeTab==='assignment' }" @click="activeTab='assignment'">人員派工</button>
          <button class="btn" :class="{ 'btn-primary': activeTab==='gantt' }" @click="activeTab='gantt'">甘特</button>
        </div>
        <div v-show="activeTab==='tree'">
          <tree-editor-view :nodes="nodes" :members="members" :can-write="canWrite" />
        </div>
        <div v-show="activeTab==='kanban'"><kanban-view :nodes="nodes" :can-write="canWrite" /></div>
        <div v-show="activeTab==='assignment'"><assignment-view :nodes="nodes" :can-write="canWrite" /></div>
        <div v-show="activeTab==='gantt'"><gantt-view :nodes="nodes" :can-write="canWrite" /></div>
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

  app.component('tree-editor-view', TreeEditorView);
  app.component('kanban-view', KanbanView);
  app.component('assignment-view', AssignmentView);
  app.component('gantt-view', GanttView);
  app.mount('#detail-app');
})();
```

- [ ] **Step 2: 手動驗證 tab 切換與資料載入**

啟動應用（`docker compose up -d` 確認 DB 在跑、`mvn spring-boot:run`），瀏覽器登入 `leader` 帳號，訪問任一該使用者可讀的專案詳情頁。
Expected：四個 tab 按鈕可點擊切換，切到「樹編輯器」顯示「樹編輯器開發中」、其餘三個顯示對應開發中文字；瀏覽器 devtools Network 面板可看到 `GET /api/projects/{id}/nodes` 與 `GET /api/projects/{id}/members` 皆回 200；console 無錯誤。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/project-detail.js
git commit -m "feat: 專案詳情頁 Vue 基礎架構與 tab 切換"
```

---

## Task 4: buildTree / numbering ＋ 樹編輯器唯讀渲染

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: Task 3 的 `nodes`（扁平陣列，欄位對應 `WbsNodeDto.Response`：`id, parentId, level, title, assigneeId, assigneeDisplayName, status, priority, startDate, endDate, notes, sortOrder`）
- Produces: 模組層函式 `buildTree(flatNodes)` → 巢狀陣列（每個節點多一個 `children` 欄位）；`numbering(tree, prefix)` → `{ [nodeId]: '1.1.1' }` 對照表；全域元件 `wbs-node-row`（Task 5-9 會持續在此元件加事件與欄位，這裡先建唯讀骨架）

- [ ] **Step 1: 新增 buildTree/numbering，在 `api()` 函式後面插入**

```js
  function buildTree(flatNodes) {
    const byId = {};
    flatNodes.forEach(n => { byId[n.id] = { ...n, children: [] }; });
    const roots = [];
    flatNodes.forEach(n => {
      const node = byId[n.id];
      if (n.parentId != null && byId[n.parentId]) {
        byId[n.parentId].children.push(node);
      } else {
        roots.push(node);
      }
    });
    const sortRec = (list) => {
      list.sort((a, b) => a.sortOrder - b.sortOrder);
      list.forEach(n => sortRec(n.children));
    };
    sortRec(roots);
    return roots;
  }

  function numbering(nodes, prefix = '') {
    const map = {};
    nodes.forEach((n, i) => {
      const num = prefix ? `${prefix}.${i + 1}` : `${i + 1}`;
      map[n.id] = num;
      if (n.children.length) Object.assign(map, numbering(n.children, num));
    });
    return map;
  }
```

- [ ] **Step 2: 新增 `WbsNodeRow` 元件（唯讀版本），放在 `TreeEditorView` 定義之前**

```js
  const WbsNodeRow = defineComponent({
    name: 'WbsNodeRow',
    props: {
      node: { type: Object, required: true },
      numbering: { type: Object, required: true },
      depth: { type: Number, default: 0 },
      canWrite: { type: Boolean, default: false },
      members: { type: Array, default: () => [] },
    },
    computed: {
      statusLabel() { return STATUS_LABEL[this.node.status] || ''; },
    },
    template: `
      <div class="wbs-node-row">
        <div class="wbs-node" :style="{ paddingLeft: (depth * 24) + 'px' }">
          <span class="wbs-num">{{ numbering[node.id] }}</span>
          <span class="wbs-status-badge" :class="'status-' + node.status.toLowerCase()">{{ statusLabel }}</span>
          <span class="wbs-title">{{ node.title }}</span>
        </div>
        <wbs-node-row v-for="child in node.children" :key="child.id" :node="child" :numbering="numbering"
          :depth="depth + 1" :can-write="canWrite" :members="members" />
      </div>
    `,
  });
```

- [ ] **Step 3: 重寫 `TreeEditorView`，改為真正渲染樹狀資料與統計列（取代 Task 3 的佔位版）**

```js
  const TreeEditorView = defineComponent({
    name: 'TreeEditorView',
    props: { nodes: { type: Array, default: () => [] }, members: { type: Array, default: () => [] }, canWrite: { type: Boolean, default: false } },
    computed: {
      tree() { return buildTree(this.nodes); },
      numberingMap() { return numbering(this.tree); },
      l3Nodes() { return this.nodes.filter(n => n.level === 3); },
      stats() {
        const total = this.l3Nodes.length;
        const done = this.l3Nodes.filter(n => n.status === 'DONE').length;
        const rate = total ? Math.round(done / total * 100) : 0;
        return { total, done, rate };
      },
    },
    template: `
      <div class="wbs-tree-editor">
        <div class="page-header"><h2>樹編輯器</h2></div>
        <p class="wbs-stats" v-if="nodes.length">細項總數：{{ stats.total }}，完成率：{{ stats.rate }}%（{{ stats.done }}/{{ stats.total }}）</p>
        <p v-if="nodes.length === 0">此專案尚未建立節點</p>
        <wbs-node-row v-for="root in tree" :key="root.id" :node="root" :numbering="numberingMap"
          :depth="0" :can-write="canWrite" :members="members" />
      </div>
    `,
  });
```

- [ ] **Step 4: 註冊 `wbs-node-row`（在既有 `app.component(...)` 那幾行旁邊新增一行）**

```js
  app.component('wbs-node-row', WbsNodeRow);
```

- [ ] **Step 5: 補 CSS**

在 `app.css` 末尾新增：

```css
.wbs-stats { color:#636e72; font-size:0.9rem; margin-bottom:1rem; }
.wbs-node { display:flex; align-items:center; gap:0.5rem; padding:0.4rem 0; border-bottom:1px solid #f0f0f0; flex-wrap:wrap; }
.wbs-num { color:#b2bec3; font-size:0.8rem; min-width:2.5rem; }
.wbs-status-badge { font-size:0.75rem; padding:0.2rem 0.5rem; border-radius:4px; }
.wbs-status-badge.status-not_started { background:#dfe6e9; color:#636e72; }
.wbs-status-badge.status-in_progress { background:#ffeaa7; color:#d68910; }
.wbs-status-badge.status-done { background:#d4efdf; color:#27ae60; }
.wbs-title { flex:1; min-width:120px; }
```

- [ ] **Step 6: 手動驗證（需要專案已有節點資料，若無先用 curl 呼叫 init 端點造資料）**

```bash
# 若測試專案尚無節點，先用瀏覽器登入後的 session cookie 或既有整合測試造資料；
# 也可直接用已有節點的既有測試專案（sql/02_test_data.sql 若有種入節點資料）
```

瀏覽器訪問專案詳情頁，切到樹編輯器 tab。
Expected：看到縮排樹狀結構、階層編號（1、1.1、1.1.1）、狀態徽章依 NOT_STARTED/IN_PROGRESS/DONE 顯示不同顏色、頂部統計列顯示正確的 L3 總數與完成率；console 無錯誤。用 chrome-devtools 截圖存證。

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 樹編輯器唯讀渲染（buildTree/numbering/統計列）"
```

---

## Task 5: 狀態循環 ＋ 標題行內編輯

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`

**Interfaces:**
- Consumes: `PATCH /api/projects/{projectId}/nodes/{nodeId}/status` body `{status}`；`PUT /api/projects/{projectId}/nodes/{nodeId}` body `{title, notes, priority, startDate, endDate}`（非 L3 只能改 `title`/`notes`，其餘欄位一律傳 `null`，見 `WbsNodeService.updateNode` 既有規則）
- Produces: Root app methods `cycleStatus(nodeId)`、`updateTitle(nodeId, title)`；`WbsNodeRow` emits `cycle-status`、`update-title`

- [ ] **Step 1: Root app 新增兩個方法（加進 Task 3 的 `methods` 區塊）**

```js
      async cycleStatus(nodeId) {
        const node = this.nodes.find(n => n.id === nodeId);
        const prev = node.status;
        node.status = STATUS_CYCLE[prev];
        const result = await api(`/api/projects/${this.projectId}/nodes/${nodeId}/status`, {
          method: 'PATCH', body: JSON.stringify({ status: node.status }),
        });
        if (!result.success) { node.status = prev; this.showToast(result.message || '狀態更新失敗'); }
      },
      async updateTitle(nodeId, title) {
        const node = this.nodes.find(n => n.id === nodeId);
        const prev = node.title;
        node.title = title;
        const result = await api(`/api/projects/${this.projectId}/nodes/${nodeId}`, {
          method: 'PUT', body: JSON.stringify({ title, notes: null, priority: null, startDate: null, endDate: null }),
        });
        if (!result.success) { node.title = prev; this.showToast(result.message || '標題更新失敗'); }
      },
```

- [ ] **Step 2: Root template 的 `tree-editor-view` 標籤補上事件綁定**

```html
          <tree-editor-view :nodes="nodes" :members="members" :can-write="canWrite"
            @cycle-status="cycleStatus" @update-title="updateTitle" />
```

- [ ] **Step 3: `TreeEditorView` 加 `emits` 並在 `wbs-node-row` 標籤轉發事件**

```js
    emits: ['cycle-status', 'update-title'],
```

template 內的 `<wbs-node-row .../>` 補上：

```html
          @cycle-status="$emit('cycle-status', $event)"
          @update-title="(id, t) => $emit('update-title', id, t)"
```

- [ ] **Step 4: `WbsNodeRow` 加上點擊循環狀態與雙擊編輯標題**

在 `props` 後新增：

```js
    emits: ['cycle-status', 'update-title'],
    data() {
      return { editingTitle: false, titleDraft: this.node.title };
    },
```

在 `methods` 區塊（新增）：

```js
    methods: {
      onCycleStatus() {
        if (this.canWrite && this.node.level === 3) this.$emit('cycle-status', this.node.id);
      },
      startEditTitle() {
        if (!this.canWrite) return;
        this.titleDraft = this.node.title;
        this.editingTitle = true;
        this.$nextTick(() => this.$refs.titleInput && this.$refs.titleInput.focus());
      },
      commitTitle() {
        this.editingTitle = false;
        if (this.titleDraft.trim() && this.titleDraft !== this.node.title) {
          this.$emit('update-title', this.node.id, this.titleDraft.trim());
        }
      },
    },
```

template 改為：

```html
    template: `
      <div class="wbs-node-row">
        <div class="wbs-node" :style="{ paddingLeft: (depth * 24) + 'px' }">
          <span class="wbs-num">{{ numbering[node.id] }}</span>
          <span class="wbs-status-badge" :class="'status-' + node.status.toLowerCase()"
                :style="{ cursor: (canWrite && node.level === 3) ? 'pointer' : 'default' }"
                @click="onCycleStatus">{{ statusLabel }}</span>
          <span v-if="!editingTitle" class="wbs-title" @dblclick="startEditTitle">{{ node.title }}</span>
          <input v-else ref="titleInput" class="wbs-title-input" v-model="titleDraft"
                 @blur="commitTitle" @keyup.enter="commitTitle" @keyup.escape="editingTitle=false" />
        </div>
        <wbs-node-row v-for="child in node.children" :key="child.id" :node="child" :numbering="numbering"
          :depth="depth + 1" :can-write="canWrite" :members="members"
          @cycle-status="$emit('cycle-status', $event)"
          @update-title="(id, t) => $emit('update-title', id, t)" />
      </div>
    `,
```

補一行 CSS（`app.css` 末尾）：

```css
.wbs-title-input { flex:1; min-width:120px; padding:0.2rem 0.4rem; border:1px solid #0984e3; border-radius:4px; }
```

- [ ] **Step 5: 手動驗證**

瀏覽器以 `leader`（可寫）登入，點擊某個 L3 節點的狀態徽章，確認狀態循環 `未開始→進行中→已完成→未開始`，Network 面板看到對應 `PATCH .../status` 回 200 且畫面即時更新；雙擊任一節點標題，改字後按 Enter，確認 `PUT` 請求送出、畫面顯示新標題；再以 `director`（唯讀）登入同專案（需 director 對該專案有讀權限），確認狀態徽章不可點、標題雙擊無反應。用 chrome-devtools 截圖存證。

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 樹編輯器狀態循環與標題行內編輯"
```

---

## Task 6: 新增節點（初始化骨架／新增類別／新增細項）

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: `POST /api/projects/{projectId}/nodes/init`（無 body）；`POST /api/projects/{projectId}/nodes` body `{parentId, presetId}` 或 `{parentId, title}`；`GET /api/presets?type=CATEGORY`（取得類別選單項目，見 `WbsPresetController`）
- Produces: Root method `createNode(payload)`、`initStages()`；`WbsNodeRow` emits `create-node`；`TreeEditorView` emits `init-stages`

- [ ] **Step 1: Root app 新增兩個方法**

```js
      async createNode(payload) {
        const result = await api(`/api/projects/${this.projectId}/nodes`, {
          method: 'POST', body: JSON.stringify(payload),
        });
        if (result.success) { await this.loadAll(); } else { this.showToast(result.message || '新增失敗'); }
      },
      async initStages() {
        const result = await api(`/api/projects/${this.projectId}/nodes/init`, { method: 'POST' });
        if (result.success) { await this.loadAll(); } else { this.showToast(result.message || '初始化失敗'); }
      },
```

- [ ] **Step 2: Root template `tree-editor-view` 標籤補事件**

```html
            @create-node="createNode" @init-stages="initStages" />
```

- [ ] **Step 3: `TreeEditorView` 補 `emits`、工具列按鈕、事件轉發**

`emits` 加入 `'create-node', 'init-stages'`。

template 的 `page-header` 區塊改為：

```html
        <div class="page-header">
          <h2>樹編輯器</h2>
          <button v-if="canWrite && nodes.length === 0" class="btn btn-primary" @click="$emit('init-stages')">初始化階段骨架</button>
        </div>
```

`<wbs-node-row>` 標籤補：

```html
          @create-node="$emit('create-node', $event)"
```

- [ ] **Step 4: `WbsNodeRow` 加上新增類別/新增細項小表單**

`emits` 加入 `'create-node'`；`data()` 加入：

```js
        showAddForm: false, addTitle: '', addPresetId: '', categoryPresets: [],
```

`methods` 加入：

```js
      async openAddForm() {
        this.showAddForm = true;
        this.addTitle = ''; this.addPresetId = '';
        if (this.node.level === 1) {
          const res = await api('/api/presets?type=CATEGORY');
          this.categoryPresets = res.success ? res.data : [];
        }
      },
      submitAdd() {
        if (this.node.level === 1) {
          if (!this.addPresetId) return;
          this.$emit('create-node', { parentId: this.node.id, presetId: Number(this.addPresetId) });
        } else {
          if (!this.addTitle.trim()) return;
          this.$emit('create-node', { parentId: this.node.id, title: this.addTitle.trim() });
        }
        this.showAddForm = false;
      },
```

template 在 `.wbs-node` 這個 `<div>` 結束標籤後、遞迴 `<wbs-node-row>` 之前，插入按鈕與 modal：

```html
        <div class="wbs-actions" v-if="canWrite && node.level < 3">
          <button class="btn btn-sm" @click="openAddForm">{{ node.level === 1 ? '新增類別' : '新增細項' }}</button>
        </div>
        <div v-if="showAddForm" class="modal-overlay" @click.self="showAddForm=false">
          <div class="modal">
            <h3>{{ node.level === 1 ? '新增類別' : '新增細項' }}</h3>
            <div class="form-group" v-if="node.level === 1">
              <label>選擇類別選單項目</label>
              <select v-model="addPresetId">
                <option value="">請選擇</option>
                <option v-for="p in categoryPresets" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
            </div>
            <div class="form-group" v-else>
              <label>細項標題</label>
              <input v-model="addTitle" @keyup.enter="submitAdd" />
            </div>
            <div class="modal-actions">
              <button class="btn" @click="showAddForm=false">取消</button>
              <button class="btn btn-primary" @click="submitAdd">新增</button>
            </div>
          </div>
        </div>
```

遞迴 `<wbs-node-row>` 標籤補：

```html
          @create-node="$emit('create-node', $event)"
```

（`.wbs-actions` 這個 class 名稱這裡先用於「新增」按鈕群，Task 8/9 會把上移/下移/刪除按鈕也放進同一個 `.wbs-actions` 容器，屆時會合併成一個 `v-if="canWrite"` 區塊，不是兩個各自判斷的 class 撞名區塊）

- [ ] **Step 5: 手動驗證**

以 `leader` 登入一個尚無節點的專案，確認顯示「初始化階段骨架」按鈕，點擊後畫面出現該科別的 STAGE 選單項目作為 L1 節點；在某 L1 節點點「新增類別」，選單跳出可選 CATEGORY 選單項目，選一項送出後該 L1 下多一個 L2 節點；在某 L2 節點點「新增細項」，填標題送出後該 L2 下多一個 L3 節點且狀態為「未開始」。用 chrome-devtools 截圖存證。

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 樹編輯器新增節點（初始化骨架/新增類別/新增細項）"
```

---

## Task 7: L3 專屬欄位（指派人／優先度／起訖日）

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: `PATCH /api/projects/{projectId}/nodes/{nodeId}/assignee` body `{assigneeId}`；`PUT /api/projects/{projectId}/nodes/{nodeId}` body 帶 `priority` 或 `startDate`/`endDate`（其餘欄位傳 `null`）
- Produces: Root methods `updateAssignee(nodeId, assigneeId)`、`updatePriority(nodeId, priority)`、`updateDates(nodeId, {startDate, endDate})`；`WbsNodeRow` emits `update-assignee`、`update-priority`、`update-dates`

- [ ] **Step 1: Root app 新增三個方法**

```js
      async updateAssignee(nodeId, assigneeId) {
        const node = this.nodes.find(n => n.id === nodeId);
        const prevId = node.assigneeId, prevName = node.assigneeDisplayName;
        node.assigneeId = assigneeId;
        const member = this.members.find(m => m.userId === assigneeId);
        node.assigneeDisplayName = member ? member.displayName : null;
        const result = await api(`/api/projects/${this.projectId}/nodes/${nodeId}/assignee`, {
          method: 'PATCH', body: JSON.stringify({ assigneeId }),
        });
        if (!result.success) { node.assigneeId = prevId; node.assigneeDisplayName = prevName; this.showToast(result.message || '指派失敗'); }
      },
      async updatePriority(nodeId, priority) {
        const node = this.nodes.find(n => n.id === nodeId);
        const prev = node.priority;
        node.priority = priority;
        const result = await api(`/api/projects/${this.projectId}/nodes/${nodeId}`, {
          method: 'PUT', body: JSON.stringify({ title: null, notes: null, priority, startDate: null, endDate: null }),
        });
        if (!result.success) { node.priority = prev; this.showToast(result.message || '優先度更新失敗'); }
      },
      async updateDates(nodeId, dates) {
        const node = this.nodes.find(n => n.id === nodeId);
        const prevStart = node.startDate, prevEnd = node.endDate;
        node.startDate = dates.startDate; node.endDate = dates.endDate;
        const result = await api(`/api/projects/${this.projectId}/nodes/${nodeId}`, {
          method: 'PUT', body: JSON.stringify({ title: null, notes: null, priority: null, startDate: dates.startDate, endDate: dates.endDate }),
        });
        if (!result.success) { node.startDate = prevStart; node.endDate = prevEnd; this.showToast(result.message || '日期更新失敗'); }
      },
```

- [ ] **Step 2: Root template `tree-editor-view` 標籤補事件**

```html
            @update-assignee="updateAssignee" @update-priority="updatePriority" @update-dates="updateDates" />
```

- [ ] **Step 3: `TreeEditorView` 補 `emits` 與轉發**

`emits` 加入 `'update-assignee', 'update-priority', 'update-dates'`；`<wbs-node-row>` 標籤補：

```html
          @update-assignee="(id, a) => $emit('update-assignee', id, a)"
          @update-priority="(id, p) => $emit('update-priority', id, p)"
          @update-dates="(id, d) => $emit('update-dates', id, d)"
```

- [ ] **Step 4: `WbsNodeRow` 加上 L3 專屬欄位**

`emits` 加入 `'update-assignee', 'update-priority', 'update-dates'`；`methods` 加入：

```js
      onAssigneeChange(e) {
        const val = e.target.value ? Number(e.target.value) : null;
        this.$emit('update-assignee', this.node.id, val);
      },
      onPriorityChange(e) {
        this.$emit('update-priority', this.node.id, e.target.value || null);
      },
      onDatesChange() {
        this.$emit('update-dates', this.node.id, { startDate: this.node.startDate, endDate: this.node.endDate });
      },
```

template 在標題（`wbs-title`/`wbs-title-input`）之後、`.wbs-actions` 之前插入：

```html
          <template v-if="node.level === 3">
            <select class="wbs-field-input" :disabled="!canWrite" :value="node.assigneeId || ''" @change="onAssigneeChange">
              <option value="">未指派</option>
              <option v-for="m in members" :key="m.userId" :value="m.userId">{{ m.displayName }}</option>
            </select>
            <select class="wbs-field-input" :disabled="!canWrite" :value="node.priority || ''" @change="onPriorityChange">
              <option value="">優先度</option>
              <option value="HIGH">高</option>
              <option value="MEDIUM">中</option>
              <option value="LOW">低</option>
            </select>
            <input type="date" class="wbs-field-input" :disabled="!canWrite" v-model="node.startDate" @change="onDatesChange" />
            <input type="date" class="wbs-field-input" :disabled="!canWrite" v-model="node.endDate" @change="onDatesChange" />
          </template>
```

遞迴 `<wbs-node-row>` 標籤補三個事件轉發：

```html
          @update-assignee="(id, a) => $emit('update-assignee', id, a)"
          @update-priority="(id, p) => $emit('update-priority', id, p)"
          @update-dates="(id, d) => $emit('update-dates', id, d)"
```

補 CSS：

```css
.wbs-field-input { font-size:0.85rem; padding:0.25rem 0.4rem; border:1px solid #dfe6e9; border-radius:4px; }
```

- [ ] **Step 5: 手動驗證**

以 `leader` 登入，對某 L3 節點：改指派人下拉（選項應只有專案成員），確認畫面與 `PATCH .../assignee` 請求一致；改優先度、改起訖日，確認對應 `PUT` 請求送出且欄位保存；重新整理頁面後三個欄位維持修改後的值（代表資料確實寫進 DB）。用 chrome-devtools 截圖存證。

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 樹編輯器 L3 指派人/優先度/起訖日"
```

---

## Task 8: 同層上移／下移

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`

**Interfaces:**
- Consumes: `PATCH /api/projects/{projectId}/nodes/reorder` body `[{nodeId, parentId, sortOrder}, ...]`
- Produces: Root method `moveNode(nodeId, direction)`；`WbsNodeRow` emits `move-node`

- [ ] **Step 1: Root app 新增方法**

```js
      async moveNode(nodeId, direction) {
        const current = this.nodes.find(n => n.id === nodeId);
        const siblings = this.nodes
          .filter(n => n.parentId === current.parentId)
          .sort((a, b) => a.sortOrder - b.sortOrder);
        const idx = siblings.findIndex(n => n.id === nodeId);
        const swapIdx = direction === 'up' ? idx - 1 : idx + 1;
        if (swapIdx < 0 || swapIdx >= siblings.length) return;
        const a = siblings[idx], b = siblings[swapIdx];
        const result = await api(`/api/projects/${this.projectId}/nodes/reorder`, {
          method: 'PATCH',
          body: JSON.stringify([
            { nodeId: a.id, parentId: a.parentId, sortOrder: b.sortOrder },
            { nodeId: b.id, parentId: b.parentId, sortOrder: a.sortOrder },
          ]),
        });
        if (result.success) { await this.loadAll(); } else { this.showToast(result.message || '排序失敗'); }
      },
```

- [ ] **Step 2: Root template 補事件**

```html
            @move-node="moveNode" />
```

（加在 Task 7 Step 2 那行 `tree-editor-view` 標籤內）

- [ ] **Step 3: `TreeEditorView` 補 `emits` 與轉發**

`emits` 加入 `'move-node'`；`<wbs-node-row>` 標籤補：

```html
          @move-node="(id, dir) => $emit('move-node', id, dir)"
```

- [ ] **Step 4: `WbsNodeRow` 加上上移/下移按鈕**

`emits` 加入 `'move-node'`；`methods` 加入：

```js
      onMoveUp() { this.$emit('move-node', this.node.id, 'up'); },
      onMoveDown() { this.$emit('move-node', this.node.id, 'down'); },
```

template：把 Task 6 的 `.wbs-actions` 區塊改為同時包含上移/下移與新增按鈕：

```html
        <div class="wbs-actions" v-if="canWrite">
          <button class="btn btn-sm" @click="onMoveUp">↑</button>
          <button class="btn btn-sm" @click="onMoveDown">↓</button>
          <button class="btn btn-sm" v-if="node.level < 3" @click="openAddForm">{{ node.level === 1 ? '新增類別' : '新增細項' }}</button>
        </div>
```

（取代 Task 6 Step 4 寫的 `<div class="wbs-actions" v-if="canWrite && node.level < 3">...</div>`）

遞迴 `<wbs-node-row>` 標籤補：

```html
          @move-node="(id, dir) => $emit('move-node', id, dir)"
```

補 CSS：

```css
.wbs-actions { display:flex; gap:0.25rem; margin-left:auto; }
```

- [ ] **Step 5: 手動驗證**

以 `leader` 登入，對同一層的節點點上移/下移，確認畫面順序即時反映且 `PATCH .../reorder` 回 200；最上/最下節點點對應方向按鈕應無反應（不送請求，`swapIdx` 超出範圍時 `return`）。用 chrome-devtools 截圖存證。

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 樹編輯器同層上移/下移"
```

---

## Task 9: 刪除節點

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`

**Interfaces:**
- Consumes: `DELETE /api/projects/{projectId}/nodes/{nodeId}`
- Produces: Root method `deleteNode(nodeId)`；`WbsNodeRow` emits `delete-node`

- [ ] **Step 1: Root app 新增方法**

```js
      async deleteNode(nodeId) {
        const result = await api(`/api/projects/${this.projectId}/nodes/${nodeId}`, { method: 'DELETE' });
        if (result.success) { await this.loadAll(); } else { this.showToast(result.message || '刪除失敗'); }
      },
```

- [ ] **Step 2: Root template 補事件**

```html
            @delete-node="deleteNode" />
```

- [ ] **Step 3: `TreeEditorView` 補 `emits` 與轉發**

`emits` 加入 `'delete-node'`；`<wbs-node-row>` 標籤補：

```html
          @delete-node="$emit('delete-node', $event)"
```

- [ ] **Step 4: `WbsNodeRow` 加上刪除按鈕與 confirm**

`emits` 加入 `'delete-node'`；`methods` 加入：

```js
      onDelete() {
        const msg = this.node.children.length
          ? `確定刪除「${this.node.title}」？將一併刪除其下所有子節點。`
          : `確定刪除「${this.node.title}」？`;
        if (confirm(msg)) this.$emit('delete-node', this.node.id);
      },
```

template：`.wbs-actions` 內補一個刪除按鈕：

```html
          <button class="btn btn-sm btn-danger" @click="onDelete">刪除</button>
```

遞迴 `<wbs-node-row>` 標籤補：

```html
          @delete-node="$emit('delete-node', $event)"
```

- [ ] **Step 5: 手動驗證**

以 `leader` 登入，刪除一個沒有子節點的 L3，確認直接消失、不跳警告；刪除一個有子節點的 L2，確認 confirm 文字有講明會連坐刪除子節點，確認後該 L2 與其下所有節點都消失。用 chrome-devtools 截圖存證。

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/project-detail.js
git commit -m "feat: 樹編輯器刪除節點"
```

---

## Task 10: canWrite 唯讀模式收尾 ＋ 多角色瀏覽器驗證

**Files:**
- 無新檔案異動（本任務純驗證，若驗證中發現 bug 才會回頭修改前面任務建立的檔案）

**Interfaces:**
- 無新介面，驗證 Task 1-9 的整體行為

- [ ] **Step 1: 啟動環境**

```bash
docker compose up -d
mvn spring-boot:run
```

確認容器與應用程式皆正常啟動（附啟動記錄關鍵行）。

- [ ] **Step 2: 執行全部既有測試，確認沒有回歸**

Run: `mvn test`
Expected: BUILD SUCCESS，所有測試（含 Task 1 新增的 `ProjectDetailPageTest`）通過

- [ ] **Step 3: 用 chrome-devtools 以 `leader`（可寫）登入，完整操作一輪樹編輯器**

依序：初始化骨架 → 新增類別 → 新增細項 → 改標題 → 循環狀態 → 改指派人/優先度/日期 → 上移/下移 → 刪除節點。每步確認畫面即時更新、無需重整；確認 console 全程無錯誤。

- [ ] **Step 4: 用 chrome-devtools 以 `director`（跨科唯讀）登入同一專案**

Expected：樹狀資料正常顯示（唯讀），但狀態徽章不可點、標題雙擊無反應、看不到任何新增/上移/下移/刪除按鈕、L3 的指派人/優先度/日期欄位皆為 disabled。

- [ ] **Step 5: 若步驟 3-4 發現任何錯誤或畫面異常**

回頭修正對應任務建立的檔案，重新驗證到通過為止，修正內容需在完成回報中列出（依 CLAUDE.md 驗證紀律，不得只讀 code 判斷沒問題）。

- [ ] **Step 6: 最終截圖存證並回報**

用 `SendUserFile` 傳送本輪關鍵截圖（樹編輯器操作後的畫面、唯讀模式畫面），回報摘要列出已驗證項目。

（本任務不需要 Step 7 commit——若 Step 5 有修正才需要對修正內容另外 commit）
