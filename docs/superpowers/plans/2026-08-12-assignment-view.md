# 人員派工分頁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 專案詳情頁新增「人員派工」分頁，依成員分欄（含「未指派」欄）呈現任務，可拖曳卡片跨欄改指派人；根元件改為雙分頁（看板／人員派工）以 `v-if` 切換。

**Architecture:** 純前端功能，全部使用既有、已測試過的後端 API（`GET .../tasks`、`GET .../members`、`PATCH .../tasks/{taskId}/assignee`），不動後端程式碼。新增 Vue 元件 `AssignmentView`，與現有 `KanbanView` 平行、各自獨立 `mounted()` 時 fetch 自己的資料。根元件從單一元件殼改為兩顆分頁按鈕 + `v-if` 切換兩個分頁元件（**不用 `v-show`**：若兩元件同時常駐掛載，各自的 `tasks` 副本會在對方寫入後失去同步；`v-if` 讓切換分頁時重新掛載、重新 fetch，永遠拿到新資料）。

**Tech Stack:** Vue 3（無 build 工具，vendored UMD），與看板前端相同。

## Global Constraints

- 依 [`docs/superpowers/specs/2026-08-12-assignment-view-design.md`](../specs/2026-08-12-assignment-view-design.md) 執行。
- **不改後端**：`PATCH /api/projects/{id}/tasks/{taskId}/assignee` body `{assigneeId}`（`null` 清空指派）原樣使用；已驗證指派對象須為專案成員。
- **分頁切換用 `v-if`，不用 `v-show`**（見上方 Architecture 說明）。
- **人員派工分頁 v1 不支援點卡片開 modal 編輯**——完整編輯（標題/分類/優先度/日期）維持在看板分頁做；本分頁卡片只能拖曳改指派人。
- **欄內排序固定規則，不使用 `sort_order`、不呼叫 `move` 端點**：依 `dueDate` 升冪，無到期日排最後；到期日相同（含都無到期日）依任務 `id` 升冪。
- 「顯示已完成」開關預設關閉、不持久化，切分頁（`v-if` remount）會重置。
- 卡片拖曳跨欄的樂觀更新必須同時改 `assigneeId` **與** `assigneeDisplayName`，不只改 id。
- 沿用既有命名慣例：CSS class 用字面 hex 色碼；`api()`/`showToast` 錯誤處理骨架照抄既有 `KanbanView` 寫法。

---

## Task 1: 人員派工分頁（`project-detail.js` + `app.css`）

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: `GET /api/projects/{id}/tasks`、`GET /api/projects/{id}/members`、`PATCH /api/projects/{id}/tasks/{taskId}/assignee`（皆為既有、已測試過的端點）
- Produces: 無新公開介面；根 Vue app 新增 `activeTab` state；新增元件 `assignment-view`（props：`projectId: Number`、`canWrite: Boolean`）

- [ ] **Step 1: 把 `isOverdue` 判斷邏輯抽成 module-level 函式，供兩個分頁共用**

現況 `KanbanView` 的 `methods` 裡有：

```javascript
      // 已完成的任務不再警示逾期，避免歷史卡片一片紅；用本地日期字串比對，避免 toISOString 的 UTC 誤差
      isOverdue(t) {
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        return !!t.dueDate && t.status !== 'DONE' && t.dueDate < today;
      },
```

人員派工分頁的卡片也要顯示逾期樣式，邏輯必須跟看板一致，不能各自維護一份（容易日後改一邊漏改另一邊）。在檔案開頭 `api` 函式定義之後（`KanbanView` 定義之前）加一個 module-level 函式：

```javascript
  // 已完成的任務不再警示逾期，避免歷史卡片一片紅；用本地日期字串比對，避免 toISOString 的 UTC 誤差
  function isOverdueDate(t) {
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    return !!t.dueDate && t.status !== 'DONE' && t.dueDate < today;
  }
```

然後把 `KanbanView.methods.isOverdue` 改成委派給它（保留方法名稱，模板 `isOverdue(t)` 呼叫處不用改）：

```javascript
      isOverdue(t) {
        return isOverdueDate(t);
      },
```

- [ ] **Step 2: 新增 `AssignmentView` 元件**

