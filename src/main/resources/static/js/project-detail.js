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

  const WbsNodeRow = defineComponent({
    name: 'WbsNodeRow',
    props: {
      node: { type: Object, required: true },
      numbering: { type: Object, required: true },
      depth: { type: Number, default: 0 },
      canWrite: { type: Boolean, default: false },
      members: { type: Array, default: () => [] },
    },
    emits: ['cycle-status', 'update-title', 'create-node'],
    data() {
      return {
        editingTitle: false, titleDraft: this.node.title,
        showAddForm: false, addTitle: '', addPresetId: '', categoryPresets: [],
      };
    },
    computed: {
      statusLabel() { return STATUS_LABEL[this.node.status] || ''; },
    },
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
    },
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
        <wbs-node-row v-for="child in node.children" :key="child.id" :node="child" :numbering="numbering"
          :depth="depth + 1" :can-write="canWrite" :members="members"
          @cycle-status="$emit('cycle-status', $event)"
          @update-title="(id, t) => $emit('update-title', id, t)"
          @create-node="$emit('create-node', $event)" />
      </div>
    `,
  });

  const TreeEditorView = defineComponent({
    name: 'TreeEditorView',
    props: { nodes: { type: Array, default: () => [] }, members: { type: Array, default: () => [] }, canWrite: { type: Boolean, default: false } },
    emits: ['cycle-status', 'update-title', 'create-node', 'init-stages'],
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
        <div class="page-header">
          <h2>樹編輯器</h2>
          <button v-if="canWrite && nodes.length === 0" class="btn btn-primary" @click="$emit('init-stages')">初始化階段骨架</button>
        </div>
        <p class="wbs-stats" v-if="nodes.length">細項總數：{{ stats.total }}，完成率：{{ stats.rate }}%（{{ stats.done }}/{{ stats.total }}）</p>
        <p v-if="nodes.length === 0">此專案尚未建立節點</p>
        <wbs-node-row v-for="root in tree" :key="root.id" :node="root" :numbering="numberingMap"
          :depth="0" :can-write="canWrite" :members="members"
          @cycle-status="$emit('cycle-status', $event)"
          @update-title="(id, t) => $emit('update-title', id, t)"
          @create-node="$emit('create-node', $event)" />
      </div>
    `,
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
          <tree-editor-view :nodes="nodes" :members="members" :can-write="canWrite"
            @cycle-status="cycleStatus" @update-title="updateTitle"
            @create-node="createNode" @init-stages="initStages" />
        </div>
        <div v-show="activeTab==='kanban'"><kanban-view :nodes="nodes" :can-write="canWrite" /></div>
        <div v-show="activeTab==='assignment'"><assignment-view :nodes="nodes" :can-write="canWrite" /></div>
        <div v-show="activeTab==='gantt'"><gantt-view :nodes="nodes" :can-write="canWrite" /></div>
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

  app.component('tree-editor-view', TreeEditorView);
  app.component('wbs-node-row', WbsNodeRow);
  app.component('kanban-view', KanbanView);
  app.component('assignment-view', AssignmentView);
  app.component('gantt-view', GanttView);
  app.mount('#detail-app');
})();
