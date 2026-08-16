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

  const app = createApp({
    data() {
      return {
        projects: [], loading: true, archived: false, errorMessage: '',
        createModal: { open: false, name: '', description: '', error: '' },
      };
    },
    methods: {
      async loadProjects() {
        this.loading = true;
        const result = await api(`/api/projects?archived=${this.archived}`);
        if (result.success) {
          this.projects = result.data;
          this.errorMessage = '';
        } else {
          this.projects = [];
          this.errorMessage = result.message || '載入失敗，請重新整理';
        }
        this.loading = false;
      },
      switchTab(archived) {
        if (this.archived === archived) return;
        this.archived = archived;
        this.loadProjects();
      },
      openCreateModal() {
        this.createModal = { open: true, name: '', description: '', error: '' };
      },
      closeCreateModal() {
        this.createModal.open = false;
      },
      async submitCreateProject() {
        const name = this.createModal.name.trim();
        if (!name) {
          this.createModal.error = '名稱不可為空';
          return;
        }
        const result = await api('/api/projects', {
          method: 'POST',
          body: JSON.stringify({ name, description: this.createModal.description || null }),
        });
        if (result.success) {
          window.location.href = '/projects/' + result.data.id;
        } else {
          this.createModal.error = result.message || '建立失敗';
        }
      },
    },
    mounted() {
      this.loadProjects();
    },
    template: `
      <div>
        <div class="page-header">
          <h1>專案列表</h1>
          <button class="btn btn-primary" @click="openCreateModal">新增專案</button>
        </div>
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': !archived }" @click="switchTab(false)">未封存</button>
          <button class="btn" :class="{ 'btn-primary': archived }" @click="switchTab(true)">已封存</button>
        </div>
        <p v-if="loading">載入中...</p>
        <p v-else-if="errorMessage" class="alert alert-error">{{ errorMessage }}</p>
        <p v-else-if="!projects.length" style="color:#636e72">目前沒有{{ archived ? '已封存' : '' }}專案</p>
        <div v-else class="project-grid">
          <a v-for="p in projects" :key="p.id" class="project-card" :href="'/projects/' + p.id">
            <div class="project-card-name">{{ p.name }}</div>
            <div class="project-card-meta">{{ p.sectionName }} · 負責人：{{ p.ownerDisplayName }}</div>
          </a>
        </div>
        <div v-if="createModal.open" class="modal-overlay" @click.self="closeCreateModal">
          <div class="modal">
            <h3>新增專案</h3>
            <p v-if="createModal.error" class="alert alert-error">{{ createModal.error }}</p>
            <div class="form-group"><label>名稱</label><input v-model="createModal.name" /></div>
            <div class="form-group"><label>描述</label><textarea v-model="createModal.description" rows="4"></textarea></div>
            <div class="modal-actions">
              <button class="btn btn-primary" @click="submitCreateProject">建立</button>
              <button class="btn" @click="closeCreateModal">取消</button>
            </div>
          </div>
        </div>
      </div>
    `,
  });

  app.mount('#list-app');
})();
