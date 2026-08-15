(function () {
  const { createApp, defineComponent } = Vue;

  const el = document.getElementById('detail-app');
  const projectId = Number(el.dataset.projectId);
  const canWrite = el.dataset.canWrite === 'true';
  const sectionId = el.dataset.sectionId ? Number(el.dataset.sectionId) : null;

  async function api(url, options = {}) {
    // fetch 失敗（斷線）或伺服器回傳非 JSON（如 CSRF 過期時的 HTML 錯誤頁）都會在此拋出例外；
    // 若不攔截，樂觀更新永遠不會回滾、也不會跳 toast，使用者會誤以為變更已儲存。
    // 注意：後端錯誤（400/403/404）本來就會回傳含 message 的 JSON（GlobalExceptionHandler），
    // 所以這裡不能用 res.ok 短路，仍要嘗試解析 JSON，只有解析本身失敗才視為網路錯誤。
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
  function isOverdueDate(t) {
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    return !!t.dueDate && t.status !== 'DONE' && t.dueDate < today;
  }

  // 大類（parentCategoryId 為 null）→ 子類 兩層分組，KanbanView 與 WbsView 共用
  function buildCategoryTree(categories) {
    const stages = categories
      .filter(c => c.parentCategoryId == null)
      .sort((a, b) => a.sortOrder - b.sortOrder);
    return stages.map(stage => ({
      ...stage,
      children: categories
        .filter(c => c.parentCategoryId === stage.id)
        .sort((a, b) => a.sortOrder - b.sortOrder),
    }));
  }

  // 依到期日升冪排序，無到期日排最後；到期日相同（含都無到期日）依 id 升冪，穩定排序不需額外欄位。
  // AssignmentView 與 WbsView 共用同一條規則
  function sortByDueDate(list) {
    return list.sort((a, b) => {
      if (a.dueDate == null && b.dueDate == null) return a.id - b.id;
      if (a.dueDate == null) return 1;
      if (b.dueDate == null) return -1;
      if (a.dueDate !== b.dueDate) return a.dueDate < b.dueDate ? -1 : 1;
      return a.id - b.id;
    });
  }

  const STATUS_LABELS = { NOT_STARTED: '未開始', IN_PROGRESS: '進行中', DONE: '已完成' };

  // KanbanView 與 AssignmentView 共用的 toast 提示邏輯，抽成 mixin 避免兩處維護同一份計時器邏輯
  const toastMixin = {
    data() {
      return { toastMessage: '', toastTimer: null };
    },
    methods: {
      showToast(message) {
        this.toastMessage = message;
        clearTimeout(this.toastTimer);
        this.toastTimer = setTimeout(() => { this.toastMessage = ''; }, 3000);
      },
    },
  };

  // KanbanView 與 WbsView 共用：任務新增/編輯/刪除的 modal 邏輯（歸類/指派/優先度/日期），
  // 兩個分頁都需要開同一顆 modal，抽出來避免各自維護一份
  const taskModalMixin = {
    data() {
      return {
        modal: {
          open: false, taskId: null,
          form: { title: '', description: '', assigneeId: null, categoryId: null, priority: null, startDate: null, dueDate: null },
        },
      };
    },
    methods: {
      emptyForm() {
        return { title: '', description: '', assigneeId: null, categoryId: null, priority: null, startDate: null, dueDate: null };
      },
      openCreate() {
        this.modal = { open: true, taskId: null, form: this.emptyForm() };
      },
      openEdit(t) {
        this.modal = {
          open: true, taskId: t.id,
          form: {
            title: t.title, description: t.description || '', assigneeId: t.assigneeId,
            categoryId: t.categoryId, priority: t.priority, startDate: t.startDate, dueDate: t.dueDate,
          },
        };
      },
      // 建立與編輯共用：CreateRequest 只收 categoryId/title/description，
      // 指派人/優先度/日期一律等有了 taskId 後跟編輯流程共用同一段 PUT+PATCH，不重複組裝欄位邏輯
      async saveTask() {
        const form = this.modal.form;
        if (!form.title || !form.title.trim()) {
          this.showToast('標題不可為空');
          return;
        }

        let taskId = this.modal.taskId;
        if (!taskId) {
          const createResult = await api(`/api/projects/${this.projectId}/tasks`, {
            method: 'POST',
            body: JSON.stringify({ categoryId: form.categoryId, title: form.title.trim(), description: form.description || null }),
          });
          if (!createResult.success) {
            this.showToast(createResult.message || '新增失敗');
            return;
          }
          taskId = createResult.data.id;
        }

        const [updateResult, assigneeResult] = await Promise.all([
          api(`/api/projects/${this.projectId}/tasks/${taskId}`, {
            method: 'PUT',
            body: JSON.stringify({
              title: form.title.trim(), description: form.description || null,
              categoryId: form.categoryId, priority: form.priority || null,
              startDate: form.startDate || null, dueDate: form.dueDate || null,
            }),
          }),
          api(`/api/projects/${this.projectId}/tasks/${taskId}/assignee`, {
            method: 'PATCH', body: JSON.stringify({ assigneeId: form.assigneeId }),
          }),
        ]);

        if (updateResult.success && assigneeResult.success) {
          this.modal.open = false;
          await this.loadAll();
        } else {
          this.showToast(updateResult.message || assigneeResult.message || '儲存失敗');
        }
      },
      async deleteTask() {
        if (!confirm('確定刪除此任務？')) return;
        const result = await api(`/api/projects/${this.projectId}/tasks/${this.modal.taskId}`, { method: 'DELETE' });
        if (result.success) {
          this.modal.open = false;
          await this.loadAll();
        } else {
          this.showToast(result.message || '刪除失敗');
        }
      },
    },
  };

  // KanbanView 與 WbsView 共用：載入 tasks/categories/members 三份資料、loading 狀態，
  // 以及同一任務連續寫入時的序列化佇列（避免同一任務快速連續寫入時後完成者用舊快照蓋掉新資料，
  // 例如連續兩次拖曳；注意 modal 的 saveTask() 目前未走此佇列，不涵蓋拖曳與 modal 編輯併發的情境）
  const taskBoardMixin = {
    data() {
      return { tasks: [], categories: [], members: [], loading: true, taskWriteQueue: {} };
    },
    methods: {
      async loadAll() {
        this.loading = true;
        try {
          const [tasksRes, categoriesRes, membersRes] = await Promise.all([
            api(`/api/projects/${this.projectId}/tasks`),
            api(`/api/projects/${this.projectId}/task-categories`),
            api(`/api/projects/${this.projectId}/members`),
          ]);
          this.tasks = tasksRes.success ? tasksRes.data : [];
          this.categories = categoriesRes.success ? categoriesRes.data : [];
          this.members = membersRes.success ? membersRes.data : [];
          if (!tasksRes.success || !categoriesRes.success || !membersRes.success) {
            this.showToast(tasksRes.message || categoriesRes.message || membersRes.message || '載入失敗，請重新整理');
          }
        } catch (e) {
          this.showToast('載入失敗，請重新整理');
        } finally {
          this.loading = false;
        }
      },
      queueTaskWrite(taskId, task) {
        const prev = this.taskWriteQueue[taskId] || Promise.resolve();
        const next = prev.then(task, task);
        this.taskWriteQueue[taskId] = next;
        return next;
      },
    },
  };

  // KanbanView 與 WbsView 共用：任務編輯 modal 的畫面本身（表單欄位＋儲存/刪除/取消按鈕）。
  // 純展示元件，不呼叫 API——modal.form 透過 v-model 直接改 modal 這個物件（父層傳進來的同一個參照，
  // 不是重新賦值 prop 本身，Vue 不會警告），實際存檔/刪除的 API 呼叫仍由父層的 taskModalMixin 負責，
  // 這裡只負責 emit 事件通知父層
  const TaskModal = defineComponent({
    name: 'TaskModal',
    props: {
      modal: { type: Object, required: true },
      members: { type: Array, required: true },
      categories: { type: Array, required: true },
      canWrite: { type: Boolean, default: false },
    },
    emits: ['save', 'delete', 'close'],
    template: `
      <div class="modal-overlay" @click.self="$emit('close')">
        <div class="modal">
          <h3>{{ modal.taskId ? '編輯任務' : '新增任務' }}</h3>
          <div class="form-group"><label>標題</label><input v-model="modal.form.title" /></div>
          <div class="form-group"><label>描述</label><textarea v-model="modal.form.description" rows="4"></textarea></div>
          <div class="form-group">
            <label>指派人</label>
            <select v-model="modal.form.assigneeId">
              <option :value="null">未指派</option>
              <option v-for="m in members" :key="m.userId" :value="m.userId">{{ m.displayName }}</option>
            </select>
          </div>
          <div class="form-group">
            <label>所屬類別</label>
            <select v-model="modal.form.categoryId">
              <option :value="null">未歸類</option>
              <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
          <div class="form-group">
            <label>優先度</label>
            <select v-model="modal.form.priority">
              <option :value="null">未設定</option>
              <option value="HIGH">高</option>
              <option value="MEDIUM">中</option>
              <option value="LOW">低</option>
            </select>
          </div>
          <div class="form-group"><label>起始日</label><input type="date" v-model="modal.form.startDate" /></div>
          <div class="form-group"><label>到期日</label><input type="date" v-model="modal.form.dueDate" /></div>
          <div class="modal-actions">
            <button class="btn btn-primary" @click="$emit('save')" :disabled="!canWrite">儲存</button>
            <button v-if="modal.taskId && canWrite" class="btn btn-danger" @click="$emit('delete')">刪除</button>
            <button class="btn" @click="$emit('close')">取消</button>
          </div>
        </div>
      </div>
    `,
  });

  const KanbanView = defineComponent({
    name: 'KanbanView',
    mixins: [toastMixin, taskBoardMixin, taskModalMixin],
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
      sectionId: { type: Number, default: null },
    },
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
    computed: {
      categoryTree() {
        return buildCategoryTree(this.categories);
      },
    },
    methods: {
      tasksIn(status) {
        return this.tasks.filter(t => t.status === status).sort((a, b) => a.sortOrder - b.sortOrder);
      },
      isOverdue(t) {
        return isOverdueDate(t);
      },
      onDragStart(t, ev) {
        this.dragging = t;
        this.dragIndex = 0;
        ev.dataTransfer.effectAllowed = 'move';
      },
      onColDragOver(status) {
        // 卡片層級的 dragover（見 onCardDragOver）會以精準位置覆蓋這裡的預設值；
        // 拖到空欄或欄尾空白處時沒有卡片可覆蓋，需要這個預設值撐住，否則 dragIndex 會殘留上次拖曳的舊值
        this.dragOverCol = status;
        const count = this.tasksIn(status).length;
        // 卡片若原本就在本欄，本地陣列此刻仍含自己，尾端索引需扣掉自己
        this.dragIndex = (this.dragging && this.dragging.status === status)
          ? Math.max(0, count - 1) : count;
      },
      onCardDragOver(status, idx) {
        this.dragOverCol = status;
        // 同欄拖曳時 tasksIn(status) 仍含被拖曳卡片本身，往下拖（idx 大於原本位置）時
        // 目標位置要扣掉自己這一格，否則 sendMove／後端 moveTask 在移除自己後的陣列上
        // insert 到 idx，會比視覺上停在的卡片多推一格（off-by-one，往上拖與跨欄不受影響）
        if (this.dragging && this.dragging.status === status) {
          const currentIdx = this.tasksIn(status).findIndex(t => t.id === this.dragging.id);
          if (currentIdx !== -1 && idx > currentIdx) {
            idx -= 1;
          }
        }
        this.dragIndex = idx;
      },
      async onDrop(status) {
        if (!this.dragging || !this.canWrite) return;
        const taskId = this.dragging.id;
        const targetIndex = this.dragIndex;
        this.dragging = null;
        this.dragOverCol = null;
        await this.sendMove(taskId, status, targetIndex);
      },
      // 拖曳唯一走樂觀更新：本地先重新分配目標欄的 sortOrder 立即反映拖曳結果，
      // 失敗才整批 reload（而非嘗試精算回滾每個受影響任務的 sortOrder，move 一次可能牽動整欄排序，
      // 精算回滾複雜度不成比例，reload 更安全簡單）
      async sendMove(taskId, status, targetIndex) {
        const task = this.tasks.find(t => t.id === taskId);

        const targetColumn = this.tasks.filter(t => t.id !== taskId && t.status === status)
          .sort((a, b) => a.sortOrder - b.sortOrder);
        const insertAt = Math.max(0, Math.min(targetIndex, targetColumn.length));
        targetColumn.splice(insertAt, 0, task);
        task.status = status;
        targetColumn.forEach((t, i) => { t.sortOrder = i; });

        await this.queueTaskWrite(taskId, async () => {
          const result = await api(`/api/projects/${this.projectId}/tasks/${taskId}/move`, {
            method: 'PATCH', body: JSON.stringify({ status, sortOrder: targetIndex }),
          });
          if (!result.success) {
            // move 一次可能牽動整欄排序，回滾要精算所有受影響任務的 sortOrder，複雜度不成比例；
            // 直接整批 reload 從後端拿回真實狀態更簡單可靠（呼應上方設計決策 5）
            this.showToast(result.message || '移動失敗');
            await this.loadAll();
          }
        });
      },
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
          // 後端 ON DELETE SET NULL 讓被刪分類底下的任務落回未歸類；本地也要同步，
          // 否則任務卡片編輯 modal 的「所屬類別」下拉選單會因 categoryId 對不到任何選項而顯示空白，
          // 要等重新整理頁面才會變回「未歸類」
          this.tasks.forEach(t => {
            if (removedIds.includes(t.categoryId)) t.categoryId = null;
          });
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
    },
    mounted() {
      this.loadAll();
    },
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
        <div v-else class="kanban">
          <div v-for="col in columns" :key="col.status" class="kanban-col"
               :class="{ 'drag-over': dragOverCol === col.status }"
               @dragover.prevent="onColDragOver(col.status)"
               @dragleave="dragOverCol = null"
               @drop="onDrop(col.status)">
            <div class="kanban-col-header">
              <span>{{ col.label }}</span>
              <span class="kanban-col-count">{{ tasksIn(col.status).length }}</span>
            </div>
            <div v-for="(t, idx) in tasksIn(col.status)" :key="t.id"
                 class="task-card" :class="['priority-' + (t.priority || 'NONE'), { dragging: dragging === t }]"
                 :draggable="canWrite" @dragstart="onDragStart(t, $event)"
                 @dragover.prevent.stop="onCardDragOver(col.status, idx)" @click="openEdit(t)">
              <div class="task-card-title">{{ t.title }}</div>
              <div class="task-card-meta">
                <span>{{ t.assigneeDisplayName || '未指派' }}</span>
                <span class="task-due" :class="{ overdue: isOverdue(t) }" v-if="t.dueDate">{{ t.dueDate }}</span>
              </div>
            </div>
          </div>
        </div>
        <task-modal v-if="modal.open" :modal="modal" :members="members" :categories="categories" :can-write="canWrite"
                    @save="saveTask" @delete="deleteTask" @close="modal.open = false" />
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

  const AssignmentView = defineComponent({
    name: 'AssignmentView',
    mixins: [toastMixin],
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
      isOverdue(t) {
        return isOverdueDate(t);
      },
      statusLabel(status) {
        return STATUS_LABELS[status];
      },
      tasksFor(assigneeId) {
        return sortByDueDate(this.tasks.filter(t => t.assigneeId === assigneeId && (this.showDone || t.status !== 'DONE')));
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

  const WbsView = defineComponent({
    name: 'WbsView',
    mixins: [toastMixin, taskBoardMixin, taskModalMixin],
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
    },
    data() {
      return {
        expandedState: {},
        draggingTask: null,
        dragOverCategoryId: undefined, // undefined=未拖曳中；null=懸停在「未歸類」；number=懸停在該分類節點
      };
    },
    computed: {
      categoryTree() {
        return buildCategoryTree(this.categories);
      },
    },
    methods: {
      isOverdue(t) {
        return isOverdueDate(t);
      },
      statusLabel(status) {
        return STATUS_LABELS[status];
      },
      isExpanded(key) {
        return this.expandedState[key] !== false;
      },
      toggleExpanded(key) {
        this.expandedState[key] = !this.isExpanded(key);
      },
      directTasks(categoryId) {
        return sortByDueDate(this.tasks.filter(t => t.categoryId === categoryId));
      },
      stageIds(stage) {
        return [stage.id, ...stage.children.map(c => c.id)];
      },
      taskCount(ids) {
        return this.tasks.filter(t => ids.includes(t.categoryId)).length;
      },
      completionLabel(ids) {
        const list = this.tasks.filter(t => ids.includes(t.categoryId));
        if (list.length === 0) return '--';
        const done = list.filter(t => t.status === 'DONE').length;
        return `${Math.round(done / list.length * 100)}%`;
      },
      onTaskDragStart(t, ev) {
        if (!this.canWrite) return;
        this.draggingTask = t;
        ev.dataTransfer.effectAllowed = 'move';
      },
      async onCategoryNodeDrop(categoryId) {
        if (!this.draggingTask || !this.canWrite) return;
        const task = this.draggingTask;
        this.draggingTask = null;
        this.dragOverCategoryId = undefined;
        if (task.categoryId === categoryId) return;
        await this.moveTaskToCategory(task, categoryId);
      },
      // 拖曳只改 categoryId，其餘欄位原樣送出（跟 taskModalMixin 的 saveTask() 組 payload 方式相同），
      // 不新增後端端點；走 taskWriteQueue 序列化同一任務的連續寫入，避免快速連續拖曳時後完成者用舊快照蓋掉新資料
      async moveTaskToCategory(task, categoryId) {
        const prev = task.categoryId;
        task.categoryId = categoryId;
        await this.queueTaskWrite(task.id, async () => {
          const result = await api(`/api/projects/${this.projectId}/tasks/${task.id}`, {
            method: 'PUT',
            body: JSON.stringify({
              title: task.title, description: task.description || null,
              categoryId, priority: task.priority || null,
              startDate: task.startDate || null, dueDate: task.dueDate || null,
            }),
          });
          if (!result.success) {
            // 只在 categoryId 仍是這次呼叫剛設定的值時才回滾；若後續另一次呼叫已成功把它改成別的值，
            // 代表本地已是最新狀態，不可用這次失敗的舊值覆蓋掉
            if (task.categoryId === categoryId) task.categoryId = prev;
            this.showToast(result.message || '移動失敗');
          }
        });
      },
    },
    mounted() {
      this.loadAll();
    },
    template: `
      <div>
        <p v-if="loading">載入中...</p>
        <div v-else class="wbs-tree">
          <div class="wbs-node"
               :class="{ 'drag-over': draggingTask && dragOverCategoryId === null }"
               @dragover.prevent="dragOverCategoryId = null" @drop="onCategoryNodeDrop(null)">
            <div class="wbs-node-header" @click="toggleExpanded('unassigned')">
              <span class="wbs-node-toggle">{{ isExpanded('unassigned') ? '▾' : '▸' }}</span>
              <span class="wbs-node-name">未歸類 ({{ taskCount([null]) }})</span>
              <span class="wbs-node-summary">{{ completionLabel([null]) }}</span>
            </div>
            <div v-if="isExpanded('unassigned')" class="wbs-task-list">
              <div v-for="t in directTasks(null)" :key="t.id" class="wbs-task-row"
                   :class="['priority-' + (t.priority || 'NONE')]"
                   :draggable="canWrite" @dragstart="onTaskDragStart(t, $event)"
                   @dragend="draggingTask = null; dragOverCategoryId = undefined" @click="openEdit(t)">
                <span class="wbs-task-title">{{ t.title }}</span>
                <span class="wbs-task-status">{{ statusLabel(t.status) }}</span>
                <span class="wbs-task-assignee">{{ t.assigneeDisplayName || '--' }}</span>
                <span class="wbs-task-due" :class="{ overdue: isOverdue(t) }">{{ t.dueDate || '--' }}</span>
              </div>
            </div>
          </div>

          <div v-for="stage in categoryTree" :key="stage.id" class="wbs-node"
               :class="{ 'drag-over': draggingTask && dragOverCategoryId === stage.id }"
               @dragover.prevent="dragOverCategoryId = stage.id" @drop="onCategoryNodeDrop(stage.id)">
            <div class="wbs-node-header" @click="toggleExpanded(stage.id)">
              <span class="wbs-node-toggle">{{ isExpanded(stage.id) ? '▾' : '▸' }}</span>
              <span class="wbs-node-name">{{ stage.name }} ({{ taskCount(stageIds(stage)) }})</span>
              <span class="wbs-node-summary">{{ completionLabel(stageIds(stage)) }}</span>
            </div>
            <div v-if="isExpanded(stage.id)" class="wbs-node-body">
              <div v-for="t in directTasks(stage.id)" :key="t.id" class="wbs-task-row"
                   :class="['priority-' + (t.priority || 'NONE')]"
                   :draggable="canWrite" @dragstart="onTaskDragStart(t, $event)"
                   @dragend="draggingTask = null; dragOverCategoryId = undefined" @click="openEdit(t)">
                <span class="wbs-task-title">{{ t.title }}</span>
                <span class="wbs-task-status">{{ statusLabel(t.status) }}</span>
                <span class="wbs-task-assignee">{{ t.assigneeDisplayName || '--' }}</span>
                <span class="wbs-task-due" :class="{ overdue: isOverdue(t) }">{{ t.dueDate || '--' }}</span>
              </div>
              <div v-for="child in stage.children" :key="child.id" class="wbs-node wbs-node-child"
                   :class="{ 'drag-over': draggingTask && dragOverCategoryId === child.id }"
                   @dragover.prevent.stop="dragOverCategoryId = child.id" @drop.stop="onCategoryNodeDrop(child.id)">
                <div class="wbs-node-header" @click="toggleExpanded(child.id)">
                  <span class="wbs-node-toggle">{{ isExpanded(child.id) ? '▾' : '▸' }}</span>
                  <span class="wbs-node-name">{{ child.name }} ({{ taskCount([child.id]) }})</span>
                  <span class="wbs-node-summary">{{ completionLabel([child.id]) }}</span>
                </div>
                <div v-if="isExpanded(child.id)" class="wbs-task-list">
                  <div v-for="t in directTasks(child.id)" :key="t.id" class="wbs-task-row"
                       :class="['priority-' + (t.priority || 'NONE')]"
                       :draggable="canWrite" @dragstart="onTaskDragStart(t, $event)"
                       @dragend="draggingTask = null; dragOverCategoryId = undefined" @click="openEdit(t)">
                    <span class="wbs-task-title">{{ t.title }}</span>
                    <span class="wbs-task-status">{{ statusLabel(t.status) }}</span>
                    <span class="wbs-task-assignee">{{ t.assigneeDisplayName || '--' }}</span>
                    <span class="wbs-task-due" :class="{ overdue: isOverdue(t) }">{{ t.dueDate || '--' }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <task-modal v-if="modal.open" :modal="modal" :members="members" :categories="categories" :can-write="canWrite"
                    @save="saveTask" @delete="deleteTask" @close="modal.open = false" />
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

  const app = createApp({
    data() {
      return { projectId, canWrite, sectionId, activeTab: 'kanban' };
    },
    template: `
      <div>
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'wbs' }" @click="activeTab = 'wbs'">WBS 檢視</button>
        </div>
        <kanban-view v-if="activeTab === 'kanban'" :project-id="projectId" :can-write="canWrite" :section-id="sectionId" />
        <assignment-view v-else-if="activeTab === 'assignment'" :project-id="projectId" :can-write="canWrite" />
        <wbs-view v-else :project-id="projectId" :can-write="canWrite" />
      </div>
    `,
  });

  app.component('kanban-view', KanbanView);
  app.component('assignment-view', AssignmentView);
  app.component('task-modal', TaskModal);
  app.component('wbs-view', WbsView);
  app.mount('#detail-app');
})();
