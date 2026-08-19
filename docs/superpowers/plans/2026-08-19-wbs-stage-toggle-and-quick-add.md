# WBS 分類管理收斂 + 快速新增任務 + 任務狀態快速切換 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 看板「分類管理」面板整個移除，分類（大類/子類）新增/改名/刪除/排序全部收斂到 WBS 檢視；WBS 新增「未歸類」與每個子類別旁的快速新增任務、每筆任務的狀態快速循環按鈕。

**Architecture:** 前端改動集中在 `src/main/resources/static/js/project-detail.js`（`categoryPresetMixin`、`KanbanView`、`WbsView`、`WbsTaskRow`）與 `src/main/resources/static/css/app.css`；唯一的後端改動是 `TaskCategoryDto.CreateRequest` 新增可選 `name` 欄位、`TaskCategoryService.create()` 支援不經 preset 直接用名稱建立分類，其餘全部重用既有 REST 端點。

**Tech Stack:** Vue 3（CDN 版，無 build 工具）、Spring Boot 3 + JPA、既有 CSS token（`--signal`/`--line`/`--paper`/`--ink-muted`/`--danger`）。

## Global Constraints

- 本次唯一允許的後端改動：`TaskCategoryDto.CreateRequest` 加 `name` 欄位、`TaskCategoryService.create()` 的對應分支邏輯；其餘一律重用既有 REST 端點，不新增端點、不改 DDL
- 只有子類別（CATEGORY 層級）新增走「輸入名稱」；大項（STAGE 層級）維持只能從 preset 啟用/停用，不開放自由輸入大項名稱
- 快速新增任務的「+ 新增」不出現在大項節點旁，只出現在「未歸類」與每個子類別節點旁（大項底下不直接掛任務，維持現狀）
- 這個專案的前端沒有 JS 測試框架（vanilla Vue 3、無 build 工具），前端驗證一律用啟動應用程式後以瀏覽器手動操作 + chrome-devtools 截圖；後端改動需要 `mvn test` 通過
- 程式碼風格比照檔案現有寫法：不使用 optional chaining（`?.`）
- 測試帳號：`leader` / `password123`（密碼所有帳號皆同），種子資料 `project_id=1` 已有一個 `SIT` 大類、其下 `程式開發` 子類別（見 `sql/02_test_data.sql`）；應用程式網址 `http://localhost:8080`
- **Task 1（`deleteCategory` 搬進 `categoryPresetMixin`、`createCategoryFromPreset` 加可選 `parentCategoryId` 參數）已於 commit `828b244` 完成且審查通過，不用重做**——本計畫從 Task 2 開始

---

### Task 2: 後端——`TaskCategoryDto.CreateRequest` 支援直接用名稱建立分類

**Files:**
- Modify: `src/main/java/com/missionboard/task/TaskCategoryDto.java`
- Modify: `src/main/java/com/missionboard/task/TaskCategoryService.java:26-57`
- Modify: `src/test/java/com/missionboard/task/TaskCategoryServiceTest.java`（13 處既有呼叫加參數 + 2 個新測試）
- Modify: `src/test/java/com/missionboard/task/TaskCategoryControllerTest.java`（2 個新測試）

**Interfaces:**
- Consumes：無新依賴
- Produces：`TaskCategoryDto.CreateRequest(Long parentCategoryId, Long presetId, String name, Integer sortOrder)`——後續任務（Task 4）的前端 `createCategoryWithName(name, parentCategoryId)` 會呼叫 `POST /api/projects/{id}/task-categories`，body 帶 `{ parentCategoryId, name, sortOrder: null }`（不帶 `presetId`）

- [ ] **Step 1: 修改 `TaskCategoryDto.CreateRequest`，加入可選 `name` 欄位**

現在的 `TaskCategoryDto.java`：

```java
public record CreateRequest(Long parentCategoryId, Long presetId, Integer sortOrder) {}
```

改成：

```java
public record CreateRequest(Long parentCategoryId, Long presetId, String name, Integer sortOrder) {}
```

（`name` 放在 `presetId` 之後、`sortOrder` 之前——這會讓所有既有的 3 參數呼叫變成需要 4 參數，Step 3 統一處理）

- [ ] **Step 2: 修改 `TaskCategoryService.create()`，`presetId`／`name` 擇一必填**

現在（`TaskCategoryService.java:26-57`）：

```java
    // 建立類別：從選單快照名稱；深度上限兩層在此強制（service 層驗證，不用 DB CHECK）
    @Transactional
    public TaskCategory create(Long projectId, TaskCategoryDto.CreateRequest req) {
        if (req.presetId() == null) {
            throw new IllegalArgumentException("選單項目為必填");
        }
        Project project = projectService.getById(projectId);
        TaskCategory parent = null;
        if (req.parentCategoryId() != null) {
            parent = getCategoryInProject(projectId, req.parentCategoryId());
            if (parent.getParentCategory() != null) {
                throw new IllegalArgumentException("已達第二層，無法在類別下新增子類別");
            }
        }

        TaskCategoryPreset preset = taskCategoryPresetRepository.findById(req.presetId())
            .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
        TaskCategoryPreset.Type expectedType = parent == null
            ? TaskCategoryPreset.Type.STAGE : TaskCategoryPreset.Type.CATEGORY;
        if (preset.getType() != expectedType) {
            throw new IllegalArgumentException("選單項目型別不符");
        }
        if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
            throw new IllegalArgumentException("選單項目不屬於此專案科別");
        }

        TaskCategory category = new TaskCategory();
        category.setProject(project);
        category.setParentCategory(parent);
        category.setName(preset.getName());
        category.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        return taskCategoryRepository.save(category);
    }
```

改成：

```java
    // 建立類別：presetId 從選單快照名稱，或直接用 name 建立；深度上限兩層在此強制（service 層驗證，不用 DB CHECK）
    @Transactional
    public TaskCategory create(Long projectId, TaskCategoryDto.CreateRequest req) {
        if (req.presetId() == null && (req.name() == null || req.name().isBlank())) {
            throw new IllegalArgumentException("選單項目或名稱擇一必填");
        }
        Project project = projectService.getById(projectId);
        TaskCategory parent = null;
        if (req.parentCategoryId() != null) {
            parent = getCategoryInProject(projectId, req.parentCategoryId());
            if (parent.getParentCategory() != null) {
                throw new IllegalArgumentException("已達第二層，無法在類別下新增子類別");
            }
        }

        String name;
        if (req.presetId() != null) {
            TaskCategoryPreset preset = taskCategoryPresetRepository.findById(req.presetId())
                .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
            TaskCategoryPreset.Type expectedType = parent == null
                ? TaskCategoryPreset.Type.STAGE : TaskCategoryPreset.Type.CATEGORY;
            if (preset.getType() != expectedType) {
                throw new IllegalArgumentException("選單項目型別不符");
            }
            if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
                throw new IllegalArgumentException("選單項目不屬於此專案科別");
            }
            name = preset.getName();
        } else {
            name = req.name().trim();
        }

        TaskCategory category = new TaskCategory();
        category.setProject(project);
        category.setParentCategory(parent);
        category.setName(name);
        category.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        return taskCategoryRepository.save(category);
    }
```

