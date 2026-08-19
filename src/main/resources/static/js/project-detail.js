(function () {
  const { createApp, defineComponent } = Vue;

  const el = document.getElementById('detail-app');
  const projectId = Number(el.dataset.projectId);
  const canWrite = el.dataset.canWrite === 'true';
  const canArchive = el.dataset.canArchive === 'true';
  const archived = el.dataset.archived === 'true';
  const ownerId = el.dataset.ownerId ? Number(el.dataset.ownerId) : null;
  const sectionId = el.dataset.sectionId ? Number(el.dataset.sectionId) : null;
  const projectName = el.dataset.projectName;

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

  // KanbanView 與 WbsView 共用：新增大類/子類的選單挑選器邏輯（從 task_category_presets 選單挑選後建立 task_categories）。
  // 依賴 host 元件已混入 taskBoardMixin（提供 categories 陣列）與 toastMixin（提供 showToast）
  const categoryPresetMixin = {
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
      async createCategoryFromPreset(presetId, parentCategoryId) {
        if (parentCategoryId === undefined) {
          parentCategoryId = this.presetPicker ? this.presetPicker.parentCategoryId : null;
        }
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
    mixins: [toastMixin, taskBoardMixin, taskModalMixin, categoryPresetMixin],
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
      sectionId: { type: Number, default: null },
      dataVersion: { type: Number, default: 0 },
    },
    watch: {
      dataVersion() {
        this.loadAll();
      },
    },
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
    },
    mounted() {
      this.loadAll();
    },
    template: `
      <div>
        <div class="kanban-toolbar" v-if="canWrite">
          <button class="btn btn-primary" @click="openCreate">新增任務</button>
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
      dataVersion: { type: Number, default: 0 },
    },
    watch: {
      dataVersion() {
        this.loadAll();
      },
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

  // WbsView 專用：一列任務的顯示（標題/狀態/指派人/到期日），在未歸類、階段直屬、子類三處清單重複出現，
  // 抽成元件避免同一段 markup 維護三份
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

  const WbsView = defineComponent({
    name: 'WbsView',
    mixins: [toastMixin, taskBoardMixin, taskModalMixin, categoryPresetMixin],
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
      sectionId: { type: Number, default: null },
      dataVersion: { type: Number, default: 0 },
    },
    watch: {
      dataVersion() {
        this.loadAll();
      },
    },
    data() {
      return {
        expandedState: {},
        draggingTask: null,
        dragOverCategoryId: undefined, // undefined=未拖曳中；null=懸停在「未歸類」；number=懸停在該分類節點
        subCategoryAdd: { open: false, parentCategoryId: null, name: '' },
      };
    },
    computed: {
      categoryTree() {
        return buildCategoryTree(this.categories);
      },
    },
    methods: {
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
          // 這個 input 位於 v-for="stage in categoryTree" 迴圈內，同名 ref 在 Vue 3 會收斂成陣列
          // （即使同時只有一個符合 subCategoryAdd.parentCategoryId 的節點會實際掛載），
          // 直接當單一元素呼叫 .focus() 會拋 TypeError，需先攤平陣列
          const el = Array.isArray(this.$refs.subCategoryAddInput)
            ? this.$refs.subCategoryAddInput[0] : this.$refs.subCategoryAddInput;
          if (el) el.focus();
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
      // 不能沿用 window.location.href：401/403 時後端沒有 Content-Disposition，瀏覽器會直接把
      // 整頁導航到 ApiResponse 的原始 JSON 錯誤內容，使用者當下的分頁狀態全部消失。改用 fetch+blob，
      // 非 200 時才有機會攔下來跳 toast；也不能沿用 api() 骨架，它固定呼叫 res.json()，對二進位 xlsx
      // 內容會解析失敗
      async exportXlsx() {
        try {
          const res = await fetch(`/api/projects/${this.projectId}/export.xlsx`);
          if (!res.ok) {
            const body = await res.json().catch(() => ({}));
            this.showToast(body.message || '匯出失敗');
            return;
          }
          // 檔名從後端 Content-Disposition 的 RFC 5987 filename*=UTF-8''... 取回，
          // 保留 TaskController.download() 產生的「{專案名稱}_任務清單.xlsx」，不要 hardcode 通用檔名
          const disposition = res.headers.get('Content-Disposition') || '';
          const match = disposition.match(/filename\*=UTF-8''([^;]+)/);
          const filename = match ? decodeURIComponent(match[1]) : '任務清單.xlsx';
          const url = URL.createObjectURL(await res.blob());
          const a = document.createElement('a');
          a.href = url;
          a.download = filename;
          a.click();
          URL.revokeObjectURL(url);
        } catch (e) {
          this.showToast('網路錯誤，請稍後再試');
        }
      },
    },
    mounted() {
      this.loadAll();
      this.loadCategoryPresets();
    },
    template: `
      <div>
        <div class="wbs-toolbar">
          <button class="btn" @click="exportXlsx">匯出 Excel</button>
        </div>
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
              <wbs-task-row v-for="t in directTasks(null)" :key="t.id" :task="t" :can-write="canWrite"
                            @dragstart="onTaskDragStart(t, $event)"
                            @dragend="draggingTask = null; dragOverCategoryId = undefined" @open="openEdit(t)" />
            </div>
          </div>

          <div v-for="stage in categoryTree" :key="stage.id" class="wbs-node"
               :class="{ 'drag-over': draggingTask && dragOverCategoryId === stage.id }"
               @dragover.prevent="dragOverCategoryId = stage.id" @drop="onCategoryNodeDrop(stage.id)">
            <div class="wbs-node-header"
                 @click="toggleExpanded(stage.id)"
                 @dragover.prevent="dragOverCategoryId = stage.id" @drop="onCategoryDrop(stage)">
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
                     @dragover.prevent="dragOverCategoryId = child.id" @drop="onCategoryDrop(child)">
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

          <div v-if="canWrite" class="stage-toggle-row">
            <button v-for="p in stagePresets" :key="p.id"
                    class="btn btn-sm stage-toggle-btn" :class="{ active: isStageActive(p) }"
                    @click="toggleStage(p)">
              {{ p.name }} · {{ isStageActive(p) ? '已啟用' : '啟用' }}
            </button>
          </div>
        </div>
        <task-modal v-if="modal.open" :modal="modal" :members="members" :categories="categories" :can-write="canWrite"
                    @save="saveTask" @delete="deleteTask" @close="modal.open = false" />
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

  const app = createApp({
    mixins: [toastMixin],
    data() {
      return {
        projectId, projectName, canWrite, canArchive, archived, sectionId, activeTab: 'kanban',
        ownerId, dataVersion: 0,
        memberPanelOpen: false, membersLoaded: false, membersLoading: false,
        members: [], allUsers: [], addingUserId: null, changingOwnerId: null,
      };
    },
    computed: {
      availableUsers() {
        const memberIds = new Set(this.members.map(m => m.userId));
        return this.allUsers.filter(u => !memberIds.has(u.id));
      },
    },
    methods: {
      async archiveProject() {
        const result = await api(`/api/projects/${this.projectId}/archive`, { method: 'PATCH' });
        if (result.success) {
          this.archived = true;
          // 封存後任何角色的 canWrite 恆為 false（ProjectService.canWrite 的鏡射邏輯），不需要重新查詢後端
          this.canWrite = false;
        } else {
          this.showToast(result.message || '封存失敗');
        }
      },
      async unarchiveProject() {
        const result = await api(`/api/projects/${this.projectId}/unarchive`, { method: 'PATCH' });
        if (result.success) {
          this.archived = false;
          // 未封存時 canWrite 與 canArchive 的角色判斷邏輯完全相同（見 ProjectService.canWrite/canArchive），可直接沿用
          this.canWrite = this.canArchive;
          // canWrite 由 false 轉 true：若成員面板先前已在唯讀狀態下載入過（跳過了 /api/users），
          // 復位 membersLoaded 讓下次開面板補抓使用者名單，否則新增成員下拉會維持空白直到整頁重新整理
          this.membersLoaded = false;
        } else {
          this.showToast(result.message || '解封存失敗');
        }
      },
      async toggleMemberPanel() {
        this.memberPanelOpen = !this.memberPanelOpen;
        // 先設 true 擋住連續點擊的重複載入；loadMembers 失敗會自行復位，讓下次開面板重新嘗試
        if (this.memberPanelOpen && !this.membersLoaded) {
          this.membersLoaded = true;
          await this.loadMembers();
        }
      },
      // /api/users（全體使用者名單）只有 canWrite 的新增成員下拉會用到，唯讀角色（如 DIRECTOR）
      // 開面板時略過這支請求，不必為了用不到的資料多打一次 API
      async loadMembers() {
        this.membersLoading = true;
        const requests = [api(`/api/projects/${this.projectId}/members`)];
        if (this.canWrite) requests.push(api('/api/users'));
        const [membersRes, usersRes] = await Promise.all(requests);
        this.members = membersRes.success ? membersRes.data : [];
        this.allUsers = (usersRes && usersRes.success) ? usersRes.data : [];
        if (!membersRes.success || (usersRes && !usersRes.success)) {
          this.showToast(membersRes.message || (usersRes && usersRes.message) || '成員載入失敗');
          // 失敗時復位 membersLoaded，避免網路小抖動後面板永遠卡在空清單，要重新整理頁面才能救回來
          this.membersLoaded = false;
        }
        this.membersLoading = false;
      },
      async addMember() {
        if (!this.addingUserId) return;
        const result = await api(`/api/projects/${this.projectId}/members`, {
          method: 'POST', body: JSON.stringify({ userId: this.addingUserId }),
        });
        this.addingUserId = null;
        if (result.success) {
          await this.loadMembers();
          this.dataVersion++;
        } else {
          this.showToast(result.message || '新增成員失敗');
        }
      },
      async removeMember(m) {
        if (m.userId === this.ownerId) return;
        if (!confirm(`確定移除成員「${m.displayName}」？`)) return;
        const result = await api(`/api/projects/${this.projectId}/members/${m.userId}`, { method: 'DELETE' });
        if (result.success) {
          await this.loadMembers();
          this.dataVersion++;
        } else {
          this.showToast(result.message || '移除成員失敗');
        }
      },
      async changeOwner() {
        if (!this.changingOwnerId || this.changingOwnerId === this.ownerId) return;
        const result = await api(`/api/projects/${this.projectId}/owner`, {
          method: 'PUT', body: JSON.stringify({ userId: this.changingOwnerId }),
        });
        if (result.success) {
          this.ownerId = this.changingOwnerId;
          this.changingOwnerId = null;
          this.dataVersion++;
        } else {
          this.showToast(result.message || '換負責人失敗');
        }
      },
    },
    template: `
      <div>
        <div class="project-toolbar">
          <span v-if="archived" class="archived-badge">已封存</span>
          <button v-if="canArchive && !archived" class="btn" @click="archiveProject">封存</button>
          <button v-if="canArchive && archived" class="btn" @click="unarchiveProject">解封存</button>
          <button class="btn" @click="toggleMemberPanel">成員管理 {{ memberPanelOpen ? '▴' : '▾' }}</button>
        </div>
        <div v-if="memberPanelOpen" class="member-panel">
          <p v-if="membersLoading">載入中...</p>
          <template v-else>
            <ul class="member-list">
              <li v-for="m in members" :key="m.userId" class="member-row">
                <span>{{ m.displayName }}<span v-if="m.userId === ownerId" class="member-owner-badge">負責人</span></span>
                <button v-if="canWrite" class="btn btn-sm btn-danger" :disabled="m.userId === ownerId"
                        :title="m.userId === ownerId ? '請先轉移負責人' : ''"
                        @click="removeMember(m)">移除</button>
              </li>
              <li v-if="!members.length" style="color:#636e72;font-size:0.85rem">尚無成員</li>
            </ul>
            <div v-if="canWrite" class="member-panel-actions">
              <div class="member-add-row">
                <select v-model="addingUserId">
                  <option :value="null">— 新增成員 —</option>
                  <option v-for="u in availableUsers" :key="u.id" :value="u.id">{{ u.displayName }}</option>
                </select>
                <button class="btn btn-sm btn-primary" :disabled="!addingUserId" @click="addMember">新增</button>
              </div>
              <div class="member-owner-row">
                <label>換負責人</label>
                <select v-model="changingOwnerId">
                  <option :value="null">— 選擇成員 —</option>
                  <option v-for="m in members" :key="m.userId" :value="m.userId">{{ m.displayName }}</option>
                </select>
                <button class="btn btn-sm" :disabled="!changingOwnerId || changingOwnerId === ownerId" @click="changeOwner">確認</button>
              </div>
            </div>
          </template>
        </div>
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
        <kanban-view v-if="activeTab === 'kanban'" :project-id="projectId" :can-write="canWrite" :section-id="sectionId" :data-version="dataVersion" />
        <assignment-view v-else-if="activeTab === 'assignment'" :project-id="projectId" :can-write="canWrite" :data-version="dataVersion" />
        <wbs-view v-else :project-id="projectId" :can-write="canWrite" :section-id="sectionId" :data-version="dataVersion" />
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

  app.component('kanban-view', KanbanView);
  app.component('assignment-view', AssignmentView);
  app.component('task-modal', TaskModal);
  app.component('wbs-task-row', WbsTaskRow);
  app.component('wbs-view', WbsView);
  app.mount('#detail-app');
})();
