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
-- wbs_presets 種子：STAGE / CATEGORY 全域預設（section_id NULL）
-- ============================================================
INSERT INTO wbs_presets (type, name, sort_order, section_id) VALUES
    ('STAGE', 'SIT', 1, NULL),
    ('STAGE', 'UAT', 2, NULL),
    ('STAGE', 'PROD', 3, NULL),
    ('CATEGORY', '程式開發', 1, NULL),
    ('CATEGORY', '環境建置', 2, NULL),
    ('CATEGORY', '使用者測試', 3, NULL);
