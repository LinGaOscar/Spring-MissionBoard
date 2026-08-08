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
-- wbs_presets：L1(STAGE)／L2(CATEGORY) 選單，section_id NULL = 全域預設
-- ============================================================
CREATE TABLE wbs_presets (
    id          bigserial PRIMARY KEY,
    type        varchar(20) NOT NULL CHECK (type IN ('STAGE', 'CATEGORY')),
    name        varchar(100) NOT NULL,
    sort_order  int NOT NULL DEFAULT 0,
    enabled     boolean NOT NULL DEFAULT true,
    section_id  bigint REFERENCES departments(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_wbs_presets_section ON wbs_presets(section_id);

-- ============================================================
-- wbs_nodes：核心表，節點即任務。三層固定語意由 CHECK 約束保證：
--   L1/L2 不可派工、不落地狀態／日期（由子節點即時彙總，不寫入本表）
--   L3 是唯一可派工層，assignee/status/priority/日期僅 L3 允許寫值
-- ============================================================
CREATE TABLE wbs_nodes (
    id           bigserial PRIMARY KEY,
    project_id   bigint NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    parent_id    bigint REFERENCES wbs_nodes(id) ON DELETE CASCADE,
    level        smallint NOT NULL CHECK (level BETWEEN 1 AND 3),
    title        varchar(300) NOT NULL,
    assignee_id  bigint REFERENCES users(id),
    status       varchar(20) CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE')),
    priority     varchar(10) CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    start_date   date,
    end_date     date,
    notes        text,
    sort_order   int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    -- L1 無父節點；L2/L3 必有父節點
    CONSTRAINT chk_level_parent CHECK (
        (level = 1 AND parent_id IS NULL) OR (level > 1 AND parent_id IS NOT NULL)
    ),
    -- 僅 L3 可派工
    CONSTRAINT chk_assignee_l3_only CHECK (level = 3 OR assignee_id IS NULL),
    -- 僅 L3 儲存狀態（L1/L2 由子節點即時彙總，不落地）
    CONSTRAINT chk_status_l3_only CHECK (level = 3 OR status IS NULL),
    -- 僅 L3 儲存優先度
    CONSTRAINT chk_priority_l3_only CHECK (level = 3 OR priority IS NULL),
    -- 僅 L3 可編輯起迄日（L1/L2 顯示子節點 min/max，屬計算值不落地）
    CONSTRAINT chk_dates_l3_only CHECK (level = 3 OR (start_date IS NULL AND end_date IS NULL))
);

CREATE INDEX idx_wbs_nodes_project ON wbs_nodes(project_id);
CREATE INDEX idx_wbs_nodes_parent ON wbs_nodes(parent_id);
CREATE INDEX idx_wbs_nodes_assignee ON wbs_nodes(assignee_id);