- [ ] **Step 3: 更新 `TaskCategoryServiceTest.java` 既有 13 處 `new TaskCategoryDto.CreateRequest(...)` 呼叫**

先執行 `grep -n "new TaskCategoryDto.CreateRequest(" src/test/java/com/missionboard/task/TaskCategoryServiceTest.java`，應該看到 13 行，形式都是：

```java
new TaskCategoryDto.CreateRequest(<parentCategoryId>, <presetId>, <sortOrder>)
```

每一行改成 4 參數，在第 3 個位置（`presetId` 之後）插入 `null`：

```java
new TaskCategoryDto.CreateRequest(<parentCategoryId>, <presetId>, null, <sortOrder>)
```

例如原本：

```java
new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null)
```

改成：

```java
new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null)
```

（原本第三個參數是 `sortOrder`，這裡剛好本來就都傳 `null`，所以視覺上會變成兩個相鄰的 `null, null`——這是正確的，不是重複打錯，第一個 `null` 是新的 `name` 欄位、第二個 `null` 是原本的 `sortOrder`）

13 處全部改完後，重新執行同一個 grep 確認還是 13 行、且每行都是 4 個參數（用 `grep -c ", null, null)"` 或直接讀過一遍確認）。

- [ ] **Step 4: 在 `TaskCategoryServiceTest.java` 新增 2 個測試，驗證 `name` 建立路徑**

在 `rejectsThirdLevelCategory` 測試（`createsSubCategoryUnderStageLevelParent` 之後）附近，新增：

```java
    @Test
    void createsSubCategoryFromNameWhenPresetIdMissing() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), null, "自訂子類別", null));
        assertThat(sub.getName()).isEqualTo("自訂子類別");
        assertThat(sub.getParentCategory().getId()).isEqualTo(stage.getId());
    }

    @Test
    void rejectsCreateWhenBothPresetIdAndNameMissing() {
        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }
```

- [ ] **Step 5: 在 `TaskCategoryControllerTest.java` 新增 2 個測試**

在既有的 `createStageLevelCategoryFromPreset` 測試之後新增：

```java
    @Test
    void createCategoryFromNameWhenPresetIdMissing() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/task-categories", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":null,\"name\":\"自訂名稱\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("自訂名稱"));
    }

    @Test
    void createFailsWhenBothPresetIdAndNameMissing() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/task-categories", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":null}"))
            .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 6: 執行測試確認全部通過**

Run: `mvn test -Dtest=TaskCategoryServiceTest,TaskCategoryControllerTest`
Expected: 全部通過（既有測試因為改成 4 參數不會壞掉行為，新增的 4 個測試通過）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/missionboard/task/TaskCategoryDto.java src/main/java/com/missionboard/task/TaskCategoryService.java src/test/java/com/missionboard/task/TaskCategoryServiceTest.java src/test/java/com/missionboard/task/TaskCategoryControllerTest.java
git commit -m "feat: 分類建立 API 支援不經選單、直接用名稱建立"
```

---

### Task 3: 移除看板「分類管理」面板，改名/拖曳排序邏輯搬進共用 mixin

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js:198-269`（`categoryPresetMixin`，新增 4 個方法與對應 state）
- Modify: `src/main/resources/static/js/project-detail.js:339-352`（`KanbanView.data()`）
- Modify: `src/main/resources/static/js/project-detail.js:425-484`（`KanbanView.methods` 裡要搬走的 4 個方法，整段刪除）
- Modify: `src/main/resources/static/js/project-detail.js:489-552`（`KanbanView` template，移除分類管理面板）
- Modify: `src/main/resources/static/css/app.css:84-92`（移除面板專用樣式）

**Interfaces:**
- Consumes：Task 1 產出的 `categoryPresetMixin.methods.deleteCategory`
- Produces：`categoryPresetMixin` 新增 `data()` 的 `editingCategoryId`/`categoryNameDraft`/`draggingCategoryId`，以及 `methods` 的 `startEditCategoryName(category)`/`commitCategoryName(category)`/`onCategoryDragStart(category, ev)`/`onCategoryDrop(targetCategory)`——這四個方法簽章與行為跟原本 `KanbanView` 裡的完全相同，供 Task 4 的 `WbsView` 直接使用

- [ ] **Step 1: `categoryPresetMixin.data()` 加入改名/拖曳排序需要的 state**

現在（`project-detail.js:201-208`）：

```js
    data() {
      return {
        presetsLoaded: false,
        stagePresets: [],
        categoryPresets: [],
        presetPicker: null, // { parentCategoryId: null|number } 開啟中的選單挑選器；null 表示未開啟
      };
    },
```

改成：

```js
    data() {
      return {
        presetsLoaded: false,
        stagePresets: [],
        categoryPresets: [],
        presetPicker: null, // { parentCategoryId: null|number } 開啟中的選單挑選器；null 表示未開啟
        editingCategoryId: null,
        categoryNameDraft: '',
        draggingCategoryId: null,
      };
    },
```

- [ ] **Step 2: `categoryPresetMixin.methods` 新增 4 個方法（從 `KanbanView` 搬過來，逐字相同）**

在 `project-detail.js:246-267` 的 `deleteCategory` 方法之後、`methods` 區塊結尾的 `},` 之前，加入：

```js
      startEditCategoryName(category) {
        if (!this.canWrite) return;
        this.editingCategoryId = category.id;
        this.categoryNameDraft = category.name;
      },
      async commitCategoryName(category) {
        if (this.editingCategoryId !== category.id) return;
        this.editingCategoryId = null;
        const name = this.categoryNameDraft.trim();
        if (!name || name === category.name) return;
        const prev = category.name;
        category.name = name;
        const result = await api(`/api/projects/${this.projectId}/task-categories/${category.id}`, {
          method: 'PUT', body: JSON.stringify({ name }),
        });
        if (!result.success) {
          category.name = prev;
          this.showToast(result.message || '改名失敗');
        }
      },
      onCategoryDragStart(category, ev) {
        if (!this.canWrite) return;
        this.draggingCategoryId = category.id;
        ev.dataTransfer.effectAllowed = 'move';
      },
      // 只在同一層內重新排序：跨層（parentCategoryId 不同）一律忽略，不送任何請求
      async onCategoryDrop(targetCategory) {
        const draggingId = this.draggingCategoryId;
        this.draggingCategoryId = null;
        if (draggingId == null || draggingId === targetCategory.id) return;
        const dragging = this.categories.find(c => c.id === draggingId);
        if (!dragging || dragging.parentCategoryId !== targetCategory.parentCategoryId) return;

        const siblings = this.categories
          .filter(c => c.parentCategoryId === dragging.parentCategoryId)
          .sort((a, b) => a.sortOrder - b.sortOrder);
        const fromIdx = siblings.findIndex(c => c.id === dragging.id);
        const toIdx = siblings.findIndex(c => c.id === targetCategory.id);
        siblings.splice(fromIdx, 1);
        siblings.splice(toIdx, 0, dragging);

        const changed = [];
        siblings.forEach((c, i) => {
          if (c.sortOrder !== i) {
            c.sortOrder = i;
            changed.push(c);
          }
        });
        if (changed.length === 0) return;

        const results = await Promise.all(changed.map(c =>
          api(`/api/projects/${this.projectId}/task-categories/${c.id}`, {
            method: 'PUT', body: JSON.stringify({ sortOrder: c.sortOrder }),
          })
        ));
        if (results.some(r => !r.success)) {
          this.showToast('排序失敗，已重新載入');
          await this.loadAll();
        }
      },
