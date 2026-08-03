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
