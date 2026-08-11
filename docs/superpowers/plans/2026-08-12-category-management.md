# 分類管理畫面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在看板工具列加一顆「分類管理」切換按鈕，展開一個內嵌面板（非浮窗），可用拖曳在同一層內排序階段／子類別，並支援新增（從選單挑選）、原地改名、刪除。

**Architecture:** 純前端功能，全部使用既有、已測試過的後端 API（`TaskCategoryController`、`TaskCategoryPresetController`），不動後端程式碼。所有邏輯加進 `project-detail.js` 既有的 `KanbanView` 元件（跟看板卡片共用同一份 Vue instance 與 `categories` 狀態），沿用看板卡片已建立的原生 HTML5 拖曳技巧與 `api()`/`showToast` 慣例。

**Tech Stack:** Vue 3（無 build 工具，vendored UMD），與看板前端相同。

## Global Constraints

- 依 [`docs/superpowers/specs/2026-08-12-category-management-design.md`](../specs/2026-08-12-category-management-design.md) 執行。
- **不改後端**：`TaskCategoryDto.CreateRequest(parentCategoryId, presetId, sortOrder)`、`UpdateRequest(name, sortOrder)`（局部更新，欄位可各自省略）、`GET/POST/PUT/DELETE .../task-categories`、`GET /api/task-category-presets?type=&sectionId=` 全部原樣使用。
- **不用浮動視窗**：面板是頁面內容的一部分（`v-if` 切換顯示／隱藏），不是覆蓋在畫面上的 modal。
- **拖曳排序僅限同一層內**（階段之間、同一階段下的子類別之間）；跨層拖曳一律忽略，不送任何請求。
- 排序沒有批次端點：拖曳放開後，對「同層內 `sortOrder` 有變動」的每一筆分類各自送一支 `PUT`。
- 刪除失敗或排序批次更新中途失敗，不手動回滾個別欄位，直接呼叫 `loadAll()` 重新同步伺服器狀態並 `showToast`。
- 沿用既有命名慣例：新增的 CSS class 一律用字面 hex 色碼（`app.css` 目前沒有用 CSS variable）。

---

## Task 1: 分類管理面板（`project-detail.js` + `app.css`）

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: `GET/POST/PUT/DELETE /api/projects/{id}/task-categories`、`GET /api/task-category-presets?type=STAGE|CATEGORY&sectionId=`（皆為既有、已測試過的端點）
- Produces: 無新公開介面；`KanbanView` 內部狀態擴充（`categoryPanelOpen`、`stagePresets`、`categoryPresets`、`presetPicker`、`editingCategoryId`、`draggingCategoryId`）

- [ ] **Step 1: 根 Vue app 補回 `sectionId` 讀取，往下傳給 `KanbanView`**

Task 9 的看板重寫拿掉了 `sectionId` 的讀取（當時沒有地方用到）。在 `project-detail.js` 檔案開頭，把：

```javascript
const el = document.getElementById('detail-app');
const projectId = Number(el.dataset.projectId);
const canWrite = el.dataset.canWrite === 'true';
```

改成：

```javascript
const el = document.getElementById('detail-app');
const projectId = Number(el.dataset.projectId);
const canWrite = el.dataset.canWrite === 'true';
const sectionId = el.dataset.sectionId ? Number(el.dataset.sectionId) : null;
```

`KanbanView` 的 `props` 加一項：

```javascript
props: {
  projectId: { type: Number, required: true },
  canWrite: { type: Boolean, default: false },
  sectionId: { type: Number, default: null },
},
```

檔尾的根 app 定義改成：

```javascript
const app = createApp({
  data() {
    return { projectId, canWrite, sectionId };
  },
  template: `<kanban-view :project-id="projectId" :can-write="canWrite" :section-id="sectionId" />`,
});
```

- [ ] **Step 2: `KanbanView` 的 `data()` 加分類管理面板需要的狀態**

在現有 `data()` 回傳物件裡（`tasks`、`categories`、`members`、`loading` 那些既有欄位之後）加：

```javascript
categoryPanelOpen: false,
presetsLoaded: false,
stagePresets: [],
categoryPresets: [],
presetPicker: null,       // { parentCategoryId: null|number } 開啟中的選單挑選器；null 表示未開啟
editingCategoryId: null,
categoryNameDraft: '',
draggingCategoryId: null,
```

- [ ] **Step 3: `computed` 加 `categoryTree`（把扁平的 `categories` 組成兩層樹狀結構供渲染）**

在既有 `methods` 之前（或 `KanbanView` 目前沒有 `computed` 區塊的話，新增一個）加：

```javascript
computed: {
  categoryTree() {
    const stages = this.categories
      .filter(c => c.parentCategoryId == null)
      .sort((a, b) => a.sortOrder - b.sortOrder);
    return stages.map(stage => ({
      ...stage,
      children: this.categories
        .filter(c => c.parentCategoryId === stage.id)
        .sort((a, b) => a.sortOrder - b.sortOrder),
    }));
  },
},
```

- [ ] **Step 4: `methods` 加選單載入、新增、改名、刪除、拖曳排序邏輯**