```

- [ ] **Step 3: `KanbanView.data()` 移除分類管理相關 state**

現在（`project-detail.js:339-352`）：

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

改成：

```js
    data() {
      return {
        columns: [
          { status: 'NOT_STARTED', label: '未開始' },
          { status: 'IN_PROGRESS', label: '進行中' },
          { status: 'DONE', label: '已完成' },
        ],
        dragging: null, dragOverCol: null, dragIndex: 0,
      };
    },
```

- [ ] **Step 4: `KanbanView.methods` 刪除搬走的 4 個方法**

在 `project-detail.js:425-484`，把 `startEditCategoryName`／`commitCategoryName`／`onCategoryDragStart`／`onCategoryDrop` 這整段（從 `startEditCategoryName(category) {` 開始到 `onCategoryDrop` 方法結尾的 `},` 為止，Step 2 貼過去的那一大段文字）從 `KanbanView.methods` 裡刪掉。刪除後 `sendMove` 方法（`project-detail.js:403-424`）的結尾 `},` 應該直接接到 `methods` 區塊結尾的 `},`（`KanbanView.methods` 現在只剩 `tasksIn`/`isOverdue`/`onDragStart`/`onColDragOver`/`onCardDragOver`/`onDrop`/`sendMove`）。

- [ ] **Step 5: `KanbanView` template 移除分類管理面板**

現在（`project-detail.js:489-552`）：

```html
    template: `
      <div>
        <div class="kanban-toolbar" v-if="canWrite">
          <button class="btn btn-primary" @click="openCreate">新增任務</button>
          <span class="preset-picker-anchor">
            <button class="btn" @click="categoryPanelOpen = !categoryPanelOpen">
              分類管理 {{ categoryPanelOpen ? '▴' : '▾' }}
            </button>
          </span>
        </div>
        <div v-if="categoryPanelOpen" class="category-panel">
          <div v-for="stage in categoryTree" :key="stage.id" class="category-row-group">
            <div class="category-row"
                 :draggable="canWrite"
                 @dragstart="onCategoryDragStart(stage, $event)"
                 @dragover.prevent
                 @drop="onCategoryDrop(stage)">
              <span class="category-handle">⠿</span>
              <span v-if="editingCategoryId !== stage.id" class="category-name" @dblclick="startEditCategoryName(stage)">{{ stage.name }}</span>
              <input v-else class="category-name-input" v-model="categoryNameDraft"
                     @blur="commitCategoryName(stage)" @keyup.enter="commitCategoryName(stage)" @keyup.escape="editingCategoryId = null" />
              <span class="category-row-actions" v-if="canWrite">
                <span class="preset-picker-anchor">
                  <button class="btn btn-sm" @click="openPresetPicker(stage.id)">+子類別</button>
                  <div v-if="presetPicker && presetPicker.parentCategoryId === stage.id" class="preset-picker-popover">
                    <p>選擇子類別選單項目</p>
                    <ul>
                      <li v-for="p in categoryPresets" :key="p.id">
                        <button class="btn btn-sm" @click="createCategoryFromPreset(p.id)">{{ p.name }}</button>
                      </li>
                    </ul>
                    <button class="btn btn-sm" @click="closePresetPicker">取消</button>
                  </div>
                </span>
                <button class="btn btn-sm btn-danger" @click="deleteCategory(stage)">刪除</button>
              </span>
            </div>
            <div v-for="cat in stage.children" :key="cat.id" class="category-row category-row-child"
                 :draggable="canWrite"
                 @dragstart="onCategoryDragStart(cat, $event)"
                 @dragover.prevent
                 @drop="onCategoryDrop(cat)">
              <span class="category-handle">⠿</span>
              <span v-if="editingCategoryId !== cat.id" class="category-name" @dblclick="startEditCategoryName(cat)">{{ cat.name }}</span>
              <input v-else class="category-name-input" v-model="categoryNameDraft"
                     @blur="commitCategoryName(cat)" @keyup.enter="commitCategoryName(cat)" @keyup.escape="editingCategoryId = null" />
              <span class="category-row-actions" v-if="canWrite">
                <button class="btn btn-sm btn-danger" @click="deleteCategory(cat)">刪除</button>
              </span>
            </div>
          </div>
          <span class="preset-picker-anchor" v-if="canWrite">
            <button class="btn btn-sm" @click="openPresetPicker(null)">+ 新增階段</button>
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
        <p v-if="loading">載入中...</p>
```

改成：

```html
    template: `
      <div>
        <div class="kanban-toolbar" v-if="canWrite">
          <button class="btn btn-primary" @click="openCreate">新增任務</button>
        </div>
        <p v-if="loading">載入中...</p>
```

（後面 `<div v-else class="kanban">` 開始的整個看板欄位 markup 不動，只是把上面這段換掉）

- [ ] **Step 6: `app.css` 移除面板專用樣式**

現在（`app.css:84-92`）：

```css
.category-panel { background:var(--paper); border:1px solid var(--line); border-radius:8px; padding:1rem 1.25rem; margin-bottom:1.5rem; }
.category-row-group { margin-bottom:0.4rem; }
.category-row { display:flex; align-items:center; gap:0.5rem; padding:0.4rem 0.25rem; border-radius:4px; cursor:grab; }
.category-row:active { cursor:grabbing; }
.category-row-child { padding-left:1.75rem; }
.category-handle { color:var(--ink-muted); font-size:0.9rem; }
.category-name { flex:1; }
.category-name-input { flex:1; padding:0.2rem 0.4rem; border:1px solid var(--signal); border-radius:4px; }
.category-row-actions { display:flex; align-items:center; gap:0.25rem; margin-left:auto; }
```

改成（只保留 Task 4 會繼續用到的 `.category-name-input`/`.category-handle`，其餘四個只服務被移除面板的樣式刪掉）：

```css
.category-handle { color:var(--ink-muted); font-size:0.9rem; cursor:grab; }
.category-handle:active { cursor:grabbing; }
.category-name-input { flex:1; padding:0.2rem 0.4rem; border:1px solid var(--signal); border-radius:4px; }
```

- [ ] **Step 7: 啟動應用程式，手動驗證看板功能與分類管理面板已消失**

```bash
docker compose up -d
mvn spring-boot:run
```

用瀏覽器打開 `http://localhost:8080/login`，以 `leader` / `password123` 登入，進入 `project_id=1` 專案詳情頁、切到「看板」分頁：