在 `KanbanView` 元件定義結束（`});`，即現有檔案第 488 行 `});` 之後、`const app = createApp({` 之前，插入：

```javascript
  const AssignmentView = defineComponent({
    name: 'AssignmentView',
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
    },
    data() {
      return {
        tasks: [], members: [],
        loading: true,
        showDone: false,
        dragging: null,
        dragOverAssignee: undefined, // undefined=未拖曳中；null=懸停在「未指派」欄；number=懸停在該成員欄
        toastMessage: '', toastTimer: null,
      };
    },
    computed: {
      columns() {
        return [{ assigneeId: null, label: '未指派' }, ...this.members.map(m => ({ assigneeId: m.userId, label: m.displayName }))];
      },
    },
    methods: {
      async loadAll() {
        this.loading = true;
        try {
          const [tasksRes, membersRes] = await Promise.all([
            api(`/api/projects/${this.projectId}/tasks`),
            api(`/api/projects/${this.projectId}/members`),
          ]);
          this.tasks = tasksRes.success ? tasksRes.data : [];
          this.members = membersRes.success ? membersRes.data : [];
          if (!tasksRes.success || !membersRes.success) {
            this.showToast(tasksRes.message || membersRes.message || '載入失敗，請重新整理');
          }
        } catch (e) {
          this.showToast('載入失敗，請重新整理');
        } finally {
          this.loading = false;
        }
      },
      showToast(message) {
        this.toastMessage = message;
        clearTimeout(this.toastTimer);
        this.toastTimer = setTimeout(() => { this.toastMessage = ''; }, 3000);
      },
      isOverdue(t) {
        return isOverdueDate(t);
      },
      statusLabel(status) {
        return { NOT_STARTED: '未開始', IN_PROGRESS: '進行中', DONE: '已完成' }[status];
      },
      // 依到期日升冪排序，無到期日排最後；到期日相同（含都無到期日）依 id 升冪，穩定排序不需額外欄位
      tasksFor(assigneeId) {
        return this.tasks
          .filter(t => t.assigneeId === assigneeId && (this.showDone || t.status !== 'DONE'))
          .sort((a, b) => {
            if (a.dueDate == null && b.dueDate == null) return a.id - b.id;
            if (a.dueDate == null) return 1;
            if (b.dueDate == null) return -1;
            if (a.dueDate !== b.dueDate) return a.dueDate < b.dueDate ? -1 : 1;
            return a.id - b.id;
          });
      },
      onDragStart(t, ev) {
        this.dragging = t;
        ev.dataTransfer.effectAllowed = 'move';
      },
      async onDrop(assigneeId) {
        if (!this.dragging || !this.canWrite) return;
        const task = this.dragging;
        this.dragging = null;
        this.dragOverAssignee = undefined;
        if (task.assigneeId === assigneeId) return;
        await this.updateAssignee(task, assigneeId);
      },
      // 樂觀更新要連 assigneeDisplayName 一起改，否則失敗回滾或欄位重新分組時會找不到對應成員
      async updateAssignee(task, assigneeId) {
        const prevId = task.assigneeId, prevName = task.assigneeDisplayName;
        task.assigneeId = assigneeId;
        const member = this.members.find(m => m.userId === assigneeId);
        task.assigneeDisplayName = member ? member.displayName : null;
        const result = await api(`/api/projects/${this.projectId}/tasks/${task.id}/assignee`, {
          method: 'PATCH', body: JSON.stringify({ assigneeId }),
        });
        if (!result.success) {
          task.assigneeId = prevId;
          task.assigneeDisplayName = prevName;
          this.showToast(result.message || '指派失敗');
        }
      },
    },
    mounted() {
      this.loadAll();
    },
    template: `
      <div>
        <div class="assignment-toolbar">
          <label class="assignment-toggle">
            <input type="checkbox" v-model="showDone" /> 顯示已完成
          </label>
        </div>
        <p v-if="loading">載入中...</p>
        <div v-else class="assignment-board">
          <div v-for="col in columns" :key="col.assigneeId === null ? 'unassigned' : col.assigneeId"
               class="kanban-col assignment-col"
               :class="{ 'drag-over': dragOverAssignee === col.assigneeId }"
               @dragover.prevent="dragOverAssignee = col.assigneeId"
               @dragleave="dragOverAssignee = undefined"
               @drop="onDrop(col.assigneeId)">
            <div class="kanban-col-header">
              <span>{{ col.label }}</span>
              <span class="kanban-col-count">{{ tasksFor(col.assigneeId).length }}</span>
            </div>
            <div v-for="t in tasksFor(col.assigneeId)" :key="t.id"
                 class="task-card" :class="['priority-' + (t.priority || 'NONE'), { dragging: dragging === t }]"
                 :draggable="canWrite" @dragstart="onDragStart(t, $event)">
              <div class="task-card-title">{{ t.title }}</div>
              <div class="task-card-meta">
                <span>{{ statusLabel(t.status) }}</span>
                <span class="task-due" :class="{ overdue: isOverdue(t) }" v-if="t.dueDate">{{ t.dueDate }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

```

- [ ] **Step 3: 根 app 改為雙分頁（`v-if` 切換）並註冊 `assignment-view`**

把檔尾的：

```javascript
  const app = createApp({
    data() {
      return { projectId, canWrite, sectionId };
    },
    template: `<kanban-view :project-id="projectId" :can-write="canWrite" :section-id="sectionId" />`,
  });

  app.component('kanban-view', KanbanView);
  app.mount('#detail-app');
})();
```

改成：

```javascript
  const app = createApp({
    data() {
      return { projectId, canWrite, sectionId, activeTab: 'kanban' };
    },
    template: `
      <div>
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</button>
        </div>
        <kanban-view v-if="activeTab === 'kanban'" :project-id="projectId" :can-write="canWrite" :section-id="sectionId" />
        <assignment-view v-else :project-id="projectId" :can-write="canWrite" />
      </div>
    `,
  });

  app.component('kanban-view', KanbanView);
  app.component('assignment-view', AssignmentView);
  app.mount('#detail-app');
})();
```

- [ ] **Step 4: `app.css` 加分頁按鈕與人員派工看板樣式**

在 `app.css` 檔尾（既有 `.preset-picker-popover li` 規則之後）加：

```css
.detail-tabs { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.assignment-toolbar { display: flex; justify-content: flex-end; margin-bottom: 1rem; }
.assignment-toggle { display: flex; align-items: center; gap: 0.4rem; font-size: 0.9rem; cursor: pointer; }
.assignment-board { display: flex; gap: 1rem; overflow-x: auto; padding-bottom: 0.5rem; align-items: start; }
.assignment-col { flex: 0 0 240px; }
```

（`.assignment-col` 疊加在既有 `.kanban-col` 上：背景／圓角／padding／`min-height` 沿用，只多補一個橫向捲動看板需要的固定寬度。）

- [ ] **Step 5: `mvn test` 確認全綠（本任務不改後端，預期無影響）**

```bash
mvn test
```

Expected: BUILD SUCCESS，測試數與改動前相同（無新增/刪除任何測試）。

- [ ] **Step 6: 瀏覽器實測（無 JS 測試框架，這是本任務的主要驗證方式）**

```bash
docker compose up -d
mvn spring-boot:run
```

以 `leader`/`password123` 登入「MissionBoard 範例專案」（種子資料：`leader` 與 `member` 為專案成員；任務「設計登入頁」指派給 `member`／`DONE`，「實作看板拖曳」指派給 `leader`／`IN_PROGRESS`，「整理需求訪談紀錄」未指派／`NOT_STARTED`），依序確認：

1. 專案詳情頁上方出現「看板」「人員派工」兩顆分頁按鈕，預設停在看板
2. 點「人員派工」切換，出現「未指派」「專案負責人」「專案成員」三欄橫向排列
3. 預設「顯示已完成」關閉：「專案成員」欄應只看到 0 筆（唯一指派給 member 的任務是 DONE，被過濾掉）；「專案負責人」欄看到「實作看板拖曳」；「未指派」欄看到「整理需求訪談紀錄」
4. 勾選「顯示已完成」：「專案成員」欄出現「設計登入頁」，狀態標籤顯示「已完成」
5. 拖曳「整理需求訪談紀錄」從「未指派」欄拖到「專案成員」欄，確認卡片移動且欄位計數同步變化；重新整理頁面後仍在新欄位（後端已寫入）
6. 切回「看板」分頁，確認該任務卡片的指派人已同步顯示為「專案成員」（驗證 `v-if` 重新 fetch 沒有拿到舊資料）
7. 用 `member`（`PROJECT_MEMBER`，非本專案 leader）或封存後的專案登入測試唯讀情境：卡片不可拖曳（`draggable` 應為 `false`，可用 chrome-devtools 檢查屬性或直接嘗試拖曳無反應）
8. console 面板確認無錯誤
9. 依專案 CLAUDE.md「有畫面就有截圖」規則截圖存證（人員派工分頁初始狀態、拖曳後狀態，兩張以內）

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 新增人員派工分頁，依成員分欄可拖曳改指派"
```

---

## Task 2: 更新 CLAUDE.md 反映雙分頁現況

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 修正兩處「看板為預設且唯一分頁」敘述**

`CLAUDE.md` 第 9 行（## 專案狀態段落）目前是：

```
`tasks`/`task_categories`/`task_category_presets` 已取代舊的 `wbs_nodes`/`wbs_presets`，`wbs` 套件與相關 DDL 已移除。看板（`KanbanView`）為專案詳情頁預設且唯一的分頁，可實際操作：拖曳卡片跨欄、建立任務、歸類、指派皆走 REST＋樂觀更新。舊的樹編輯器／人員派工／甘特三個分頁已隨這次重構移除，若後續要重做，需依新的扁平任務模型另行設計，不可沿用舊 `wbs_nodes` 邏輯。依任務路由表，新功能一律先走 `superpowers:brainstorming`。
```

改成：

```
`tasks`/`task_categories`/`task_category_presets` 已取代舊的 `wbs_nodes`/`wbs_presets`，`wbs` 套件與相關 DDL 已移除。專案詳情頁為雙分頁：看板（`KanbanView`，預設分頁）可拖曳卡片跨欄、建立任務、歸類、指派；人員派工（`AssignmentView`）依成員分欄，可拖曳卡片跨欄改指派人，皆走 REST＋樂觀更新。舊的樹編輯器／人員派工／甘特三個分頁曾隨重構移除，人員派工已依新的扁平任務模型重做完成；樹編輯器／甘特若後續要重做，需另行設計，不可沿用舊 `wbs_nodes` 邏輯。依任務路由表，新功能一律先走 `superpowers:brainstorming`。
```

第 73 行（前端模式段落）目前是：

```
專案詳情頁一次載入任務與分類資料，看板（`project-detail.js` 的 `KanbanView`）是預設且唯一落地的檢視。所有修改走 REST，成功後就地更新（樂觀更新＋失敗回滾、fetch 失敗顯示 toast）：拖曳卡片跨欄呼叫 `move` 端點、建立任務預設「未歸類」（`category_id` 為 NULL）、點卡片開 modal 編輯歸類／指派／優先度／日期。
```

改成：

```
專案詳情頁（`project-detail.js`）為雙分頁，各自獨立載入資料、以 `v-if` 切換（非 `v-show`，避免兩分頁資料不同步）：看板（`KanbanView`，預設分頁）一次載入任務與分類資料；人員派工（`AssignmentView`）載入任務與成員資料，依成員分欄（含「未指派」欄）呈現，v1 僅支援拖曳改指派，不支援點卡片開 modal（完整編輯回看板做）。所有修改走 REST，成功後就地更新（樂觀更新＋失敗回滾、fetch 失敗顯示 toast）：看板拖曳卡片跨欄呼叫 `move` 端點、建立任務預設「未歸類」（`category_id` 為 NULL）、點卡片開 modal 編輯歸類／指派／優先度／日期；人員派工拖曳卡片跨欄呼叫 `assignee` 端點，欄內排序為固定規則（依到期日，無到期日排最後）。
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 反映人員派工分頁已依新模型重做完成"
```

---

## 收尾：驗證

- [ ] Task 1 Step 6 的瀏覽器實測全數通過、Task 2 的文件更新完成即整體完成，無需額外收尾步驟。
