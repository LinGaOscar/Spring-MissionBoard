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

  // 已完成的任務不再警示逾期，避免歷史卡片一片紅；用本地日期字串比對，避免 toISOString 的 UTC 誤差
  function isOverdueDate(dueDate, status) {
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    return !!dueDate && status !== 'DONE' && dueDate < today;
  }

  const STATUS_LABELS = { NOT_STARTED: '未開始', IN_PROGRESS: '進行中', DONE: '已完成' };

  const app = createApp({
    data() {
      return {
        loading: true,
        errorMessage: '',
        viewType: null,
        activeProjects: [],
        myTasks: [],
        sectionName: '',
        projectSummaries: [],
        sectionSummaries: [],
      };
    },
    methods: {
      isOverdue(task) {
        return isOverdueDate(task.dueDate, task.status);
      },
      statusLabel(status) {
        return STATUS_LABELS[status];
      },
      async loadDashboard() {
        this.loading = true;
        const result = await api('/api/users/me/dashboard');
        if (result.success) {
          this.viewType = result.data.viewType;
          this.activeProjects = result.data.activeProjects || [];
          this.myTasks = result.data.myTasks || [];
          this.sectionName = result.data.sectionName || '';
          this.projectSummaries = result.data.projectSummaries || [];
          this.sectionSummaries = result.data.sectionSummaries || [];
          this.errorMessage = '';
        } else {
          this.errorMessage = result.message || '載入失敗，請重新整理';
        }
        this.loading = false;
      },
      goToProject(projectId) {
        window.location.href = '/projects/' + projectId;
      },
    },
    mounted() {
      this.loadDashboard();
    },
    template: `
      <div>
        <p v-if="loading">載入中...</p>
        <p v-else-if="errorMessage" class="alert alert-error">{{ errorMessage }}</p>
        <template v-else-if="viewType === 'PERSONAL'">
          <div class="dashboard-section">
            <h2>進行中的專案</h2>
            <p v-if="!activeProjects.length" style="color:var(--ink-muted)">目前沒有進行中的專案</p>
            <div v-else class="project-grid">
              <a v-for="p in activeProjects" :key="p.id" class="project-card" :href="'/projects/' + p.id">
                <div class="project-card-name">{{ p.name }}</div>
                <div class="project-card-meta">{{ p.sectionName }} · 負責人：{{ p.ownerDisplayName }}</div>
              </a>
            </div>
          </div>
          <div class="dashboard-section">
            <h2>指派給我的任務</h2>
            <p v-if="!myTasks.length" style="color:var(--ink-muted)">目前沒有指派給你的任務</p>
            <div v-else>
              <div v-for="t in myTasks" :key="t.id" class="dashboard-task-row">
                <span>{{ t.title }}</span>
                <span class="dashboard-task-meta" :class="{ overdue: isOverdue(t) }">
                  <span>{{ t.projectName }}</span>
                  <span>{{ statusLabel(t.status) }}</span>
                  <span>{{ t.dueDate || '--' }}</span>
                </span>
              </div>
            </div>
          </div>
        </template>
        <template v-else-if="viewType === 'SECTION'">
          <div class="dashboard-section">
            <h2>{{ sectionName }} 進行中專案總覽</h2>
            <p v-if="!projectSummaries.length" style="color:var(--ink-muted)">本科目前沒有進行中的專案</p>
            <div v-else>
              <div v-for="p in projectSummaries" :key="p.projectId" class="dashboard-summary-row clickable" @click="goToProject(p.projectId)">
                <span class="dashboard-summary-name">{{ p.projectName }}</span>
                <span class="dashboard-summary-stats" :class="{ 'has-overdue': p.overdueCount > 0 }">
                  {{ p.taskCount }} 個任務 · {{ p.overdueCount }} 逾期 · {{ p.completionLabel }} 完成
                </span>
              </div>
            </div>
          </div>
        </template>
        <template v-else-if="viewType === 'ORG'">
          <div class="dashboard-section">
            <h2>全公司進行中專案總覽</h2>
            <p v-if="!sectionSummaries.length" style="color:var(--ink-muted)">目前沒有進行中的專案</p>
            <div v-else>
              <div v-for="s in sectionSummaries" :key="s.sectionId" class="dashboard-summary-row">
                <span class="dashboard-summary-name">{{ s.sectionName }}</span>
                <span class="dashboard-summary-stats" :class="{ 'has-overdue': s.overdueCount > 0 }">
                  {{ s.projectCount }} 個專案 · {{ s.overdueCount }} 逾期 · {{ s.completionLabel }} 完成
                </span>
              </div>
            </div>
          </div>
        </template>
      </div>
    `,
  });

  app.mount('#home-app');
})();