1. 工具列應該只剩「新增任務」按鈕，沒有「分類管理」按鈕
2. 「新增任務」正常開啟 modal、建立任務正常
3. 卡片拖曳跨欄改狀態正常運作
4. 點卡片開 modal 編輯正常（含「所屬類別」下拉選單仍有 SIT / 程式開發選項——這兩個分類還在，只是沒有 UI 能管理它們了，等 Task 4 補回來）
5. console 無錯誤

Expected: 以上五項全部符合。

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "refactor: 移除看板分類管理面板，改名/拖曳排序邏輯搬進共用 mixin"
```

---

### Task 4: WBS 檢視分類管理 UI——大項啟用/停用、子類別新增/改名/刪除/排序

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js:198-291`（`categoryPresetMixin`，新增 `createCategoryWithName` 方法）
- Modify: `src/main/resources/static/js/project-detail.js:733-847`（`WbsView`：`data()`/`methods()`）
- Modify: `src/main/resources/static/js/project-detail.js:847-915`（`WbsView` template）
- Modify: `src/main/resources/static/css/app.css`（新增大項切換鈕、子類別操作按鈕的樣式）

**Interfaces:**
- Consumes：Task 3 產出的 `categoryPresetMixin` 的 `editingCategoryId`/`categoryNameDraft`/`draggingCategoryId`（state）與 `startEditCategoryName`/`commitCategoryName`/`onCategoryDragStart`/`onCategoryDrop`（methods）；Task 1 產出的 `deleteCategory`/`createCategoryFromPreset`；既有 `stagePresets`（`categoryPresetMixin` state）、`WbsView.computed.categoryTree`
- Produces：
  - `categoryPresetMixin.methods.createCategoryWithName(name, parentCategoryId)`：呼叫建立分類 API 帶 `name`（不帶 `presetId`），成功後把回傳分類插入 `this.categories`——這是本任務新增的方法，跟 `createCategoryFromPreset` 平行存在
  - `WbsView.methods.isStageActive(preset)`/`findActiveStage(preset)`/`toggleStage(preset)`：僅本元件內使用
  - `WbsView.data.subCategoryAdd`：`{ open: false, parentCategoryId: null, name: '' }`，僅本元件內使用

現在的 `WbsView` template 底部「+ 新增大項」區塊（`project-detail.js:898-909`）：

```html
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
```

這個任務會整個換掉這段，並且改動大項/子類別節點本身的 markup（`project-detail.js:869-896`）。

- [ ] **Step 1: `categoryPresetMixin.methods` 新增 `createCategoryWithName`**

在 `project-detail.js` 的 `categoryPresetMixin.methods` 裡、`createCategoryFromPreset` 方法（`project-detail.js:231-245`）之後加入：

```js
      async createCategoryWithName(name, parentCategoryId) {
        const result = await api(`/api/projects/${this.projectId}/task-categories`, {
          method: 'POST',
          body: JSON.stringify({ parentCategoryId, name, sortOrder: null }),
        });
        if (result.success) {
          this.categories.push(result.data);
        } else {
          this.showToast(result.message || '新增分類失敗');
        }
      },
```

- [ ] **Step 2: `WbsView.data()` 加入 `subCategoryAdd` 狀態**

現在（`project-detail.js:747-753`）：

```js
    data() {
      return {
        expandedState: {},
        draggingTask: null,
        dragOverCategoryId: undefined, // undefined=未拖曳中；null=懸停在「未歸類」；number=懸停在該分類節點
      };
    },
```

改成：

```js
    data() {
      return {
        expandedState: {},
        draggingTask: null,
        dragOverCategoryId: undefined, // undefined=未拖曳中；null=懸停在「未歸類」；number=懸停在該分類節點
        subCategoryAdd: { open: false, parentCategoryId: null, name: '' },
      };
    },
```

- [ ] **Step 3: `WbsView.methods` 新增大項啟用/停用與子類別新增的方法**

在 `project-detail.js` 的 `WbsView.methods` 裡（`project-detail.js:759` 開始的區塊）、`stageIds` 方法之後加入：

```js
      findActiveStage(preset) {
        return this.categoryTree.find(s => s.name === preset.name) || null;
      },
      isStageActive(preset) {
        return this.findActiveStage(preset) !== null;
      },
      async toggleStage(preset) {
        const existing = this.findActiveStage(preset);
        if (existing) {
          await this.deleteCategory(existing);
        } else {
          await this.createCategoryFromPreset(preset.id, null);
        }
      },
      openSubCategoryAdd(parentCategoryId) {
        this.subCategoryAdd = { open: true, parentCategoryId, name: '' };
        this.$nextTick(() => {
          if (this.$refs.subCategoryAddInput) this.$refs.subCategoryAddInput.focus();
        });
      },
      closeSubCategoryAdd() {
        this.subCategoryAdd = { open: false, parentCategoryId: null, name: '' };
      },
      async submitSubCategoryAdd() {
        const name = this.subCategoryAdd.name.trim();
        if (!name) {
          this.showToast('名稱不可為空');
          return;
        }
        await this.createCategoryWithName(name, this.subCategoryAdd.parentCategoryId);
        this.subCategoryAdd = { open: false, parentCategoryId: null, name: '' };
      },
```

- [ ] **Step 4: `WbsView` template——大項與子類別節點加上改名/刪除/排序/新增子項**

