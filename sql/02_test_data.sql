-- 測試帳號與必要設定（種子資料）；不建業務資料，業務資料由系統功能產生
-- 測試帳號密碼統一為 password123（BCrypt strength=10）

-- ============================================================
-- 部門（部 → 科）
-- ============================================================
INSERT INTO departments (id, name, parent_id) VALUES
    (1, '資訊部', NULL),
    (2, '系統科', 1),
    (3, '網路科', 1);

-- ============================================================
-- 測試帳號：四角色各一
-- ============================================================
INSERT INTO users (id, username, password, display_name, role, department_id) VALUES
    (1, 'director',       '$2b$10$8FgvlcJJyR8xog72HASXTOLMie5Fur/gEmw2An0CI3IAZyP4ms/kK', '主任',       'DIRECTOR',       1),
    (2, 'chief',          '$2b$10$8FgvlcJJyR8xog72HASXTOLMie5Fur/gEmw2An0CI3IAZyP4ms/kK', '科長',       'SECTION_CHIEF',  2),
    (3, 'leader',         '$2b$10$8FgvlcJJyR8xog72HASXTOLMie5Fur/gEmw2An0CI3IAZyP4ms/kK', '專案負責人', 'PROJECT_LEADER', 2),
    (4, 'member',         '$2b$10$8FgvlcJJyR8xog72HASXTOLMie5Fur/gEmw2An0CI3IAZyP4ms/kK', '專案成員',   'PROJECT_MEMBER', 2),
    (5, 'member2',        '$2b$10$8FgvlcJJyR8xog72HASXTOLMie5Fur/gEmw2An0CI3IAZyP4ms/kK', '專案成員二', 'PROJECT_MEMBER', 3);

SELECT setval('users_id_seq', (SELECT max(id) FROM users));
SELECT setval('departments_id_seq', (SELECT max(id) FROM departments));

-- ============================================================
-- 範例專案：leader 為 owner 與成員、member 加入專案；member2 不加入（維持跨科隔離測試素材）
-- ============================================================
INSERT INTO projects (id, name, description, section_id, owner_id, created_by) VALUES
    (1, 'MissionBoard 範例專案', '示範任務看板用', 2, 3, 3);

SELECT setval('projects_id_seq', (SELECT max(id) FROM projects));

INSERT INTO project_members (project_id, user_id, assigned_by) VALUES
    (1, 3, 3),
    (1, 4, 3);

-- ============================================================
-- task_category_presets 種子：STAGE / CATEGORY 全域預設（section_id NULL）
-- ============================================================
INSERT INTO task_category_presets (type, name, sort_order, section_id) VALUES
    ('STAGE', 'SIT', 1, NULL),
    ('STAGE', 'UAT', 2, NULL),
    ('STAGE', 'PROD', 3, NULL),
    ('CATEGORY', '程式開發', 1, NULL),
    ('CATEGORY', '環境建置', 2, NULL),
    ('CATEGORY', '使用者測試', 3, NULL);

-- ============================================================
-- task_categories 種子：示範兩層（階段 SIT → 類別 程式開發），供看板歸類與測試使用
-- ============================================================
INSERT INTO task_categories (id, project_id, parent_category_id, name, sort_order) VALUES
    (1, 1, NULL, 'SIT', 1),
    (2, 1, 1, '程式開發', 1);

SELECT setval('task_categories_id_seq', (SELECT max(id) FROM task_categories));

-- ============================================================
-- tasks 種子：含已歸類／未歸類、三種狀態、含指派／不含指派，供看板拖曳與權限測試使用
-- ============================================================
INSERT INTO tasks (project_id, category_id, title, description, assignee_id, status, priority, start_date, due_date, sort_order) VALUES
    (1, 2, '設計登入頁', '完成登入頁 UI 設計稿', 4, 'DONE', 'MEDIUM', '2026-07-01', '2026-07-10', 0),
    (1, 2, '實作看板拖曳', '串接 /tasks/move 端點', 3, 'IN_PROGRESS', 'HIGH', '2026-07-15', '2026-08-15', 0),
    (1, NULL, '整理需求訪談紀錄', NULL, NULL, 'NOT_STARTED', 'LOW', NULL, NULL, 0);
