(function () {
  const { createApp, defineComponent } = Vue;

  const el = document.getElementById('detail-app');
  const projectId = Number(el.dataset.projectId);
  const canWrite = el.dataset.canWrite === 'true';

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

  const KanbanView = defineComponent({
    name: 'KanbanView',
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
    },
    data() {
      return {
        tasks: [], categories: [], members: [],
        loading: true,
        columns: [
          { status: 'NOT_STARTED', label: '未開始' },
          { status: 'IN_PROGRESS', label: '進行中' },
          { status: 'DONE', label: '已完成' },
        ],
        dragging: null, dragOverCol: null, dragIndex: 0,
        taskWriteQueue: {},  // 同一任務的連續寫入序列化，避免拖曳與 modal 編輯併發時後完成者用舊快照蓋掉新資料（遺失更新）
        modal: {
          open: false, taskId: null,
          form: { title: '', description: '', assigneeId: null, categoryId: null, priority: null, startDate: null, dueDate: null },
        },
        toastMessage: '', toastTimer: null,
      };
    },
    methods: {
      emptyForm() {
        return { title: '', description: '', assigneeId: null, categoryId: null, priority: null, startDate: null, dueDate: null };
      },
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
      showToast(message) {
        this.toastMessage = message;
        clearTimeout(this.toastTimer);
        this.toastTimer = setTimeout(() => { this.toastMessage = ''; }, 3000);
      },
      queueTaskWrite(taskId, task) {
        const prev = this.taskWriteQueue[taskId] || Promise.resolve();
        const next = prev.then(task, task);
        this.taskWriteQueue[taskId] = next;
        return next;
      },
      tasksIn(status) {
        return this.tasks.filter(t => t.status === status).sort((a, b) => a.sortOrder - b.sortOrder);
      },
      // 已完成的任務不再警示逾期，避免歷史卡片一片紅；用本地日期字串比對，避免 toISOString 的 UTC 誤差
      isOverdue(t) {
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        return !!t.dueDate && t.status !== 'DONE' && t.dueDate < today;
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
        <div v-if="modal.open" class="modal-overlay" @click.self="modal.open = false">
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
              <button class="btn btn-primary" @click="saveTask" :disabled="!canWrite">儲存</button>
              <button v-if="modal.taskId && canWrite" class="btn btn-danger" @click="deleteTask">刪除</button>
              <button class="btn" @click="modal.open = false">取消</button>
            </div>
          </div>
        </div>
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

  const app = createApp({
    data() {
      return { projectId, canWrite };
    },
    template: `<kanban-view :project-id="projectId" :can-write="canWrite" />`,
  });

  app.component('kanban-view', KanbanView);
  app.mount('#detail-app');
})();