現在（`project-detail.js:869-896`，大項與子類別節點的 markup）：

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
```

改成：

```html
          <div v-for="stage in categoryTree" :key="stage.id" class="wbs-node"
               :class="{ 'drag-over': draggingTask && dragOverCategoryId === stage.id }"
               @dragover.prevent="dragOverCategoryId = stage.id" @drop="onCategoryNodeDrop(stage.id)">
            <div class="wbs-node-header"
                 @click="toggleExpanded(stage.id)"
                 @dragover.prevent.stop="dragOverCategoryId = stage.id" @drop.stop="onCategoryDrop(stage)">
              <span v-if="canWrite" class="category-handle" draggable="true"
                    @dragstart.stop="onCategoryDragStart(stage, $event)">⠿</span>
              <span class="wbs-node-toggle">{{ isExpanded(stage.id) ? '▾' : '▸' }}</span>
              <span v-if="editingCategoryId !== stage.id" class="wbs-node-name" @click.stop @dblclick="startEditCategoryName(stage)">{{ stage.name }} ({{ taskCount(stageIds(stage)) }})</span>
              <input v-else class="category-name-input" v-model="categoryNameDraft" @click.stop
                     @blur="commitCategoryName(stage)" @keyup.enter="commitCategoryName(stage)" @keyup.escape="editingCategoryId = null" />
              <span class="wbs-node-summary">{{ completionLabel(stageIds(stage)) }}</span>
              <button v-if="canWrite" class="btn btn-sm" @click.stop="openSubCategoryAdd(stage.id)">+ 新增子項</button>
            </div>
            <div v-if="subCategoryAdd.open && subCategoryAdd.parentCategoryId === stage.id" class="wbs-quick-add-row">
              <input ref="subCategoryAddInput" v-model="subCategoryAdd.name" class="wbs-quick-add-input"
                     placeholder="子類別名稱" @keyup.enter="submitSubCategoryAdd" @keyup.esc="closeSubCategoryAdd" />
              <button class="btn btn-sm btn-primary" @click="submitSubCategoryAdd">新增</button>
              <button class="btn btn-sm" @click="closeSubCategoryAdd">取消</button>
            </div>
            <div v-if="isExpanded(stage.id)" class="wbs-node-body">
              <wbs-task-row v-for="t in directTasks(stage.id)" :key="t.id" :task="t" :can-write="canWrite"
                            @dragstart="onTaskDragStart(t, $event)"
                            @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
              <div v-for="child in stage.children" :key="child.id" class="wbs-node wbs-node-child"
                   :class="{ 'drag-over': draggingTask && dragOverCategoryId === child.id }"
                   @dragover.prevent.stop="dragOverCategoryId = child.id" @drop.stop="onCategoryNodeDrop(child.id)">
                <div class="wbs-node-header"
                     @click="toggleExpanded(child.id)"
                     @dragover.prevent.stop="dragOverCategoryId = child.id" @drop.stop="onCategoryDrop(child)">
                  <span v-if="canWrite" class="category-handle" draggable="true"
                        @dragstart.stop="onCategoryDragStart(child, $event)">⠿</span>
                  <span class="wbs-node-toggle">{{ isExpanded(child.id) ? '▾' : '▸' }}</span>
                  <span v-if="editingCategoryId !== child.id" class="wbs-node-name" @click.stop @dblclick="startEditCategoryName(child)">{{ child.name }} ({{ taskCount([child.id]) }})</span>
                  <input v-else class="category-name-input" v-model="categoryNameDraft" @click.stop
                         @blur="commitCategoryName(child)" @keyup.enter="commitCategoryName(child)" @keyup.escape="editingCategoryId = null" />
                  <span class="wbs-node-summary">{{ completionLabel([child.id]) }}</span>
                  <button v-if="canWrite" class="btn btn-sm btn-danger" @click.stop="deleteCategory(child)">刪除</button>
                </div>
                <div v-if="isExpanded(child.id)" class="wbs-task-list">
                  <wbs-task-row v-for="t in directTasks(child.id)" :key="t.id" :task="t" :can-write="canWrite"
                                @dragstart="onTaskDragStart(t, $event)"
                                @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
                </div>
              </div>
            </div>
          </div>
```

重點說明給實作者：
- `.wbs-node-header` 上的 `@dragover.prevent.stop`/`@drop.stop="onCategoryDrop(...)"` 是「拖曳分類節點本身來排序」用的，跟外層 `.wbs-node`/`.wbs-node-child` 上原本就有的 `@dragover`/`@drop="onCategoryNodeDrop(...)"`（「拖曳任務卡片進這個分類」用的）是兩組獨立機制——`onCategoryDrop` 讀 `draggingCategoryId`、`onCategoryNodeDrop` 讀 `draggingTask`，兩者互不干擾，但header 這層還是要加 `.stop`，否則分類拖曳的 dragover 事件會冒泡到外層，讓外層誤判「有任務正拖在上面」而顯示不該出現的 drag-over 外框
- 拖曳分類節點排序的「抓取點」限定在 `.category-handle`（⠿ 圖示）本身，不是整個標題列——標題列還要負責點擊展開/收合、雙擊改名，做成整列可拖曳容易互相干擾
- 分類名稱文字（`.wbs-node-name`）上的 `@click.stop` 是刻意的：這個 span 在 `.wbs-node-header` 裡面，`.wbs-node-header` 有 `@click="toggleExpanded(...)"`；如果不擋掉，雙擊改名時會先觸發兩次單擊、把節點展開又收合，`@click.stop` 讓「點名稱文字」不觸發展開/收合（要展開/收合可以點標題列其他地方，例如箭頭或右側統計文字），只保留雙擊進入改名模式

- [ ] **Step 5: `WbsView` template——把「+ 新增大項」換成啟用/停用切換鈕列**

現在（`project-detail.js:898-909`，內容同本任務開頭列出的區塊）換成：

```html
          <div v-if="canWrite" class="stage-toggle-row">
            <button v-for="p in stagePresets" :key="p.id"
                    class="btn btn-sm stage-toggle-btn" :class="{ active: isStageActive(p) }"
                    @click="toggleStage(p)">
              {{ p.name }} · {{ isStageActive(p) ? '已啟用' : '啟用' }}
            </button>
          </div>
```

- [ ] **Step 6: `WbsView.mounted()` 補載入 stage presets**

現在（`project-detail.js:844-846`）：

```js
    mounted() {
      this.loadAll();
    },
```

改成：

```js
    mounted() {
      this.loadAll();
      this.loadCategoryPresets();
    },
```

- [ ] **Step 7: `app.css` 新增樣式**

在 `.wbs-node-child` 規則（`app.css:111` 附近）之後加入：

```css
.stage-toggle-row { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-top: 0.75rem; }
.stage-toggle-btn { background: var(--paper); color: var(--ink-muted); border-color: var(--line); }
.stage-toggle-btn.active { background: var(--signal); color: var(--paper); border-color: var(--signal); }
.stage-toggle-btn.active:hover { filter: brightness(0.9); }
.wbs-quick-add-row { display: flex; gap: 0.5rem; align-items: center; margin: 0.4rem 0 0.4rem 1.5rem; }
.wbs-quick-add-input { flex: 1; max-width: 320px; padding: 0.4rem 0.6rem; border: 1px solid var(--line); border-radius: 4px; font-size: 0.9rem; }
```

（`.wbs-quick-add-row`/`.wbs-quick-add-input` 這兩條 Task 5 也會用到，這裡先加好）

- [ ] **Step 8: 啟動應用程式，手動驗證**

```bash
mvn spring-boot:run
```

用瀏覽器打開 `http://localhost:8080/login`，以 `leader` / `password123` 登入，進入 `project_id=1` 專案詳情頁、切到「WBS 檢視」分頁：

