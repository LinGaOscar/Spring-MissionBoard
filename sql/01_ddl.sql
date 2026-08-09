-- Spring-MissionBoard schema
-- 手寫 DDL，ddl-auto: none，不使用 Flyway / Liquibase

-- ============================================================
-- departments：部 → 科 自參照樹（parent_id IS NULL = 部，有值 = 科）
-- ============================================================
CREATE TABLE departments (
    id          bigserial PRIMARY KEY,
    name        varchar(100) NOT NULL,
    parent_id   bigint REFERENCES departments(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_departments_parent ON departments(parent_id);

-- ============================================================
-- users：四角色，科別歸屬
-- ============================================================
CREATE TABLE users (
    id             bigserial PRIMARY KEY,
    username       varchar(50) NOT NULL UNIQUE,
    password       varchar(100) NOT NULL,
    display_name   varchar(100) NOT NULL,
    role           varchar(20) NOT NULL
                   CHECK (role IN ('DIRECTOR', 'SECTION_CHIEF', 'PROJECT_LEADER', 'PROJECT_MEMBER')),
    department_id  bigint REFERENCES departments(id),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_department ON users(department_id);

-- ============================================================
-- projects：owner／created_by／archived，section_id 供科別隔離查詢
-- ============================================================
CREATE TABLE projects (
    id           bigserial PRIMARY KEY,
    name         varchar(200) NOT NULL,
    description  text,
    section_id   bigint NOT NULL REFERENCES departments(id),
    owner_id     bigint NOT NULL REFERENCES users(id),
    created_by   bigint NOT NULL REFERENCES users(id),
    archived     boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_section ON projects(section_id);
CREATE INDEX idx_projects_owner ON projects(owner_id);

-- ============================================================
-- project_members：複合 PK，assigned_by 記錄派工者
-- ============================================================
CREATE TABLE project_members (
    project_id   bigint NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id      bigint NOT NULL REFERENCES users(id),
    assigned_by  bigint NOT NULL REFERENCES users(id),
    joined_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);

CREATE INDEX idx_project_members_user ON project_members(user_id);

-- ============================================================
-- task_category_presets：階段(STAGE)／類別(CATEGORY) 選單，section_id NULL = 全域預設
-- ============================================================
CREATE TABLE task_category_presets (
    id          bigserial PRIMARY KEY,
    type        varchar(20) NOT NULL CHECK (type IN ('STAGE', 'CATEGORY')),
    name        varchar(100) NOT NULL,
    sort_order  int NOT NULL DEFAULT 0,
    enabled     boolean NOT NULL DEFAULT true,
    section_id  bigint REFERENCES departments(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_category_presets_section ON task_category_presets(section_id);

-- ============================================================
-- task_categories：選配、最多兩層（parent_category_id NULL = 階段層，有值 = 類別層）。
-- 深度上限由 service 層驗證（TaskCategoryService），不用 DB CHECK——沿用專案「應用層驗證優先」慣例。
-- 刪階段連帶刪其下類別（CASCADE）；刪類別不影響任務，任務改屬 tasks.category_id 的 ON DELETE SET NULL 落回未歸類。
-- ============================================================
CREATE TABLE task_categories (
    id                   bigserial PRIMARY KEY,
    project_id           bigint NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    parent_category_id   bigint REFERENCES task_categories(id) ON DELETE CASCADE,
    name                 varchar(300) NOT NULL,
    sort_order           int NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_categories_project ON task_categories(project_id);
CREATE INDEX idx_task_categories_parent ON task_categories(parent_category_id);

-- ============================================================
-- tasks：核心表，任務天生扁平獨立，不需先有類別骨架才能建立。
-- category_id 可為 NULL（未歸類）；status/priority/assignee/日期任何任務皆可直接寫值，不再有層級限制。
-- ============================================================
CREATE TABLE tasks (
    id           bigserial PRIMARY KEY,
    project_id   bigint NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    category_id  bigint REFERENCES task_categories(id) ON DELETE SET NULL,
    title        varchar(300) NOT NULL,
    description  text,
    assignee_id  bigint REFERENCES users(id),
    status       varchar(20) NOT NULL CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE')),
    priority     varchar(10) CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    start_date   date,
    due_date     date,
    sort_order   int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_project ON tasks(project_id);
CREATE INDEX idx_tasks_category ON tasks(category_id);
CREATE INDEX idx_tasks_assignee ON tasks(assignee_id);
CREATE INDEX idx_tasks_status ON tasks(status);