在既有 `methods` 物件裡加入以下方法（緊接在 `deleteTask` 之後）：

```javascript
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
async deleteCategory(category) {
  const hasChildren = this.categories.some(c => c.parentCategoryId === category.id);
  const msg = hasChildren
    ? `確定刪除「${category.name}」？其下所有子類別將一併刪除，相關任務會變成未歸類。`
    : `確定刪除「${category.name}」？相關任務會變成未歸類。`;
  if (!confirm(msg)) return;
  const result = await api(`/api/projects/${this.projectId}/task-categories/${category.id}`, { method: 'DELETE' });
  if (result.success) {
    const removedIds = hasChildren
      ? [category.id, ...this.categories.filter(c => c.parentCategoryId === category.id).map(c => c.id)]
      : [category.id];
    this.categories = this.categories.filter(c => !removedIds.includes(c.id));
  } else {
    this.showToast(result.message || '刪除失敗');
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

- [ ] **Step 5: 模板加工具列按鈕、面板、選單挑選錨定彈出層**

把 `KanbanView` 模板裡的 `kanban-toolbar` 區塊：

```html
<div class="kanban-toolbar" v-if="canWrite">
  <button class="btn btn-primary" @click="openCreate">新增任務</button>
</div>
```

改成：

```html
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
```

（`editingCategoryId !== stage.id` 這類比對：`editingCategoryId` 初始為 `null`，跟任何真實 `id` 都不相等，邏輯上不需要額外的 `null` 判斷。）

- [ ] **Step 6: `app.css` 加分類面板樣式**

在 `app.css` 檔尾（既有 `.task-due.overdue` 規則之後）加：

```css
.category-panel { background:#fff; border:1px solid #dfe6e9; border-radius:8px; padding:1rem 1.25rem; margin-bottom:1.5rem; }
.category-row-group { margin-bottom:0.4rem; }
.category-row { display:flex; align-items:center; gap:0.5rem; padding:0.4rem 0.25rem; border-radius:4px; cursor:grab; }
.category-row:active { cursor:grabbing; }
.category-row-child { padding-left:1.75rem; }
.category-handle { color:#b2bec3; font-size:0.9rem; }
.category-name { flex:1; }
.category-name-input { flex:1; padding:0.2rem 0.4rem; border:1px solid #0984e3; border-radius:4px; }
.category-row-actions { display:flex; align-items:center; gap:0.25rem; margin-left:auto; }
.preset-picker-anchor { position:relative; display:inline-block; }
.preset-picker-popover { position:absolute; top:calc(100% + 4px); left:0; background:#fff; border:1px solid #dfe6e9; border-radius:6px; box-shadow:0 4px 12px rgba(0,0,0,0.12); z-index:300; min-width:180px; padding:0.6rem; }
.preset-picker-popover p { font-size:0.8rem; color:#636e72; margin-bottom:0.4rem; }
.preset-picker-popover ul { list-style:none; margin:0 0 0.4rem; padding:0; }
.preset-picker-popover li { margin-bottom:0.25rem; }
```

- [ ] **Step 7: `mvn test` 確認全綠（本任務不改後端，預期無影響）**

```bash
mvn test
```

Expected: BUILD SUCCESS，測試數與改動前相同（無新增/刪除任何測試）。

- [ ] **Step 8: 瀏覽器實測（無 JS 測試框架，這是本任務的主要驗證方式）**

```bash
docker compose up -d
mvn spring-boot:run
```

以 `leader`/`password123` 登入「MissionBoard 範例專案」，依序確認：
1. 看板工具列出現「分類管理」按鈕，點擊展開／收合面板
2. 面板初始應顯示種子資料已有的兩筆分類（`SIT` 階段、其下 `程式開發` 子類別）
3. 點「+ 新增階段」，錨定彈出層列出 STAGE 選單項目（`SIT`／`UAT`／`PROD`），選一個後面板即時新增該階段列
4. 在新建的階段下點「+子類別」，錨定彈出層列出 CATEGORY 選單項目，選一個後即時新增子類別列
5. 雙擊某個分類名稱，原地變成輸入框，改名後 `Enter` 確認，畫面與重新整理後皆反映新名稱
6. 拖曳同一階段下的兩個子類別互換順序，重新整理頁面後順序仍保留
7. 刪除一個有子類別的階段，確認彈出訊息文字正確（提到「其下所有子類別將一併刪除」），確認後子類別跟著消失
8. 開啟一顆原本歸類在剛刪除分類下的任務卡片，確認編輯 modal 的「所屬類別」已變回「未歸類」
9. console 面板確認無錯誤
10. 依專案 CLAUDE.md「有畫面就有截圖」規則截圖存證（面板展開狀態、改名中狀態、刪除確認訊息三張以內）

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 新增分類管理面板，支援階段/子類別的建立、改名、刪除、同層拖曳排序"
```

---

## 收尾：驗證

- [ ] Step 8 的瀏覽器實測全數通過即完成，無需額外收尾步驟（本任務範圍小，單一 task 即涵蓋完整交付）。