1. 樹狀結構應該看到「SIT」大項節點（種子資料既有），其下「程式開發」子類別節點
2. SIT 節點標題列右側有「+ 新增子項」按鈕，點擊出現輸入框，輸入「測試子類別」按 Enter → 樹狀結構立即多一個子類別節點，不用重新整理頁面
3. 雙擊「程式開發」文字 → 進入改名模式（文字變成輸入框），改成「後端開發」按 Enter → 節點名稱更新；重新整理頁面後名稱仍是「後端開發」（驗證真的寫進後端）
4. 雙擊節點名稱進入改名模式時，節點不應該被意外展開或收合（改名操作不影響展開狀態）
5. 用滑鼠拖曳「程式開發」（或改名後的「後端開發」）節點的「⠿」圖示，拖到步驟 2 建立的「測試子類別」上方 → 兩者順序互換，重新整理頁面後順序保持
6. 「測試子類別」節點右側有「刪除」按鈕，點擊 → 跳出確認框 → 確認後節點消失
7. 樹狀結構底部應該看到一排啟用/停用切換鈕，「SIT · 已啟用」是實心樣式，其他 STAGE 選單項目（若有 DEV/UAT/PROD）是 outline 樣式「· 啟用」
8. 點一個 outline 樣式的按鈕 → 立即多一個大項節點、按鈕變實心；再點一次該按鈕（此時已啟用）→ 跳確認框 → 確認後該大項節點消失、按鈕變回 outline
9. 拖曳一張任務卡片到某個分類節點上（既有的「拖曳任務改分類」功能）仍然正常運作，不受這次改動影響——這是驗證新加的分類節點拖曳排序（Step 5 的 `.stop`）沒有把任務拖放的事件擋掉
10. console 無錯誤

Expected: 以上十項全部符合。

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: WBS 檢視補齊分類管理——大項啟用/停用、子類別新增/改名/刪除/排序"
```

---

### Task 5: 快速新增任務——「未歸類」與每個子類別節點

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js:733-847`（`WbsView`：`data()`/`methods()`）
- Modify: `src/main/resources/static/js/project-detail.js:847-915`（`WbsView` template）
- Modify: `src/main/resources/static/css/app.css`（`.wbs-quick-add-btn`）

**Interfaces:**
- Consumes：既有 `api()` helper、`this.tasks`（`taskBoardMixin`）、`this.showToast`（`toastMixin`）
- Produces：`WbsView.methods.openQuickAdd(categoryId)`/`closeQuickAdd()`/`submitQuickAdd()`——`categoryId` 為 `null` 代表未歸類、否則是子類別 id；`WbsView.data.quickAdd`：`{ open: false, categoryId: null, title: '' }`；不供其他任務使用

- [ ] **Step 1: `WbsView.data()` 加入 `quickAdd` 狀態**

在 Task 4 Step 2 已經加過 `subCategoryAdd` 的同一個 `data()`（`project-detail.js:747-754` 附近，實際行號依 Task 4 完成後為準）裡，補上：

```js
        quickAdd: { open: false, categoryId: null, title: '' },
```

（放在 `subCategoryAdd: { open: false, parentCategoryId: null, name: '' },` 這行之後即可）

- [ ] **Step 2: `WbsView.methods` 新增快速新增任務的方法**

在 `WbsView.methods` 裡（Task 4 Step 3 加的 `submitSubCategoryAdd` 之後）加入：

```js
      openQuickAdd(categoryId) {
        this.quickAdd = { open: true, categoryId, title: '' };
        this.$nextTick(() => {
          if (this.$refs.quickAddInput) this.$refs.quickAddInput.focus();
        });
      },
      closeQuickAdd() {
        this.quickAdd = { open: false, categoryId: null, title: '' };
      },
      async submitQuickAdd() {
        const title = this.quickAdd.title.trim();
        if (!title) {
          this.showToast('標題不可為空');
          return;
        }
        const result = await api(`/api/projects/${this.projectId}/tasks`, {
          method: 'POST',
          body: JSON.stringify({ categoryId: this.quickAdd.categoryId, title, description: null }),
        });
        if (result.success) {
          this.tasks.push(result.data);
          this.quickAdd = { open: false, categoryId: null, title: '' };
        } else {
          this.showToast(result.message || '新增失敗');
        }
      },
```

- [ ] **Step 3: 「未歸類」節點加上「+ 新增」按鈕與輸入框**

現在（`project-detail.js:854-867`，Task 4 沒有動過這段，行號應該不變）：

```html
          <div class="wbs-node"
               :class="{ 'drag-over': draggingTask && dragOverCategoryId === null }"
               @dragover.prevent="dragOverCategoryId = null" @drop="onCategoryNodeDrop(null)">
            <div class="wbs-node-header" @click="toggleExpanded('unassigned')">
              <span class="wbs-node-toggle">{{ isExpanded('unassigned') ? '▾' : '▸' }}</span>
              <span class="wbs-node-name">未歸類 ({{ taskCount([null]) }})</span>
              <span class="wbs-node-summary">{{ completionLabel([null]) }}</span>
            </div>
            <div v-if="isExpanded('unassigned')" class="wbs-task-list">
              <wbs-task-row v-for="t in directTasks(null)" :key="t.id" :task="t" :can-write="canWrite"
                            @dragstart="onTaskDragStart(t, $event)"
                            @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
            </div>
          </div>
```

改成：

```html
          <div class="wbs-node"
               :class="{ 'drag-over': draggingTask && dragOverCategoryId === null }"
               @dragover.prevent="dragOverCategoryId = null" @drop="onCategoryNodeDrop(null)">
            <div class="wbs-node-header" @click="toggleExpanded('unassigned')">
              <span class="wbs-node-toggle">{{ isExpanded('unassigned') ? '▾' : '▸' }}</span>
              <span class="wbs-node-name">未歸類 ({{ taskCount([null]) }})</span>
              <span class="wbs-node-summary">{{ completionLabel([null]) }}</span>
              <button v-if="canWrite" class="btn btn-sm wbs-quick-add-btn" @click.stop="openQuickAdd(null)">+ 新增</button>
            </div>
            <div v-if="quickAdd.open && quickAdd.categoryId === null" class="wbs-quick-add-row">
              <input ref="quickAddInput" v-model="quickAdd.title" class="wbs-quick-add-input"
                     placeholder="任務名稱" @keyup.enter="submitQuickAdd" @keyup.esc="closeQuickAdd" />
              <button class="btn btn-sm btn-primary" @click="submitQuickAdd">新增</button>
              <button class="btn btn-sm" @click="closeQuickAdd">取消</button>
            </div>
            <div v-if="isExpanded('unassigned')" class="wbs-task-list">
              <wbs-task-row v-for="t in directTasks(null)" :key="t.id" :task="t" :can-write="canWrite"
                            @dragstart="onTaskDragStart(t, $event)"
                            @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
            </div>
          </div>
```

