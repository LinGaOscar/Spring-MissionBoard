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
      return { projects: [], loading: true, archived: false, errorMessage: '' };
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
    },
    mounted() {
      this.loadProjects();
    },
    template: `
      <div>
        <div class="page-header">
          <h1>專案列表</h1>
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
      </div>
    `,
  });

  app.mount('#list-app');
})();