- [ ] **Step 4: 子類別節點加上「+ 新增」按鈕與輸入框**

找到子類別節點 markup（`.wbs-node-child` 裡的 `.wbs-node-header`；這段內容已經被 Task 4 的兩輪修復動過——`.wbs-node-header` 的 `@dragover`/`@drop` 已經拿掉 `.stop`、`.category-handle` 已經加了 `@dragend="draggingCategoryId = null"`——下面的「現在」區塊就是修復後的真實現狀，標題列右側目前只有一個「刪除」按鈕），在「刪除」按鈕**之前**加一個「+ 新增」按鈕，並在 `.wbs-node-header` 結束的 `</div>` 之後、`isExpanded(child.id)` 的任務清單 `<div>` 之前，插入 quick-add 輸入框。子類別節點標題列這段現在應該長這樣：

```html
                <div class="wbs-node-header"
                     @click="toggleExpanded(child.id)"
                     @dragover.prevent="dragOverCategoryId = child.id" @drop="onCategoryDrop(child)">
                  <span v-if="canWrite" class="category-handle" draggable="true"
                        @dragstart.stop="onCategoryDragStart(child, $event)"
                        @dragend="draggingCategoryId = null">⠿</span>
                  <span class="wbs-node-toggle">{{ isExpanded(child.id) ? '▾' : '▸' }}</span>
                  <span v-if="editingCategoryId !== child.id" class="wbs-node-name" @click.stop @dblclick="startEditCategoryName(child)">{{ child.name }} ({{ taskCount([child.id]) }})</span>
                  <input v-else class="category-name-input" v-model="categoryNameDraft" @click.stop
                         @blur="commitCategoryName(child)" @keyup.enter="commitCategoryName(child)" @keyup.escape="editingCategoryId = null" />
                  <span class="wbs-node-summary">{{ completionLabel([child.id]) }}</span>
                  <button v-if="canWrite" class="btn btn-sm btn-danger" @click.stop="deleteCategory(child)">刪除</button>
                </div>
                <div v-if="isExpanded(child.id)" class="wbs-task-list">
```

改成：

```html
                <div class="wbs-node-header"
                     @click="toggleExpanded(child.id)"
                     @dragover.prevent="dragOverCategoryId = child.id" @drop="onCategoryDrop(child)">
                  <span v-if="canWrite" class="category-handle" draggable="true"
                        @dragstart.stop="onCategoryDragStart(child, $event)"
                        @dragend="draggingCategoryId = null">⠿</span>
                  <span class="wbs-node-toggle">{{ isExpanded(child.id) ? '▾' : '▸' }}</span>
                  <span v-if="editingCategoryId !== child.id" class="wbs-node-name" @click.stop @dblclick="startEditCategoryName(child)">{{ child.name }} ({{ taskCount([child.id]) }})</span>
                  <input v-else class="category-name-input" v-model="categoryNameDraft" @click.stop
                         @blur="commitCategoryName(child)" @keyup.enter="commitCategoryName(child)" @keyup.escape="editingCategoryId = null" />
                  <span class="wbs-node-summary">{{ completionLabel([child.id]) }}</span>
                  <button v-if="canWrite" class="btn btn-sm wbs-quick-add-btn" @click.stop="openQuickAdd(child.id)">+ 新增</button>
                  <button v-if="canWrite" class="btn btn-sm btn-danger" @click.stop="deleteCategory(child)">刪除</button>
                </div>
                <div v-if="quickAdd.open && quickAdd.categoryId === child.id" class="wbs-quick-add-row">
                  <input ref="quickAddInput" v-model="quickAdd.title" class="wbs-quick-add-input"
                         placeholder="任務名稱" @keyup.enter="submitQuickAdd" @keyup.esc="closeQuickAdd" />
                  <button class="btn btn-sm btn-primary" @click="submitQuickAdd">新增</button>
                  <button class="btn btn-sm" @click="closeQuickAdd">取消</button>
                </div>
                <div v-if="isExpanded(child.id)" class="wbs-task-list">
```

- [ ] **Step 5: `app.css` 新增 `.wbs-quick-add-btn`**

在 Task 4 Step 7 加的 `.wbs-quick-add-input` 規則之後加入：

```css
.wbs-quick-add-btn { margin-left: auto; }
```

- [ ] **Step 6: 啟動應用程式，手動驗證**

```bash
mvn spring-boot:run
```

用瀏覽器打開 `http://localhost:8080/login`，以 `leader` / `password123` 登入，進入 `project_id=1` 專案詳情頁、切到「WBS 檢視」分頁：

1. 「未歸類」節點標題列右側有「+ 新增」按鈕；「程式開發」（或 Task 4 測試時改名後的名稱）子類別節點標題列右側，「刪除」按鈕左邊也有「+ 新增」按鈕
2. 點「未歸類」的「+ 新增」，直接按 Enter（空白）→ 「標題不可為空」toast，輸入框不關閉
3. 輸入「未歸類測試任務」按 Enter → 輸入框收起，「未歸類」任務數 +1、清單出現這筆任務，不用重新整理頁面
4. 點子類別節點的「+ 新增」，輸入「子類別測試任務」按 Enter → 該子類別任務數 +1、清單出現這筆任務；點開這筆任務的 modal，確認「所屬類別」正確是這個子類別（不是未歸類）
5. 點「+ 新增」按鈕時，節點不應該被意外展開/收合或觸發改名（`.click.stop` 生效）
6. console 無錯誤

Expected: 以上六項全部符合。

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: WBS 檢視「未歸類」與子類別節點加上快速新增任務"
```

---

### Task 6: 任務狀態快速循環按鈕

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js:706-731`（`WbsTaskRow`）
- Modify: `src/main/resources/static/js/project-detail.js:733-847`（`WbsView.methods`，新增 `cycleTaskStatus`）
- Modify: `src/main/resources/static/js/project-detail.js:847-915`（`WbsView` template，三處 `<wbs-task-row>` 都要加監聽）
- Modify: `src/main/resources/static/css/app.css`（`.wbs-task-status-btn`）

**Interfaces:**
- Consumes：既有 `queueTaskWrite`（`taskBoardMixin`，序列化同一任務的連續寫入）、既有 `/move` 端點
- Produces：無其他任務依賴此任務的產出，這是最後一個任務

現在的 `WbsTaskRow`（`project-detail.js:706-731`）：

```js
  const WbsTaskRow = defineComponent({
    name: 'WbsTaskRow',
    props: {
      task: { type: Object, required: true },
      canWrite: { type: Boolean, default: false },
    },
    emits: ['dragstart', 'dragend', 'open'],
    methods: {
      isOverdue(t) {
        return isOverdueDate(t);
      },
      statusLabel(status) {
        return STATUS_LABELS[status];
      },
    },
    template: `
      <div class="wbs-task-row" :class="['priority-' + (task.priority || 'NONE')]"
           :draggable="canWrite" @dragstart="$emit('dragstart', $event)"
           @dragend="$emit('dragend')" @click="$emit('open')">
        <span class="wbs-task-title">{{ task.title }}</span>
        <span class="wbs-task-status">{{ statusLabel(task.status) }}</span>
        <span class="wbs-task-assignee">{{ task.assigneeDisplayName || '--' }}</span>
        <span class="wbs-task-due" :class="{ overdue: isOverdue(task) }">{{ task.dueDate || '--' }}</span>
      </div>
    `,
  });
```

- [ ] **Step 1: `WbsTaskRow` 加上 `cycle-status` emit 與狀態按鈕**

改成：

```js
  const WbsTaskRow = defineComponent({
    name: 'WbsTaskRow',
    props: {
      task: { type: Object, required: true },
      canWrite: { type: Boolean, default: false },
    },
    emits: ['dragstart', 'dragend', 'open', 'cycle-status'],
    methods: {
      isOverdue(t) {
        return isOverdueDate(t);
      },
      statusLabel(status) {
        return STATUS_LABELS[status];
      },
    },
    template: `
      <div class="wbs-task-row" :class="['priority-' + (task.priority || 'NONE')]"
           :draggable="canWrite" @dragstart="$emit('dragstart', $event)"
           @dragend="$emit('dragend')" @click="$emit('open')">
        <span class="wbs-task-title">{{ task.title }}</span>
        <button v-if="canWrite" class="btn btn-sm wbs-task-status-btn" @click.stop="$emit('cycle-status')">{{ statusLabel(task.status) }}</button>
        <span v-else class="wbs-task-status">{{ statusLabel(task.status) }}</span>
        <span class="wbs-task-assignee">{{ task.assigneeDisplayName || '--' }}</span>
        <span class="wbs-task-due" :class="{ overdue: isOverdue(task) }">{{ task.dueDate || '--' }}</span>
      </div>
    `,
  });
```

（唯讀時（`canWrite` 為 false）維持原本純文字顯示，不給按鈕；`@click.stop` 避免點狀態按鈕時連帶觸發整列的 `@click="$emit('open')"` 開 modal）

- [ ] **Step 2: `WbsView.methods` 新增 `cycleTaskStatus`**

在 `WbsView.methods` 裡（Task 5 加的 `submitQuickAdd` 之後）加入：

```js
      async cycleTaskStatus(task) {
        const order = ['NOT_STARTED', 'IN_PROGRESS', 'DONE'];
        const nextStatus = order[(order.indexOf(task.status) + 1) % order.length];
        const prevStatus = task.status;
        const targetIndex = this.tasks.filter(t => t.id !== task.id && t.status === nextStatus).length;
        task.status = nextStatus;
        await this.queueTaskWrite(task.id, async () => {
          const result = await api(`/api/projects/${this.projectId}/tasks/${task.id}/move`, {
            method: 'PATCH', body: JSON.stringify({ status: nextStatus, sortOrder: targetIndex }),
          });
          if (!result.success) {
            if (task.status === nextStatus) task.status = prevStatus;
            this.showToast(result.message || '狀態切換失敗');
          }
        });
      },
```

- [ ] **Step 3: `WbsView` template——三處 `<wbs-task-row>` 都加上 `@cycle-status` 監聽**

`project-detail.js` 的 `WbsView` template 裡有三處 `<wbs-task-row v-for="t in directTasks(...)" ...>`（未歸類節點一處、大項節點下的直屬任務一處、子類別節點裡一處），每一處現在都是：

```html
              <wbs-task-row v-for="t in directTasks(...)" :key="t.id" :task="t" :can-write="canWrite"
                            @dragstart="onTaskDragStart(t, $event)"
                            @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
```

三處都在結尾加上 `@cycle-status="cycleTaskStatus(t)"`：

```html
              <wbs-task-row v-for="t in directTasks(...)" :key="t.id" :task="t" :can-write="canWrite"
                            @dragstart="onTaskDragStart(t, $event)"
                            @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)"
                            @cycle-status="cycleTaskStatus(t)" />
```

（`directTasks(...)` 裡的參數三處不同，分別是 `directTasks(null)`、`directTasks(stage.id)`、`directTasks(child.id)`，不要弄混，只改屬性列表、不要動 `v-for` 那段）

- [ ] **Step 4: `app.css` 新增狀態按鈕樣式**

在 `.wbs-task-row.priority-LOW` 規則之後加入：

```css
.wbs-task-status-btn { font-family: var(--font-mono); font-size: 0.8rem; padding: 0.2rem 0.5rem; }
```

- [ ] **Step 5: 啟動應用程式，手動驗證**

```bash
mvn spring-boot:run
```

用瀏覽器打開 `http://localhost:8080/login`，以 `leader` / `password123` 登入，進入 `project_id=1` 專案詳情頁、切到「WBS 檢視」分頁：

1. 任一任務列右側的狀態文字現在是一個按鈕
2. 點擊三次同一顆按鈕，狀態應該依序「未開始→進行中→已完成→未開始」（第三次點擊繞回未開始）
3. 點狀態按鈕不應該觸發任務列的 `@click="openEdit"` 開 modal（`.stop` 生效）
4. 切到「看板」分頁重新整理頁面，確認這筆任務真的移到對應的欄位（驗證有寫進後端，不是只改本地畫面）
5. 用一個 `canWrite` 為 false 的情境（例如封存後的專案，或非本專案成員的角色）確認狀態欄位顯示純文字、沒有按鈕
6. console 無錯誤

Expected: 以上六項全部符合。

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: WBS 檢視任務列加上狀態快速循環按鈕"
```

---

## 完成後建議動作（不算在計畫任務內，執行者提醒使用者即可）

- 提醒使用者可執行 `/sync-docs` 同步 `docs/dev.md` 與 `README.md`
- 提醒使用者這次改動大幅修改了專案 `CLAUDE.md`「前端模式」章節記載的既有設計原則（看板分類管理面板已移除、WBS 檢視現在是分類操作的唯一入口），建議一併更新 `CLAUDE.md`，避免文件與實作不一致
