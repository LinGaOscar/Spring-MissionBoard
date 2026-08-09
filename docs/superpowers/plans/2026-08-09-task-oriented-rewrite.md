# Spring-MissionBoard 任務導向重構 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `wbs_nodes` 三層固定樹（L1 階段／L2 類別／L3 可派工細項，靠 DB CHECK 約束）整個換成扁平獨立的 `tasks`（天生可不歸類存在）＋選配兩層的 `task_categories`，並把專案詳情頁預設檢視從樹編輯器換成看板（拖曳＋modal 編輯），移除樹編輯器／人員派工／甘特三個分頁。

**Architecture:** 新增 `com.missionboard.task` package 取代 `com.missionboard.wbs`：`Task`（原 L3 語意，扁平獨立）、`TaskCategory`（原 L1/L2，選配兩層，深度上限由 service 層驗證，不用 DB CHECK）、`TaskCategoryPreset`（`WbsPreset` 直接改名，欄位與權限規則不變）。`ProjectService.canRead/canWrite/canArchive` 與 IDOR 防護模式（`getXInProject` 先查存在、再比對 `project_id`）全部沿用。前端 `project-detail.js` 移除 `TreeEditorView`/`WbsNodeRow`/`AssignmentView`/`GanttView`，`KanbanView` 從佔位符換成真正的拖曳看板，成為預設分頁。

**Tech Stack:** Java 21、Spring Boot 3.4、Spring Data JPA（Hibernate）、PostgreSQL 16（`sql/01_ddl.sql` 手寫 DDL，`ddl-auto: none`）、H2（`MODE=PostgreSQL`，測試用 `ddl-auto: create-drop`，schema 由 entity 產生非 `01_ddl.sql`）、Vue 3（無 build，vendored UMD）、Lombok、MockMvc。

## Global Constraints

- 依 [`docs/superpowers/specs/2026-08-08-missionboard-task-oriented-rewrite-design.md`](../specs/2026-08-08-missionboard-task-oriented-rewrite-design.md) 執行；改名（`Spring-WbsFlow` → `Spring-MissionBoard`）已於先前 session 完成，不在本計畫範圍。
- **保留完全不動**：`auth/`、`project/`（除 Task 5.6 對 `ProjectService.removeMember` 的必要重接線）、`user/`、`department/`。
- **深度上限兩層由 service 層驗證強制**，不使用 DB CHECK／trigger（規格明文：沿用「應用層驗證優先」慣例）。
- **v1 純 REST，不做 WebSocket**；service 層是唯一寫入口。
- 狀態變更／派工走專用 PATCH 端點（`/status`、`/assignee`、`/move`），不塞進泛用 PUT——沿用專案既有 API 慣例。
- 例外→HTTP 狀態碼映射沿用 `GlobalExceptionHandler` 既有規則：`EntityNotFoundException`→404、`IllegalArgumentException`→400、`SecurityException`→403，不新增例外型別。
- 所有新 mutating service 方法標 `@Transactional`，查詢方法標 `@Transactional(readOnly = true)`。
- 每個任務結束時 `mvn test` 全綠才 commit；commit message 依專案既有風格（`feat:`/`fix:`/`refactor:`/`docs:` 前綴 + 中文摘要）。

---

## 資料模型設計決策（非規格逐字規定，本計畫明確拍板，供後續任務引用）

1. **`tasks.due_date`**：規格原文用詞是 `due_date`（非沿用 `wbs_nodes.end_date` 命名），本計畫全程使用 `dueDate`/`due_date`，前端也同步改名。
2. **`task_categories` 刪除語意**：`parent_category_id` 用 `ON DELETE CASCADE`（刪階段連帶刪其下類別，比照舊 `wbs_nodes.parent_id` 行為）；`tasks.category_id` 用 `ON DELETE SET NULL`（刪類別不刪任務，任務落回「未歸類」，呼應規格「任務天生可獨立存在」的定位）。兩者都加 Hibernate `@OnDelete` 對應標註，讓 H2 測試庫（entity 建表）與正式 Postgres DDL 的 cascade 行為一致。
3. **`TaskCategoryPreset.Type`（`STAGE`/`CATEGORY`）原樣保留**：規格明文「欄位與權限規則不變」，`STAGE` 選單餵階段層類別（`parentCategoryId == null`）、`CATEGORY` 選單餵子層類別，直接對應舊 L1/L2 語意，不重新設計。
4. **`Task` 的 `UpdateRequest` 採「送整份表單」語意，不是舊 `WbsNodeDto.UpdateRequest` 的「null 表不動」局部更新**：因為看板 modal 一次編輯標題/描述/優先度/日期/所屬類別並一次送出（規格：「開 modal 編輯標題/描述/指派人/優先度/日期/所屬類別」），`categoryId: null` 必須明確代表「改成未歸類」而非「不變」，用局部更新語意會讓兩者無法區分。指派人與狀態仍走各自專用 PATCH 端點（`/assignee`、`/status`、`/move`），不进 `UpdateRequest`。
5. **`/status` 與 `/move` 的差異**：`/status` 是單純改狀態、新狀態欄位一律接到該欄最後（`sortOrder = 該狀態現有任務數`）；`/move` 是看板拖曳專用，帶目標欄內的插入位置（`sortOrder` 為目標索引），並對來源欄與目標欄各自重新編號 `0..n-1`（理由見 Task 5 的 `moveTask` 實作說明，比照舊 `WbsNodeService.reorder`/前端 `moveNode` 的「整批重編號」模式，避免 sort_order 重複值讓 Hibernate 髒檢查誤判無變化而不送出 UPDATE）。

---

## ⚠️ Task 1-2 與 Task 7 之間的中間態

Task 1（DDL）與 Task 2（種子資料）把資料庫換成新 schema，但應用程式的 Java 程式碼要到 Task 7 刪除 `wbs` package、Task 3-6 補齊 `task` package 才會同步换成新模型。在 Task 7 完成之前，`mvn spring-boot:run` 對著 Task 1/2 重置過的資料庫會啟動失敗或編譯失敗（entity 仍指向已被刪除的表）——這是預期中的暫態，不是缺陷。**Task 1、Task 2 的驗證步驟因此只做 `psql` 層級確認，不做應用程式啟動或瀏覽器操作**；第一次能真正啟動應用程式驗證的時間點是 Task 7 完成之後。

## Task 1: 重寫 DDL（`sql/01_ddl.sql`）

**Files:**
- Modify: `sql/01_ddl.sql`

**Interfaces:**
- Produces: `task_category_presets`（欄位同舊 `wbs_presets`：`id, type, name, sort_order, enabled, section_id, created_at`）、`task_categories`（`id, project_id, parent_category_id, name, sort_order, created_at, updated_at`）、`tasks`（`id, project_id, category_id, title, description, assignee_id, status, priority, start_date, due_date, sort_order, created_at, updated_at`）三張表，供 Task 2-6 的 entity/repository/測試引用。

- [ ] **Step 1: 移除 `wbs_presets`／`wbs_nodes` 區塊，新增三張新表**

把 `sql/01_ddl.sql` 中從 `-- wbs_presets：` 註解區塊到檔尾 `wbs_nodes` 的 `CREATE INDEX` 全部替換成：

```sql
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
```

同時把檔頭的 `wbs_nodes：核心表，節點即任務` 相關舊註解一併移除（該表不存在了）。

- [ ] **Step 2: 容器化驗證新 DDL 可正確初始化**

```bash
docker compose down -v && docker compose up -d
```

Expected: 容器啟動且 healthy；`docker exec spring-missionboard-db-1 psql -U missionboard -d missionboard -c "\dt"` 顯示 `task_category_presets`、`task_categories`、`tasks`，不再有 `wbs_presets`/`wbs_nodes`。

- [ ] **Step 3: Commit**

```bash
git add sql/01_ddl.sql
git commit -m "refactor: DDL 改用 tasks/task_categories/task_category_presets 取代 wbs_nodes/wbs_presets"
```

---

## Task 2: 重寫測試種子資料（`sql/02_test_data.sql`）

**Files:**
- Modify: `sql/02_test_data.sql`

**Interfaces:**
- Consumes: Task 1 的三張新表
- Produces: 專案 id=1（`leader`/`member` 為成員，`member2` 不加入，維持既有跨科隔離測試素材）、`task_categories` id=1/2（示範兩層）、3 筆 `tasks`（含已歸類/未歸類、三種狀態、含指派/不含指派），供 Task 5 的 service/controller 測試與看板實測使用。

- [ ] **Step 1: 移除 `wbs_presets` 種子區塊，新增專案／成員／類別／任務種子**

把 `sql/02_test_data.sql` 檔尾的 `wbs_presets 種子` 區塊替換成：

```sql
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
```

- [ ] **Step 2: 容器化驗證種子資料正確載入（僅 psql 層級，見上方「Task 1-2 與 Task 7 之間的中間態」說明）**

```bash
docker compose down -v && docker compose up -d
docker exec spring-missionboard-db-1 psql -U missionboard -d missionboard -c "SELECT id, title, status, category_id, assignee_id FROM tasks ORDER BY id;"
docker exec spring-missionboard-db-1 psql -U missionboard -d missionboard -c "SELECT id, name, section_id, owner_id FROM projects;"
```

Expected: 3 筆任務資料如上；`projects` 可見 id=1「MissionBoard 範例專案」。此步驟**不**啟動 `mvn spring-boot:run` 或用瀏覽器驗證——應用程式要到 Task 7 完成後才能對著新 schema 正常啟動。

- [ ] **Step 3: Commit**

```bash
git add sql/02_test_data.sql
git commit -m "feat: 種子資料改為 task_categories/tasks，含一個範例專案供看板測試"
```

---

## Task 3: `TaskCategoryPreset` 後端（entity/repository/service/controller/dto，直接改名自 `WbsPreset`）

**Files:**
- Create: `src/main/java/com/missionboard/task/TaskCategoryPreset.java`
- Create: `src/main/java/com/missionboard/task/TaskCategoryPresetRepository.java`
- Create: `src/main/java/com/missionboard/task/TaskCategoryPresetService.java`
- Create: `src/main/java/com/missionboard/task/TaskCategoryPresetController.java`
- Create: `src/main/java/com/missionboard/task/TaskCategoryPresetDto.java`
- Test: `src/test/java/com/missionboard/task/TaskCategoryPresetServiceTest.java`
- Test: `src/test/java/com/missionboard/task/TaskCategoryPresetControllerTest.java`
- Test: `src/test/java/com/missionboard/task/TaskCategoryPresetRepositoryTest.java`

**Interfaces:**
- Consumes: `com.missionboard.department.Department`、`com.missionboard.user.User`（含 `User.Role.SECTION_CHIEF`）
- Produces: `TaskCategoryPresetService.list(Type, Long sectionId)`、`.create(Type, String name, int sortOrder, User actor)`、`.update(Long id, String name, Integer sortOrder, Boolean enabled, User actor)`、`.delete(Long id, User actor)`；`TaskCategoryPreset.Type` enum（`STAGE`, `CATEGORY`）供 Task 4 的 `TaskCategoryService` 使用；`GET/POST/PUT/DELETE /api/task-category-presets` 端點供前端與 Task 4 引用。

- [ ] **Step 1: 建立 entity（`WbsPreset.java` 原樣改名改 package，`@Table` 改 `task_category_presets`）**

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_category_presets")
@Getter
@Setter
public class TaskCategoryPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled = true;

    // NULL = 系統全域預設；有值 = 該科自訂（科別隔離）
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    private Department section;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum Type {
        STAGE, CATEGORY
    }
}
```

- [ ] **Step 2: 建立 repository（改名自 `WbsPresetRepository`）**

```java
package com.missionboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskCategoryPresetRepository extends JpaRepository<TaskCategoryPreset, Long> {

    // 全域（section IS NULL）＋指定科別自訂合併，僅回傳啟用中的項目，依 sortOrder 排序
    @Query("SELECT p FROM TaskCategoryPreset p WHERE p.type = :type AND p.enabled = true "
        + "AND (p.section IS NULL OR p.section.id = :sectionId) ORDER BY p.sortOrder")
    List<TaskCategoryPreset> findVisiblePresets(@Param("type") TaskCategoryPreset.Type type, @Param("sectionId") Long sectionId);
}
```

- [ ] **Step 3: 建立 DTO（改名自 `WbsPresetDto`）**

```java
package com.missionboard.task;

public class TaskCategoryPresetDto {

    public record Response(Long id, TaskCategoryPreset.Type type, String name, int sortOrder,
                            boolean enabled, Long sectionId) {
        public static Response from(TaskCategoryPreset preset) {
            return new Response(
                preset.getId(), preset.getType(), preset.getName(), preset.getSortOrder(),
                preset.isEnabled(), preset.getSection() != null ? preset.getSection().getId() : null
            );
        }
    }

    public record CreateRequest(TaskCategoryPreset.Type type, String name, int sortOrder) {
    }

    public record UpdateRequest(String name, Integer sortOrder, Boolean enabled) {
    }
}
```

- [ ] **Step 4: 撰寫 service 測試（先寫失敗測試）**

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TaskCategoryPresetServiceTest {

    @Autowired
    private TaskCategoryPresetService taskCategoryPresetService;
    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;

    private Department sectionA;
    private Department sectionB;
    private User chiefA;
    private User chiefB;
    private User leaderA;

    @BeforeEach
    void setUp() {
        sectionA = newDept("系統科A");
        sectionB = newDept("系統科B");
        chiefA = newUser("chiefA", User.Role.SECTION_CHIEF, sectionA);
        chiefB = newUser("chiefB", User.Role.SECTION_CHIEF, sectionB);
        leaderA = newUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
    }

    @Test
    void sectionChiefCreatesPresetScopedToOwnSection() {
        TaskCategoryPreset preset = taskCategoryPresetService.create(TaskCategoryPreset.Type.CATEGORY, "測試類別", 1, chiefA);
        assertThat(preset.getSection().getId()).isEqualTo(sectionA.getId());
    }

    @Test
    void nonChiefCannotCreatePreset() {
        assertThatThrownBy(() -> taskCategoryPresetService.create(TaskCategoryPreset.Type.CATEGORY, "測試類別", 1, leaderA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCannotUpdateOtherSectionPreset() {
        TaskCategoryPreset preset = taskCategoryPresetService.create(TaskCategoryPreset.Type.CATEGORY, "A科類別", 1, chiefA);
        assertThatThrownBy(() -> taskCategoryPresetService.update(preset.getId(), "改名", null, null, chiefB))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCannotUpdateGlobalPreset() {
        TaskCategoryPreset global = new TaskCategoryPreset();
        global.setType(TaskCategoryPreset.Type.STAGE);
        global.setName("全域階段");
        global.setSortOrder(1);
        taskCategoryPresetRepository.save(global);
        assertThatThrownBy(() -> taskCategoryPresetService.update(global.getId(), "改名", null, null, chiefA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCanDeleteOwnSectionPreset() {
        TaskCategoryPreset preset = taskCategoryPresetService.create(TaskCategoryPreset.Type.CATEGORY, "待刪類別", 1, chiefA);
        taskCategoryPresetService.delete(preset.getId(), chiefA);
        assertThat(taskCategoryPresetRepository.findById(preset.getId())).isEmpty();
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return departmentRepository.save(d);
    }

    private User newUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("x");
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.save(u);
    }
}
```

- [ ] **Step 5: 執行測試確認失敗（`TaskCategoryPresetService` 尚不存在）**

```bash
mvn test -Dtest=TaskCategoryPresetServiceTest
```

Expected: 編譯失敗，找不到 `TaskCategoryPresetService`。

- [ ] **Step 6: 實作 service（改名自 `WbsPresetService`，邏輯不變）**

```java
package com.missionboard.task;

import com.missionboard.user.User;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskCategoryPresetService {

    private final TaskCategoryPresetRepository taskCategoryPresetRepository;

    public List<TaskCategoryPreset> list(TaskCategoryPreset.Type type, Long sectionId) {
        return taskCategoryPresetRepository.findVisiblePresets(type, sectionId);
    }

    // 只有科長可管理選單，且新建項目一律歸屬建立者自己的科別
    @Transactional
    public TaskCategoryPreset create(TaskCategoryPreset.Type type, String name, int sortOrder, User actor) {
        requireSectionChief(actor);
        TaskCategoryPreset preset = new TaskCategoryPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(sortOrder);
        preset.setSection(actor.getDepartment());
        return taskCategoryPresetRepository.save(preset);
    }

    @Transactional
    public TaskCategoryPreset update(Long presetId, String name, Integer sortOrder, Boolean enabled, User actor) {
        TaskCategoryPreset preset = getOwnedPreset(presetId, actor);
        if (name != null) preset.setName(name);
        if (sortOrder != null) preset.setSortOrder(sortOrder);
        if (enabled != null) preset.setEnabled(enabled);
        return taskCategoryPresetRepository.save(preset);
    }

    @Transactional
    public void delete(Long presetId, User actor) {
        TaskCategoryPreset preset = getOwnedPreset(presetId, actor);
        taskCategoryPresetRepository.delete(preset);
    }

    // 全域預設（section 為 null）任何角色皆不可修改；自訂項目僅同科科長可管理
    private TaskCategoryPreset getOwnedPreset(Long presetId, User actor) {
        requireSectionChief(actor);
        TaskCategoryPreset preset = taskCategoryPresetRepository.findById(presetId)
            .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
        if (preset.getSection() == null || !preset.getSection().getId().equals(actor.getDepartment().getId())) {
            throw new SecurityException("只能管理自己科別的選單項目");
        }
        return preset;
    }

    private void requireSectionChief(User actor) {
        if (actor.getRole() != User.Role.SECTION_CHIEF) {
            throw new SecurityException("只有科長可管理選單");
        }
    }
}
```

- [ ] **Step 7: 執行測試確認通過**

```bash
mvn test -Dtest=TaskCategoryPresetServiceTest
```

Expected: 5 個測試全數 PASS。

- [ ] **Step 8: 實作 controller（改名自 `WbsPresetController`，端點改 `/api/task-category-presets`）**

```java
package com.missionboard.task;

import com.missionboard.common.ApiResponse;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class TaskCategoryPresetController {

    private final TaskCategoryPresetService taskCategoryPresetService;
    private final UserRepository userRepository;

    // 不傳 sectionId 時預設用呼叫者自己的部門，方便前端下拉選單直接查詢
    @GetMapping("/api/task-category-presets")
    public ApiResponse<List<TaskCategoryPresetDto.Response>> list(
            @RequestParam TaskCategoryPreset.Type type,
            @RequestParam(required = false) Long sectionId,
            Principal principal) {
        User user = currentUser(principal);
        Long effectiveSectionId = sectionId != null ? sectionId
            : (user.getDepartment() != null ? user.getDepartment().getId() : null);
        List<TaskCategoryPresetDto.Response> result = taskCategoryPresetService.list(type, effectiveSectionId)
            .stream().map(TaskCategoryPresetDto.Response::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/task-category-presets")
    public ApiResponse<TaskCategoryPresetDto.Response> create(
            @RequestBody TaskCategoryPresetDto.CreateRequest req, Principal principal) {
        User user = currentUser(principal);
        TaskCategoryPreset preset = taskCategoryPresetService.create(req.type(), req.name(), req.sortOrder(), user);
        return ApiResponse.ok(TaskCategoryPresetDto.Response.from(preset));
    }

    @PutMapping("/api/task-category-presets/{id}")
    public ApiResponse<TaskCategoryPresetDto.Response> update(@PathVariable Long id,
            @RequestBody TaskCategoryPresetDto.UpdateRequest req, Principal principal) {
        User user = currentUser(principal);
        TaskCategoryPreset preset = taskCategoryPresetService.update(id, req.name(), req.sortOrder(), req.enabled(), user);
        return ApiResponse.ok(TaskCategoryPresetDto.Response.from(preset));
    }

    @DeleteMapping("/api/task-category-presets/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        taskCategoryPresetService.delete(id, user);
        return ApiResponse.ok(null);
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
```

- [ ] **Step 9: 撰寫 controller 測試**

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskCategoryPresetControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Department sectionA = new Department();
        sectionA.setName("系統科A");
        departmentRepository.save(sectionA);

        User chiefA = new User();
        chiefA.setUsername("chiefA");
        chiefA.setPassword(passwordEncoder.encode("password123"));
        chiefA.setDisplayName("chiefA");
        chiefA.setRole(User.Role.SECTION_CHIEF);
        chiefA.setDepartment(sectionA);
        userRepository.save(chiefA);

        User leaderA = new User();
        leaderA.setUsername("leaderA");
        leaderA.setPassword(passwordEncoder.encode("password123"));
        leaderA.setDisplayName("leaderA");
        leaderA.setRole(User.Role.PROJECT_LEADER);
        leaderA.setDepartment(sectionA);
        userRepository.save(leaderA);

        TaskCategoryPreset global = new TaskCategoryPreset();
        global.setType(TaskCategoryPreset.Type.STAGE);
        global.setName("SIT");
        global.setSortOrder(1);
        taskCategoryPresetRepository.save(global);
    }

    private Cookie loginAs(String username) throws Exception {
        return mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andReturn().getResponse().getCookie("SESSION");
    }

    @Test
    void listReturnsVisiblePresetsForType() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(get("/api/task-category-presets").param("type", "STAGE").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("SIT"));
    }

    @Test
    void sectionChiefCreatesPreset() throws Exception {
        Cookie session = loginAs("chiefA");
        mockMvc.perform(post("/api/task-category-presets").cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"type\":\"CATEGORY\",\"name\":\"新類別\",\"sortOrder\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("新類別"));
    }

    @Test
    void nonChiefCannotCreatePreset() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/task-category-presets").cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"type\":\"CATEGORY\",\"name\":\"新類別\",\"sortOrder\":1}"))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 10: 撰寫 repository 測試**

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskCategoryPresetRepositoryTest {

    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesGlobalPresetWithNullSection() {
        TaskCategoryPreset preset = new TaskCategoryPreset();
        preset.setType(TaskCategoryPreset.Type.STAGE);
        preset.setName("SIT");
        preset.setSortOrder(1);
        TaskCategoryPreset saved = taskCategoryPresetRepository.save(preset);

        assertThat(saved.getSection()).isNull();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getType()).isEqualTo(TaskCategoryPreset.Type.STAGE);
    }

    @Test
    void findsVisiblePresetsMergesGlobalAndSectionSpecific() {
        Department sectionA = new Department();
        sectionA.setName("系統科A");
        departmentRepository.save(sectionA);

        TaskCategoryPreset global = new TaskCategoryPreset();
        global.setType(TaskCategoryPreset.Type.STAGE);
        global.setName("SIT");
        global.setSortOrder(1);
        taskCategoryPresetRepository.save(global);

        TaskCategoryPreset sectionSpecific = new TaskCategoryPreset();
        sectionSpecific.setType(TaskCategoryPreset.Type.STAGE);
        sectionSpecific.setName("系統科專用階段");
        sectionSpecific.setSortOrder(2);
        sectionSpecific.setSection(sectionA);
        taskCategoryPresetRepository.save(sectionSpecific);

        TaskCategoryPreset disabled = new TaskCategoryPreset();
        disabled.setType(TaskCategoryPreset.Type.STAGE);
        disabled.setName("已停用");
        disabled.setSortOrder(3);
        disabled.setEnabled(false);
        taskCategoryPresetRepository.save(disabled);

        List<TaskCategoryPreset> visible = taskCategoryPresetRepository
            .findVisiblePresets(TaskCategoryPreset.Type.STAGE, sectionA.getId());

        assertThat(visible).extracting(TaskCategoryPreset::getName)
            .containsExactly("SIT", "系統科專用階段");
    }
}
```

- [ ] **Step 11: 執行全部三個測試檔確認通過**

```bash
mvn test -Dtest=TaskCategoryPresetServiceTest,TaskCategoryPresetControllerTest,TaskCategoryPresetRepositoryTest
```

Expected: 全數 PASS。

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/missionboard/task/TaskCategoryPreset*.java src/test/java/com/missionboard/task/TaskCategoryPreset*.java
git commit -m "feat: 新增 TaskCategoryPreset（改名自 WbsPreset），選單管理邏輯不變"
```

---

## Task 4: `TaskCategory` 後端（entity/repository/service/controller/dto，兩層深度限制）

**Files:**
- Create: `src/main/java/com/missionboard/task/TaskCategory.java`
- Create: `src/main/java/com/missionboard/task/TaskCategoryRepository.java`
- Create: `src/main/java/com/missionboard/task/TaskCategoryService.java`
- Create: `src/main/java/com/missionboard/task/TaskCategoryController.java`
- Create: `src/main/java/com/missionboard/task/TaskCategoryDto.java`
- Test: `src/test/java/com/missionboard/task/TaskCategoryServiceTest.java`
- Test: `src/test/java/com/missionboard/task/TaskCategoryControllerTest.java`

**Interfaces:**
- Consumes: `TaskCategoryPreset`/`TaskCategoryPresetRepository`（Task 3）、`com.missionboard.project.Project`/`ProjectService.getById`
- Produces: `TaskCategoryService.getCategoryInProject(Long projectId, Long categoryId): TaskCategory`（**Task 5 的 `TaskService` 會呼叫此方法**驗證 `category_id` 屬於同一專案）、`.list(Long projectId): List<TaskCategory>`、`.create/.update/.delete`；`GET/POST /api/projects/{id}/task-categories`、`PUT/DELETE .../task-categories/{categoryId}` 端點。

- [ ] **Step 1: 建立 entity**

```java
package com.missionboard.task;

import com.missionboard.project.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_categories")
@Getter
@Setter
public class TaskCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // NULL = 階段層；有值 = 類別層，掛在某階段下。刪階段連帶刪其下類別，
    // 對應 sql/01_ddl.sql 的 ON DELETE CASCADE；標註後 H2 測試庫（entity 建表）行為與正式 Postgres DDL 一致
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_category_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TaskCategory parentCategory;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 建立 repository**

```java
package com.missionboard.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskCategoryRepository extends JpaRepository<TaskCategory, Long> {
    List<TaskCategory> findByProjectId(Long projectId);
}
```

- [ ] **Step 3: 建立 DTO**

```java
package com.missionboard.task;

public class TaskCategoryDto {

    public record CreateRequest(Long parentCategoryId, Long presetId, Integer sortOrder) {
    }

    public record UpdateRequest(String name, Integer sortOrder) {
    }

    public record Response(Long id, Long parentCategoryId, String name, int sortOrder) {
        public static Response from(TaskCategory category) {
            Long parentId = category.getParentCategory() != null ? category.getParentCategory().getId() : null;
            return new Response(category.getId(), parentId, category.getName(), category.getSortOrder());
        }
    }
}
```

- [ ] **Step 4: 撰寫 service 測試（先寫失敗測試，涵蓋兩層深度限制與跨專案 IDOR）**

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TaskCategoryServiceTest {

    @Autowired
    private TaskCategoryService taskCategoryService;
    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;

    private Project project;
    private Project otherProject;
    private TaskCategoryPreset stagePreset;
    private TaskCategoryPreset categoryPreset;
    private TaskCategoryPreset otherSectionStagePreset;

    @BeforeEach
    void setUp() {
        Department sectionA = newDept("系統科A");
        Department sectionB = newDept("系統科B");
        User owner = newUser("owner", sectionA);

        project = newProject(sectionA, owner);
        otherProject = newProject(sectionA, owner);

        stagePreset = newPreset(TaskCategoryPreset.Type.STAGE, "SIT", null);
        categoryPreset = newPreset(TaskCategoryPreset.Type.CATEGORY, "程式開發", null);
        otherSectionStagePreset = newPreset(TaskCategoryPreset.Type.STAGE, "B科限定", sectionB);
    }

    @Test
    void createsStageLevelCategoryFromStagePresetSnapshot() {
        TaskCategory category = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        assertThat(category.getName()).isEqualTo("SIT");
        assertThat(category.getParentCategory()).isNull();
    }

    @Test
    void createsSubCategoryUnderStageLevelParent() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null));
        assertThat(sub.getParentCategory().getId()).isEqualTo(stage.getId());
    }

    @Test
    void rejectsThirdLevelCategory() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null));

        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(sub.getId(), categoryPreset.getId(), null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPresetFromOtherSectionNotVisibleToThisProject() {
        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(null, otherSectionStagePreset.getId(), null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsParentCategoryFromAnotherProject() {
        TaskCategory foreignStage = taskCategoryService.create(otherProject.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));

        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(foreignStage.getId(), categoryPreset.getId(), null)))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void updatesNameAndSortOrder() {
        TaskCategory category = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        TaskCategory updated = taskCategoryService.update(project.getId(), category.getId(),
            new TaskCategoryDto.UpdateRequest("改名後階段", 5));
        assertThat(updated.getName()).isEqualTo("改名後階段");
        assertThat(updated.getSortOrder()).isEqualTo(5);
    }

    @Test
    void deleteCascadesToChildCategory() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null));

        taskCategoryService.delete(project.getId(), stage.getId());

        assertThat(taskCategoryService.list(project.getId())).isEmpty();
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return departmentRepository.save(d);
    }

    private User newUser(String username, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("x");
        u.setDisplayName(username);
        u.setRole(User.Role.PROJECT_LEADER);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private Project newProject(Department section, User owner) {
        Project p = new Project();
        p.setName("專案-" + System.nanoTime());
        p.setSection(section);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        return projectRepository.save(p);
    }

    private TaskCategoryPreset newPreset(TaskCategoryPreset.Type type, String name, Department section) {
        TaskCategoryPreset preset = new TaskCategoryPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(0);
        preset.setSection(section);
        return taskCategoryPresetRepository.save(preset);
    }
}
```

- [ ] **Step 5: 執行測試確認失敗**

```bash
mvn test -Dtest=TaskCategoryServiceTest
```

Expected: 編譯失敗，找不到 `TaskCategoryService`。

- [ ] **Step 6: 實作 service**

```java
package com.missionboard.task;

import com.missionboard.project.Project;
import com.missionboard.project.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskCategoryService {

    private final TaskCategoryRepository taskCategoryRepository;
    private final TaskCategoryPresetRepository taskCategoryPresetRepository;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public List<TaskCategory> list(Long projectId) {
        return taskCategoryRepository.findByProjectId(projectId);
    }

    // 建立類別：從選單快照名稱；深度上限兩層在此強制（service 層驗證，不用 DB CHECK）
    @Transactional
    public TaskCategory create(Long projectId, TaskCategoryDto.CreateRequest req) {
        Project project = projectService.getById(projectId);
        TaskCategory parent = null;
        if (req.parentCategoryId() != null) {
            parent = getCategoryInProject(projectId, req.parentCategoryId());
            if (parent.getParentCategory() != null) {
                throw new IllegalArgumentException("已達第二層，無法在類別下新增子類別");
            }
        }

        TaskCategoryPreset preset = taskCategoryPresetRepository.findById(req.presetId())
            .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
        TaskCategoryPreset.Type expectedType = parent == null
            ? TaskCategoryPreset.Type.STAGE : TaskCategoryPreset.Type.CATEGORY;
        if (preset.getType() != expectedType) {
            throw new IllegalArgumentException("選單項目型別不符");
        }
        if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
            throw new IllegalArgumentException("選單項目不屬於此專案科別");
        }

        TaskCategory category = new TaskCategory();
        category.setProject(project);
        category.setParentCategory(parent);
        category.setName(preset.getName());
        category.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        return taskCategoryRepository.save(category);
    }

    @Transactional
    public TaskCategory update(Long projectId, Long categoryId, TaskCategoryDto.UpdateRequest req) {
        TaskCategory category = getCategoryInProject(projectId, categoryId);
        if (req.name() != null) category.setName(req.name());
        if (req.sortOrder() != null) category.setSortOrder(req.sortOrder());
        return taskCategoryRepository.save(category);
    }

    @Transactional
    public void delete(Long projectId, Long categoryId) {
        TaskCategory category = getCategoryInProject(projectId, categoryId);
        taskCategoryRepository.delete(category);
    }

    // 統一的 IDOR 防護：確認類別存在且屬於路徑上的專案。Task 5 的 TaskService 建立/更新任務歸類時也呼叫此方法。
    public TaskCategory getCategoryInProject(Long projectId, Long categoryId) {
        TaskCategory category = taskCategoryRepository.findById(categoryId)
            .orElseThrow(() -> new EntityNotFoundException("類別不存在"));
        if (!category.getProject().getId().equals(projectId)) {
            throw new SecurityException("類別不屬於此專案");
        }
        return category;
    }
}
```

- [ ] **Step 7: 執行測試確認通過**

```bash
mvn test -Dtest=TaskCategoryServiceTest
```

Expected: 7 個測試全數 PASS。

- [ ] **Step 8: 實作 controller**

```java
package com.missionboard.task;

import com.missionboard.common.ApiResponse;
import com.missionboard.project.ProjectService;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class TaskCategoryController {

    private final TaskCategoryService taskCategoryService;
    private final ProjectService projectService;
    private final UserRepository userRepository;

    @GetMapping("/api/projects/{projectId}/task-categories")
    public ApiResponse<List<TaskCategoryDto.Response>> list(@PathVariable Long projectId, Principal principal) {
        checkRead(projectId, principal);
        return ApiResponse.ok(taskCategoryService.list(projectId).stream().map(TaskCategoryDto.Response::from).toList());
    }

    @PostMapping("/api/projects/{projectId}/task-categories")
    public ApiResponse<TaskCategoryDto.Response> create(@PathVariable Long projectId,
            @RequestBody TaskCategoryDto.CreateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        return ApiResponse.ok(TaskCategoryDto.Response.from(taskCategoryService.create(projectId, req)));
    }

    @PutMapping("/api/projects/{projectId}/task-categories/{categoryId}")
    public ApiResponse<TaskCategoryDto.Response> update(@PathVariable Long projectId, @PathVariable Long categoryId,
            @RequestBody TaskCategoryDto.UpdateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        return ApiResponse.ok(TaskCategoryDto.Response.from(taskCategoryService.update(projectId, categoryId, req)));
    }

    @DeleteMapping("/api/projects/{projectId}/task-categories/{categoryId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long categoryId, Principal principal) {
        checkWrite(projectId, principal);
        taskCategoryService.delete(projectId, categoryId);
        return ApiResponse.ok(null);
    }

    private void checkRead(Long projectId, Principal principal) {
        if (!projectService.canRead(projectId, currentUser(principal))) {
            throw new SecurityException("無存取權限");
        }
    }

    private void checkWrite(Long projectId, Principal principal) {
        if (!projectService.canWrite(projectId, currentUser(principal))) {
            throw new SecurityException("無編輯權限");
        }
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
```

- [ ] **Step 9: 撰寫 controller 測試（IDOR：跨專案類別／無權限使用者）**

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectMember;
import com.missionboard.project.ProjectMemberId;
import com.missionboard.project.ProjectMemberRepository;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;
    private TaskCategoryPreset stagePreset;

    @BeforeEach
    void setUp() throws Exception {
        Department sectionA = new Department();
        sectionA.setName("系統科A");
        departmentRepository.save(sectionA);
        Department sectionB = new Department();
        sectionB.setName("系統科B");
        departmentRepository.save(sectionB);

        User leaderA = newUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
        User chiefB = newUser("chiefB", User.Role.SECTION_CHIEF, sectionB);

        project = new Project();
        project.setName("專案A");
        project.setSection(sectionA);
        project.setOwner(leaderA);
        project.setCreatedBy(leaderA);
        projectRepository.save(project);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), leaderA.getId()));
        pm.setAssignedBy(leaderA);
        projectMemberRepository.save(pm);

        stagePreset = new TaskCategoryPreset();
        stagePreset.setType(TaskCategoryPreset.Type.STAGE);
        stagePreset.setName("SIT");
        stagePreset.setSortOrder(1);
        taskCategoryPresetRepository.save(stagePreset);
    }

    private User newUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private Cookie loginAs(String username) throws Exception {
        return mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andReturn().getResponse().getCookie("SESSION");
    }

    @Test
    void createStageLevelCategoryFromPreset() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/task-categories", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":null,\"presetId\":" + stagePreset.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("SIT"));
    }

    @Test
    void createDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");
        mockMvc.perform(post("/api/projects/{id}/task-categories", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":null,\"presetId\":" + stagePreset.getId() + "}"))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 10: 執行全部測試確認通過**

```bash
mvn test -Dtest=TaskCategoryServiceTest,TaskCategoryControllerTest
```

Expected: 全數 PASS。

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/missionboard/task/TaskCategory*.java src/test/java/com/missionboard/task/TaskCategory*Test.java
git commit -m "feat: 新增 TaskCategory，選配兩層深度上限由 service 層驗證"
```

---

## Task 5: `Task` 後端（entity/repository/service/controller/dto，含看板 move 端點）

**Files:**
- Create: `src/main/java/com/missionboard/task/Task.java`
- Create: `src/main/java/com/missionboard/task/TaskRepository.java`
- Create: `src/main/java/com/missionboard/task/TaskService.java`
- Create: `src/main/java/com/missionboard/task/TaskController.java`
- Create: `src/main/java/com/missionboard/task/TaskDto.java`
- Test: `src/test/java/com/missionboard/task/TaskServiceTest.java`
- Test: `src/test/java/com/missionboard/task/TaskControllerTest.java`
- Test: `src/test/java/com/missionboard/task/TaskRepositoryTest.java`

**Interfaces:**
- Consumes: `TaskCategoryService.getCategoryInProject`（Task 4）、`ProjectService.getById/isMember/canRead/canWrite`、`UserRepository`
- Produces: `TaskRepository.clearAssigneeForUserInProject(Long, Long)`（**Task 6 的 `ProjectService.removeMember` 會呼叫此方法**，取代舊 `WbsNodeRepository` 呼叫）；`TaskDto.Response`（前端看板 Task 9 消費的資料形狀）；端點 `GET/POST /api/projects/{id}/tasks`、`PUT/DELETE .../tasks/{taskId}`、`PATCH .../tasks/{taskId}/status`、`PATCH .../tasks/{taskId}/assignee`、`PATCH .../tasks/{taskId}/move`。

- [ ] **Step 1: 建立 entity**

```java
package com.missionboard.task;

import com.missionboard.project.Project;
import com.missionboard.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "tasks")
@Getter
@Setter
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // NULL = 未歸類。刪除類別時任務落回未歸類，對應 sql/01_ddl.sql 的 ON DELETE SET NULL；
    // 標註後 H2 測試庫（entity 建表）行為與正式 Postgres DDL 一致
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private TaskCategory category;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Priority priority;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum Status {
        NOT_STARTED, IN_PROGRESS, DONE
    }

    public enum Priority {
        HIGH, MEDIUM, LOW
    }
}
```

- [ ] **Step 2: 建立 repository**

```java
package com.missionboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByProjectId(Long projectId);

    List<Task> findByProjectIdAndStatusOrderBySortOrder(Long projectId, Task.Status status);

    // 移除專案成員時連動清除其指派；clearAutomatically 避免呼叫端讀到 stale 的一級快取
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Task t SET t.assignee = null WHERE t.project.id = :projectId AND t.assignee.id = :userId")
    void clearAssigneeForUserInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
```

- [ ] **Step 3: 建立 DTO**

```java
package com.missionboard.task;

import java.time.LocalDate;

public class TaskDto {

    public record CreateRequest(Long categoryId, String title, String description, Integer sortOrder) {
    }

    // 看板 modal 一次送整份表單：categoryId 為 null 明確代表「改成未歸類」，不是「不變」
    public record UpdateRequest(String title, String description, Long categoryId, String priority,
                                 LocalDate startDate, LocalDate dueDate) {
    }

    public record Response(Long id, Long categoryId, String title, String description,
                            Long assigneeId, String assigneeDisplayName,
                            String status, String priority,
                            LocalDate startDate, LocalDate dueDate, int sortOrder) {
    }

    public record StatusRequest(String status) {
    }

    public record AssigneeRequest(Long assigneeId) {
    }

    // sortOrder：目標狀態欄內的插入索引（0-based）
    public record MoveRequest(String status, int sortOrder) {
    }
}
```

- [ ] **Step 4: 撰寫 service 測試（先寫失敗測試，涵蓋建立/更新/刪除/move 重編號/assignee 成員檢查/跨專案類別驗證）**

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectMember;
import com.missionboard.project.ProjectMemberId;
import com.missionboard.project.ProjectMemberRepository;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class TaskServiceTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskCategoryRepository taskCategoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;

    private Project project;
    private Project otherProject;
    private User member;
    private User outsider;
    private TaskCategory category;
    private TaskCategory foreignCategory;

    @BeforeEach
    void setUp() {
        Department section = newDept("系統科");
        User owner = newUser("owner", section);
        member = newUser("member", section);
        outsider = newUser("outsider", section);

        project = newProject(section, owner);
        otherProject = newProject(section, owner);

        addMember(project, owner);
        addMember(project, member);

        category = newCategory(project, null, "SIT");
        foreignCategory = newCategory(otherProject, null, "跨專案階段");
    }

    @Test
    void createsTaskWithoutCategory() {
        Task task = taskService.createTask(project.getId(),
            new TaskDto.CreateRequest(null, "整理需求訪談紀錄", null, null));
        assertThat(task.getCategory()).isNull();
        assertThat(task.getStatus()).isEqualTo(Task.Status.NOT_STARTED);
    }

    @Test
    void createsTaskWithCategory() {
        Task task = taskService.createTask(project.getId(),
            new TaskDto.CreateRequest(category.getId(), "設計登入頁", "UI 設計稿", null));
        assertThat(task.getCategory().getId()).isEqualTo(category.getId());
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> taskService.createTask(project.getId(),
                new TaskDto.CreateRequest(null, "  ", null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCategoryFromAnotherProject() {
        assertThatThrownBy(() -> taskService.createTask(project.getId(),
                new TaskDto.CreateRequest(foreignCategory.getId(), "任務", null, null)))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void newTaskAppendsToBottomOfNotStartedColumn() {
        taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務一", null, null));
        Task second = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務二", null, null));
        assertThat(second.getSortOrder()).isEqualTo(1);
    }

    @Test
    void updateReplacesCategoryWithNullToUnclassify() {
        Task task = taskService.createTask(project.getId(),
            new TaskDto.CreateRequest(category.getId(), "任務", null, null));
        Task updated = taskService.updateTask(project.getId(), task.getId(),
            new TaskDto.UpdateRequest("任務", null, null, null, null, null));
        assertThat(updated.getCategory()).isNull();
    }

    @Test
    void updateSetsPriorityAndDates() {
        Task task = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務", null, null));
        Task updated = taskService.updateTask(project.getId(), task.getId(),
            new TaskDto.UpdateRequest("任務", "描述", null, "HIGH",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
        assertThat(updated.getPriority()).isEqualTo(Task.Priority.HIGH);
        assertThat(updated.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void deleteRemovesTask() {
        Task task = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務", null, null));
        taskService.deleteTask(project.getId(), task.getId());
        assertThat(taskRepository.findById(task.getId())).isEmpty();
    }

    @Test
    void deleteRejectsTaskFromAnotherProject() {
        Task foreignTask = taskService.createTask(otherProject.getId(), new TaskDto.CreateRequest(null, "任務", null, null));
        assertThatThrownBy(() -> taskService.deleteTask(project.getId(), foreignTask.getId()))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void updateStatusAppendsToBottomOfTargetColumn() {
        Task a = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務A", null, null));
        Task b = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務B", null, null));
        taskService.updateStatus(project.getId(), a.getId(), "IN_PROGRESS");
        Task updated = taskService.updateStatus(project.getId(), b.getId(), "IN_PROGRESS");
        assertThat(updated.getStatus()).isEqualTo(Task.Status.IN_PROGRESS);
        assertThat(updated.getSortOrder()).isEqualTo(1);
    }

    @Test
    void updateAssigneeRequiresProjectMembership() {
        Task task = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務", null, null));
        assertThatThrownBy(() -> taskService.updateAssignee(project.getId(), task.getId(), outsider.getId()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateAssigneeSucceedsForProjectMember() {
        Task task = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務", null, null));
        Task updated = taskService.updateAssignee(project.getId(), task.getId(), member.getId());
        assertThat(updated.getAssignee().getId()).isEqualTo(member.getId());
    }

    @Test
    void updateAssigneeWithNullClearsAssignment() {
        Task task = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "任務", null, null));
        taskService.updateAssignee(project.getId(), task.getId(), member.getId());
        Task cleared = taskService.updateAssignee(project.getId(), task.getId(), null);
        assertThat(cleared.getAssignee()).isNull();
    }

    // 同欄內移動：交換順序後整批重新編號 0..n-1（比照舊 WbsNodeService.reorder／前端 moveNode 的做法，
    // 避免 sort_order 出現重複值時 Hibernate 髒檢查誤判無變化而不送出 UPDATE）
    @Test
    void moveWithinSameColumnReindexesAllTasksInColumn() {
        Task a = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "A", null, null));
        Task b = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "B", null, null));
        Task c = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "C", null, null));

        taskService.moveTask(project.getId(), c.getId(), "NOT_STARTED", 0);

        var column = taskRepository.findByProjectIdAndStatusOrderBySortOrder(project.getId(), Task.Status.NOT_STARTED);
        assertThat(column).extracting(Task::getId).containsExactly(c.getId(), a.getId(), b.getId());
        assertThat(column).extracting(Task::getSortOrder).containsExactly(0, 1, 2);
    }

    // 跨欄移動：來源欄與目標欄都要重新編號，且不影響彼此
    @Test
    void moveAcrossColumnsReindexesBothSourceAndTargetColumns() {
        Task a = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "A", null, null));
        Task b = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "B", null, null));
        Task inProgress = taskService.createTask(project.getId(), new TaskDto.CreateRequest(null, "進行中任務", null, null));
        taskService.updateStatus(project.getId(), inProgress.getId(), "IN_PROGRESS");

        taskService.moveTask(project.getId(), a.getId(), "IN_PROGRESS", 0);

        var notStarted = taskRepository.findByProjectIdAndStatusOrderBySortOrder(project.getId(), Task.Status.NOT_STARTED);
        var inProgressColumn = taskRepository.findByProjectIdAndStatusOrderBySortOrder(project.getId(), Task.Status.IN_PROGRESS);

        assertThat(notStarted).extracting(Task::getId).containsExactly(b.getId());
        assertThat(notStarted).extracting(Task::getSortOrder).containsExactly(0);
        assertThat(inProgressColumn).extracting(Task::getId).containsExactly(a.getId(), inProgress.getId());
        assertThat(inProgressColumn).extracting(Task::getSortOrder).containsExactly(0, 1);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return departmentRepository.save(d);
    }

    private User newUser(String username, Department dept) {
        User u = new User();
        u.setUsername(username + System.nanoTime());
        u.setPassword("x");
        u.setDisplayName(username);
        u.setRole(User.Role.PROJECT_MEMBER);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private Project newProject(Department section, User owner) {
        Project p = new Project();
        p.setName("專案-" + System.nanoTime());
        p.setSection(section);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        return projectRepository.save(p);
    }

    private void addMember(Project project, User user) {
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), user.getId()));
        pm.setAssignedBy(user);
        projectMemberRepository.save(pm);
    }

    private TaskCategory newCategory(Project project, TaskCategory parent, String name) {
        TaskCategory c = new TaskCategory();
        c.setProject(project);
        c.setParentCategory(parent);
        c.setName(name);
        return taskCategoryRepository.save(c);
    }
}
```

- [ ] **Step 5: 執行測試確認失敗**

```bash
mvn test -Dtest=TaskServiceTest
```

Expected: 編譯失敗，找不到 `TaskService`。

- [ ] **Step 6: 實作 service**

```java
package com.missionboard.task;

import com.missionboard.project.Project;
import com.missionboard.project.ProjectService;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskCategoryService taskCategoryService;
    private final ProjectService projectService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<Task> list(Long projectId) {
        return taskRepository.findByProjectId(projectId);
    }

    // 建立任務：天生可不歸類（category_id 可為 null），新任務一律進 NOT_STARTED 欄的最底部
    @Transactional
    public Task createTask(Long projectId, TaskDto.CreateRequest req) {
        Project project = projectService.getById(projectId);
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("任務需要標題");
        }
        TaskCategory category = req.categoryId() != null
            ? taskCategoryService.getCategoryInProject(projectId, req.categoryId())
            : null;

        Task task = new Task();
        task.setProject(project);
        task.setCategory(category);
        task.setTitle(req.title());
        task.setDescription(req.description());
        task.setStatus(Task.Status.NOT_STARTED);
        task.setSortOrder(req.sortOrder() != null ? req.sortOrder()
            : nextSortOrderForStatus(projectId, Task.Status.NOT_STARTED));
        return taskRepository.save(task);
    }

    // modal 一次送整份表單：categoryId 為 null 明確代表「改成未歸類」，非局部更新語意
    @Transactional
    public Task updateTask(Long projectId, Long taskId, TaskDto.UpdateRequest req) {
        Task task = getTaskInProject(projectId, taskId);
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("任務需要標題");
        }
        TaskCategory category = req.categoryId() != null
            ? taskCategoryService.getCategoryInProject(projectId, req.categoryId())
            : null;

        task.setTitle(req.title());
        task.setDescription(req.description());
        task.setCategory(category);
        task.setPriority(req.priority() != null ? Task.Priority.valueOf(req.priority()) : null);
        task.setStartDate(req.startDate());
        task.setDueDate(req.dueDate());
        return taskRepository.save(task);
    }

    @Transactional
    public void deleteTask(Long projectId, Long taskId) {
        Task task = getTaskInProject(projectId, taskId);
        taskRepository.delete(task);
    }

    // 單純改狀態（非看板拖曳）：一律接到目標欄最底部
    @Transactional
    public Task updateStatus(Long projectId, Long taskId, String statusStr) {
        Task task = getTaskInProject(projectId, taskId);
        Task.Status newStatus = Task.Status.valueOf(statusStr);
        if (task.getStatus() != newStatus) {
            task.setSortOrder(nextSortOrderForStatus(projectId, newStatus));
        }
        task.setStatus(newStatus);
        return taskRepository.save(task);
    }

    @Transactional
    public Task updateAssignee(Long projectId, Long taskId, Long assigneeId) {
        Task task = getTaskInProject(projectId, taskId);
        if (assigneeId == null) {
            task.setAssignee(null);
        } else {
            if (!projectService.isMember(projectId, assigneeId)) {
                throw new IllegalArgumentException("指派對象必須是專案成員");
            }
            User assignee = userRepository.findById(assigneeId)
                .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
            task.setAssignee(assignee);
        }
        return taskRepository.save(task);
    }

    // 看板拖曳專用：targetIndex 為目標狀態欄內的插入位置（0-based）。
    // 插入後對目標欄整批重新編號 0..n-1；若跨欄移動，來源欄也整批重新編號——
    // 比照舊 WbsNodeService.reorder／前端 moveNode 的「整批重編號」模式：只交換兩者 sortOrder
    // 在既有資料重複值（如皆為 0）時會產生相同 payload，Hibernate 髒檢查判斷無變化而不送出 UPDATE，
    // 導致移動操作無聲失效；全量重編號同時能自我修復既有的重複值。
    @Transactional
    public Task moveTask(Long projectId, Long taskId, String statusStr, int targetIndex) {
        Task task = getTaskInProject(projectId, taskId);
        Task.Status oldStatus = task.getStatus();
        Task.Status newStatus = Task.Status.valueOf(statusStr);

        List<Task> targetColumn = taskRepository
            .findByProjectIdAndStatusOrderBySortOrder(projectId, newStatus).stream()
            .filter(t -> !t.getId().equals(taskId))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        int insertAt = Math.max(0, Math.min(targetIndex, targetColumn.size()));
        targetColumn.add(insertAt, task);

        task.setStatus(newStatus);
        for (int i = 0; i < targetColumn.size(); i++) {
            targetColumn.get(i).setSortOrder(i);
        }
        taskRepository.saveAll(targetColumn);

        if (oldStatus != newStatus) {
            List<Task> oldColumn = taskRepository
                .findByProjectIdAndStatusOrderBySortOrder(projectId, oldStatus).stream()
                .filter(t -> !t.getId().equals(taskId))
                .toList();
            for (int i = 0; i < oldColumn.size(); i++) {
                oldColumn.get(i).setSortOrder(i);
            }
            taskRepository.saveAll(oldColumn);
        }
        return task;
    }

    private int nextSortOrderForStatus(Long projectId, Task.Status status) {
        return taskRepository.findByProjectIdAndStatusOrderBySortOrder(projectId, status).size();
    }

    // 統一的 IDOR 防護：確認任務存在且屬於路徑上的專案
    private Task getTaskInProject(Long projectId, Long taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new EntityNotFoundException("任務不存在"));
        if (!task.getProject().getId().equals(projectId)) {
            throw new SecurityException("任務不屬於此專案");
        }
        return task;
    }
}
```

- [ ] **Step 7: 執行測試確認通過**

```bash
mvn test -Dtest=TaskServiceTest
```

Expected: 15 個測試全數 PASS。

- [ ] **Step 8: 實作 controller**

```java
package com.missionboard.task;

import com.missionboard.common.ApiResponse;
import com.missionboard.project.ProjectService;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final ProjectService projectService;
    private final UserRepository userRepository;

    @GetMapping("/api/projects/{projectId}/tasks")
    public ApiResponse<List<TaskDto.Response>> list(@PathVariable Long projectId, Principal principal) {
        checkRead(projectId, principal);
        return ApiResponse.ok(taskService.list(projectId).stream().map(this::toResponse).toList());
    }

    @PostMapping("/api/projects/{projectId}/tasks")
    public ApiResponse<TaskDto.Response> create(@PathVariable Long projectId,
            @RequestBody TaskDto.CreateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        return ApiResponse.ok(toResponse(taskService.createTask(projectId, req)));
    }

    @PutMapping("/api/projects/{projectId}/tasks/{taskId}")
    public ApiResponse<TaskDto.Response> update(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody TaskDto.UpdateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        return ApiResponse.ok(toResponse(taskService.updateTask(projectId, taskId, req)));
    }

    @DeleteMapping("/api/projects/{projectId}/tasks/{taskId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long taskId, Principal principal) {
        checkWrite(projectId, principal);
        taskService.deleteTask(projectId, taskId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/tasks/{taskId}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody TaskDto.StatusRequest req, Principal principal) {
        checkWrite(projectId, principal);
        taskService.updateStatus(projectId, taskId, req.status());
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/tasks/{taskId}/assignee")
    public ApiResponse<Void> updateAssignee(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody TaskDto.AssigneeRequest req, Principal principal) {
        checkWrite(projectId, principal);
        taskService.updateAssignee(projectId, taskId, req.assigneeId());
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/tasks/{taskId}/move")
    public ApiResponse<Void> move(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody TaskDto.MoveRequest req, Principal principal) {
        checkWrite(projectId, principal);
        taskService.moveTask(projectId, taskId, req.status(), req.sortOrder());
        return ApiResponse.ok(null);
    }

    private TaskDto.Response toResponse(Task task) {
        return new TaskDto.Response(
            task.getId(),
            task.getCategory() != null ? task.getCategory().getId() : null,
            task.getTitle(), task.getDescription(),
            task.getAssignee() != null ? task.getAssignee().getId() : null,
            task.getAssignee() != null ? task.getAssignee().getDisplayName() : null,
            task.getStatus().name(),
            task.getPriority() != null ? task.getPriority().name() : null,
            task.getStartDate(), task.getDueDate(), task.getSortOrder()
        );
    }

    private void checkRead(Long projectId, Principal principal) {
        if (!projectService.canRead(projectId, currentUser(principal))) {
            throw new SecurityException("無存取權限");
        }
    }

    private void checkWrite(Long projectId, Principal principal) {
        if (!projectService.canWrite(projectId, currentUser(principal))) {
            throw new SecurityException("無編輯權限");
        }
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
```

- [ ] **Step 9: 撰寫 controller 測試**

```java
package com.missionboard.task;

import com.jayway.jsonpath.JsonPath;
import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectMember;
import com.missionboard.project.ProjectMemberId;
import com.missionboard.project.ProjectMemberRepository;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;

    @BeforeEach
    void setUp() {
        Department sectionA = new Department();
        sectionA.setName("系統科A");
        departmentRepository.save(sectionA);
        Department sectionB = new Department();
        sectionB.setName("系統科B");
        departmentRepository.save(sectionB);

        User leaderA = newUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
        newUser("chiefB", User.Role.SECTION_CHIEF, sectionB);

        project = new Project();
        project.setName("專案A");
        project.setSection(sectionA);
        project.setOwner(leaderA);
        project.setCreatedBy(leaderA);
        projectRepository.save(project);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), leaderA.getId()));
        pm.setAssignedBy(leaderA);
        projectMemberRepository.save(pm);
    }

    private User newUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private Cookie loginAs(String username) throws Exception {
        return mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andReturn().getResponse().getCookie("SESSION");
    }

    @Test
    void createTaskWithoutCategory() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/tasks", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"categoryId\":null,\"title\":\"整理需求訪談紀錄\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
            .andExpect(jsonPath("$.data.categoryId").doesNotExist());
    }

    @Test
    void listDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");
        mockMvc.perform(get("/api/projects/{id}/tasks", project.getId()).cookie(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void moveUpdatesStatusAndSortOrder() throws Exception {
        Cookie session = loginAs("leaderA");
        String createRes = mockMvc.perform(post("/api/projects/{id}/tasks", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"categoryId\":null,\"title\":\"任務\"}"))
            .andReturn().getResponse().getContentAsString();
        Long taskId = ((Number) JsonPath.read(createRes, "$.data.id")).longValue();

        mockMvc.perform(patch("/api/projects/{pid}/tasks/{tid}/move", project.getId(), taskId).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"status\":\"IN_PROGRESS\",\"sortOrder\":0}"))
            .andExpect(status().isOk());

        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(Task.Status.IN_PROGRESS);
    }

    @Test
    void updateAssigneeRejectsNonMember() throws Exception {
        Cookie session = loginAs("leaderA");
        String createRes = mockMvc.perform(post("/api/projects/{id}/tasks", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"categoryId\":null,\"title\":\"任務\"}"))
            .andReturn().getResponse().getContentAsString();
        Long taskId = ((Number) JsonPath.read(createRes, "$.data.id")).longValue();
        User outsider = newUser("outsider", User.Role.PROJECT_MEMBER, project.getSection());

        mockMvc.perform(patch("/api/projects/{pid}/tasks/{tid}/assignee", project.getId(), taskId).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"assigneeId\":" + outsider.getId() + "}"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 10: 撰寫 repository 測試**

```java
package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskRepositoryTest {

    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;

    private static int counter = 0;

    @Test
    void findsAllTasksByProjectId() {
        Project p1 = newProject();
        Project p2 = newProject();
        taskRepository.save(newTask(p1, null));
        taskRepository.save(newTask(p2, null));

        assertThat(taskRepository.findByProjectId(p1.getId())).hasSize(1);
    }

    @Test
    void findsByProjectIdAndStatusOrderedBySortOrder() {
        Project p = newProject();
        Task a = newTask(p, null);
        a.setSortOrder(1);
        Task b = newTask(p, null);
        b.setSortOrder(0);
        taskRepository.save(a);
        taskRepository.save(b);

        var result = taskRepository.findByProjectIdAndStatusOrderBySortOrder(p.getId(), Task.Status.NOT_STARTED);
        assertThat(result).extracting(Task::getSortOrder).containsExactly(0, 1);
    }

    @Test
    void clearsAssigneeForUserInProjectOnly() {
        Project p1 = newProject();
        Project p2 = newProject();
        User user = newUser(p1.getSection());

        Task inP1 = newTask(p1, user);
        Task otherUserInP1 = newTask(p1, newUser(p1.getSection()));
        Task sameUserInP2 = newTask(p2, user);
        taskRepository.save(inP1);
        taskRepository.save(otherUserInP1);
        taskRepository.save(sameUserInP2);

        taskRepository.clearAssigneeForUserInProject(p1.getId(), user.getId());

        assertThat(taskRepository.findById(inP1.getId()).orElseThrow().getAssignee()).isNull();
        assertThat(taskRepository.findById(otherUserInP1.getId()).orElseThrow().getAssignee()).isNotNull();
        assertThat(taskRepository.findById(sameUserInP2.getId()).orElseThrow().getAssignee()).isNotNull();
    }

    private Project newProject() {
        Department dept = new Department();
        dept.setName("科別-" + counter++);
        departmentRepository.save(dept);
        User owner = newUser(dept);

        Project p = new Project();
        p.setName("專案-" + counter++);
        p.setSection(dept);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        return projectRepository.save(p);
    }

    private User newUser(Department dept) {
        User u = new User();
        u.setUsername("user-" + counter++);
        u.setPassword("x");
        u.setDisplayName("user");
        u.setRole(User.Role.PROJECT_MEMBER);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private Task newTask(Project project, User assignee) {
        Task t = new Task();
        t.setProject(project);
        t.setTitle("任務-" + counter++);
        t.setStatus(Task.Status.NOT_STARTED);
        t.setAssignee(assignee);
        return t;
    }
}
```

- [ ] **Step 11: 執行全部三個測試檔確認通過**

```bash
mvn test -Dtest=TaskServiceTest,TaskControllerTest,TaskRepositoryTest
```

Expected: 全數 PASS。

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/missionboard/task/Task*.java src/test/java/com/missionboard/task/Task*Test.java
git commit -m "feat: 新增 Task（取代 wbs_nodes 的 L3 語意），含看板 move 端點的欄內重編號邏輯"
```

---

## Task 6: `ProjectService.removeMember` 改接 `TaskRepository`

**Files:**
- Modify: `src/main/java/com/missionboard/project/ProjectService.java`
- Test: `src/test/java/com/missionboard/project/ProjectServiceTest.java`（新增一個斷言，其餘不動）

**Interfaces:**
- Consumes: `TaskRepository.clearAssigneeForUserInProject`（Task 5）

規格雖然說「保留完全不動」`project/`，但 `ProjectService.removeMember` 目前直接依賴 `WbsNodeRepository` 做指派清除連動（見 `mvn test` 輸出中實際觸發的 `UPDATE wbs_nodes ... SET assignee_id=null`），`wbs` package 整包移除後這行編譯不過，是必要的最小重接線，不算違反規格「不動」的精神——邏輯完全不變，只換掉底層表。

- [ ] **Step 1: 修改 import 與欄位注入**

在 `src/main/java/com/missionboard/project/ProjectService.java` 把：

```java
import com.missionboard.wbs.WbsNodeRepository;
```

改成：

```java
import com.missionboard.task.TaskRepository;
```

把欄位：

```java
private final WbsNodeRepository wbsNodeRepository;
```

改成：

```java
private final TaskRepository taskRepository;
```

- [ ] **Step 2: 修改 `removeMember` 方法內的呼叫**

把 `removeMember` 方法內：

```java
wbsNodeRepository.clearAssigneeForUserInProject(projectId, userId);
```

改成：

```java
taskRepository.clearAssigneeForUserInProject(projectId, userId);
```

- [ ] **Step 3: 執行既有 `ProjectServiceTest` 確認仍通過**

```bash
mvn test -Dtest=ProjectServiceTest
```

Expected: 全數 PASS（此測試檔案本身邏輯不變，只是底層依賴換了表，斷言應該原樣通過）。

- [ ] **Step 4: Commit**

（與 Task 7 的刪除一併 commit，因為 `wbs` package 存在的情況下這步驟編譯本身就會通過，但整個重構要到 Task 7 刪除舊 package 後才真正切斷依賴——此步驟先做但先不 commit，累積到 Task 7 一起 commit，避免出現「兩個 repository 同時存在、各自做同一件事」的中間態被單獨 commit 進歷史。）

---

## Task 7: 移除舊 `wbs` package 與對應測試

**Files:**
- Delete: `src/main/java/com/missionboard/wbs/WbsNode.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsNodeRepository.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsNodeService.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsNodeController.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsNodeDto.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsPreset.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsPresetRepository.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsPresetService.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsPresetController.java`
- Delete: `src/main/java/com/missionboard/wbs/WbsPresetDto.java`
- Delete: `src/test/java/com/missionboard/wbs/WbsNodeServiceTest.java`
- Delete: `src/test/java/com/missionboard/wbs/WbsNodeControllerTest.java`
- Delete: `src/test/java/com/missionboard/wbs/WbsNodeRepositoryTest.java`
- Delete: `src/test/java/com/missionboard/wbs/WbsPresetServiceTest.java`
- Delete: `src/test/java/com/missionboard/wbs/WbsPresetControllerTest.java`
- Delete: `src/test/java/com/missionboard/wbs/WbsPresetRepositoryTest.java`

**Interfaces:**
- 無新介面；此任務只做刪除，讓 Task 6 對 `TaskRepository` 的重接線真正切斷對 `wbs` package 的依賴。

- [ ] **Step 1: 刪除整個 `wbs` package（含測試）**

```bash
rm -rf src/main/java/com/missionboard/wbs
rm -rf src/test/java/com/missionboard/wbs
```

- [ ] **Step 2: 全量編譯＋測試確認沒有殘留依賴**

```bash
mvn test
```

Expected: BUILD SUCCESS，測試數量應為（Task 3 的 3 檔＋Task 4 的 2 檔＋Task 5 的 3 檔新增的測試方法數）加上原有非 wbs 測試（`auth`/`common`/`department`/`project`/`user` 共 12 個測試檔案不變），且不再出現任何 `com.missionboard.wbs.*` 的編譯錯誤。

- [ ] **Step 3: Commit（含 Task 6 的 `ProjectService` 重接線）**

```bash
git add -A src/main/java/com/missionboard/project/ProjectService.java src/main/java/com/missionboard/wbs src/test/java/com/missionboard/wbs
git commit -m "refactor: 移除舊 wbs package，ProjectService 改接 TaskRepository"
```

---

## Task 8: 刪除已取代的舊設計／計畫文件

**Files:**
- Delete: `docs/superpowers/specs/2026-07-24-wbsflow-node-design.md`
- Delete: `docs/superpowers/specs/2026-07-29-project-detail-shell-tree-editor-design.md`
- Delete: `docs/superpowers/plans/2026-07-24-wbsflow-node-management.md`
- Delete: `docs/superpowers/plans/2026-07-29-project-detail-shell-tree-editor.md`

規格文件（`2026-08-08-missionboard-task-oriented-rewrite-design.md` 第 98-102 行）明文列出這四份「不留歷史紀錄，直接刪除」，其餘舊 design/plan 文件維持原樣不動。

- [ ] **Step 1: 刪除四份文件**

```bash
rm docs/superpowers/specs/2026-07-24-wbsflow-node-design.md
rm docs/superpowers/specs/2026-07-29-project-detail-shell-tree-editor-design.md
rm docs/superpowers/plans/2026-07-24-wbsflow-node-management.md
rm docs/superpowers/plans/2026-07-29-project-detail-shell-tree-editor.md
```

- [ ] **Step 2: Commit**

```bash
git add -A docs/superpowers/specs docs/superpowers/plans
git commit -m "docs: 刪除已被任務導向重構取代的舊 WBS 節點/樹編輯器設計與計畫文件"
```

---

## Task 9: 看板前端（`project-detail.js` 重寫＋`app.css`）

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: `GET/POST /api/projects/{id}/tasks`、`PUT/DELETE .../tasks/{taskId}`、`PATCH .../tasks/{taskId}/assignee`、`PATCH .../tasks/{taskId}/move`（Task 5）、`GET /api/projects/{id}/task-categories`（Task 4）、既有 `GET /api/projects/{id}/members`（不變）
- Produces: 無新公開介面；`#detail-app` 掛載結構與 `data-project-id`/`data-can-write`/`data-section-id` 三個 dataset 屬性維持不變（`detail.html`／`ProjectController` 不需修改）

**設計決策（本任務範圍內拍板，非規格逐字規定）：**
1. **移除分頁列，KanbanView 變成唯一畫面**——樹編輯器/人員派工/甘特三分頁本輪整個拿掉（規格線 89），只剩看板一種檢視時保留「分頁按鈕列＋永遠只有一個作用中分頁」是死重量，直接不渲染分頁列；根 Vue app 縮成薄殼（存 `projectId`/`canWrite`/`sectionId`，掛載 `<kanban-view>`），資料狀態（`tasks`/`categories`/`members`/`loading`/`toast`/`modal`/拖曳狀態）全部收進 `KanbanView` 自己的 `data()`——此前分層是為了讓四個分頁共享 root 的資料，四個分頁只剩一個後這層間接不再必要。
2. **拖曳沿用 TaskFlow `board.js`／`detail.html` 的原生 HTML5 Drag and Drop 技巧逐段搬過來**：欄位 `@dragover.prevent` 給出「插到最後」的預設 `dragIndex`，卡片 `@dragover.prevent.stop` 用 `.stop` 蓋掉欄位層級的預設值、改成插到該卡片位置——這個 `.stop` 是讓「拖到欄位空白處＝插尾端」與「拖到特定卡片上＝插該位置」能並存的關鍵，兩段必須一起搬，缺一段拖曳定位會失效。
3. **`isOverdue` 完全逐字搬移**（規格線 86 明文「沿用 TaskFlow 的邏輯」）：本地時區手動組 `YYYY-MM-DD` 字串比對，不用 `toISOString()`（避免 UTC+8 深夜前後誤差一天）；`DONE` 狀態與無 `dueDate` 皆不顯示逾期。
4. **`.task-card.dragging` 補上 TaskFlow 原始碼沒接上的 `:class` 綁定**（研究回報：TaskFlow 的 CSS 定義了這個class但模板從未綁定，是死規則）——本專案的卡片改用 `:class="{ dragging: dragging === t }"`，讓「拖曳中的卡片變半透明」這個視覺回饋真正生效。
5. **樂觀更新的套用範圍依規格精確劃分**：只有「拖曳」明文要求樂觀更新＋失敗回滾＋toast（規格線 83）；modal 的建立/編輯/刪除延續舊 `project-detail.js` 既有慣例（`createNode`/`deleteNode` 的模式：等待 API 回應成功才關窗＋重新載入，失敗則 toast 且視窗不關），不對 modal 操作做本地樂觀鏡射——modal 一次改多個欄位、樂觀回滾的狀態管理複雜度不成比例，規格也沒要求。
6. **`saveTask` 統一走「PUT 本體欄位 + PATCH 指派人」兩個並行請求，建立與編輯共用同一段程式碼**：因為 `TaskDto.CreateRequest`（Task 5）只收 `categoryId`/`title`/`description`，modal 上的指派人/優先度/日期在建立時序上必須等有了 `taskId` 才能送出，於是建立流程＝「先 POST 拿 taskId，再跟編輯流程共用同一段 PUT+PATCH」，避免重複兩套欄位組裝邏輯。
7. **`sectionId` 這個 prop 目前不再被任何邏輯使用**：舊 `WbsNodeRow.openAddForm` 用它去查詢科別限定的選單（L1/L2 建立時）；看板的類別下拉是直接列出專案既有的 `task_categories`（Task 4 的 `GET .../task-categories`），不需要另外查科別選單。保留 `#detail-app` 的 `data-section-id` 屬性本身（後端未改），但 JS 不再讀取——避免穿一條沒人用的資料線。
8. **`STATUS_LABEL`/`STATUS_CYCLE`/`PRIORITY_LABEL` 三個舊常數整批移除**：狀態文字改直接放進 `columns` 陣列的 `label` 欄位（欄位標題本身就是狀態顯示文字，不需要另一份對照表）；狀態變更改由拖曳／`/move` 驅動，不再有「點狀態徽章循環切換」的互動，`STATUS_CYCLE` 无用武之地；`PRIORITY_LABEL` 舊碼裡本來就沒被用到（前端研究已標記為 dead constant），看板卡片用左側色條表示優先度（沿用 TaskFlow 的 `border-left-color` 做法），modal 下拉選項直接寫死中文（沿用舊 `WbsNodeRow` modal 本來就寫死選項文字的既有寫法），三者都不需要這份對照表。

- [ ] **Step 1: 重寫 `project-detail.js`**

整檔取代為：

```javascript
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
```

- [ ] **Step 2: 重寫 `app.css` 看板相關區塊**

把 `src/main/resources/static/css/app.css` 檔尾（`.tabs`/`.toast` 之後）的 `.placeholder-tab` 與全部 `.wbs-*` 規則（第 59-73 行）整段替換成：

```css
.kanban-toolbar { margin-bottom: 1rem; }
.kanban { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; align-items: start; }
.kanban-col { background: #ececf1; border-radius: 8px; padding: 0.75rem; min-height: 320px; }
.kanban-col-header { display: flex; justify-content: space-between; align-items: center; font-weight: 700; font-size: 0.9rem; color: #636e72; padding: 0.25rem 0.5rem 0.75rem; }
.kanban-col-count { background: #b2bec3; color: #fff; border-radius: 10px; padding: 0 0.55rem; font-size: 0.75rem; }
.kanban-col.drag-over { outline: 2px dashed #0984e3; outline-offset: -4px; }
.task-card { background: #fff; border-radius: 6px; padding: 0.75rem 0.9rem; margin-bottom: 0.6rem; box-shadow: 0 1px 3px rgba(0,0,0,0.08); cursor: grab; border-left: 4px solid #b2bec3; }
.task-card:active { cursor: grabbing; }
.task-card.priority-HIGH { border-left-color: #d63031; }
.task-card.priority-MEDIUM { border-left-color: #e17055; }
.task-card.priority-LOW { border-left-color: #00b894; }
.task-card.dragging { opacity: 0.4; }
.task-card-title { font-size: 0.95rem; font-weight: 600; margin-bottom: 0.4rem; }
.task-card-meta { display: flex; justify-content: space-between; align-items: center; font-size: 0.8rem; color: #636e72; }
.task-due.overdue { color: #d63031; font-weight: 700; }
```

`.tabs`/`.tabs .btn`（第 56-57 行）目前分頁列已不渲染，但這兩條規則泛用（未來子專案復原分頁時會再用到），予以保留不刪；`.toast`/`.modal*`/`.form-group`/`.btn*` 全部沿用不動。

- [ ] **Step 3: 全量測試確認未破壞既有頁面測試**

```bash
mvn test
```

Expected: BUILD SUCCESS（`ProjectDetailPageTest`/`ProjectPageTest` 只斷言 view name 與狀態碼，不斷言 Vue 渲染內容，理論上不受影響）。

- [ ] **Step 4: chrome-devtools 手動實測看板互動**

前端無 build 工具、無 JS 測試框架（已確認 repo 內無 `package.json`/`*.config.js`），此任務的驗證走瀏覽器手動操作，不新增 JS 測試檔：

```bash
docker compose up -d
mvn spring-boot:run
```

以 `leader`/`password123` 登入，開啟「MissionBoard 範例專案」詳情頁，用 chrome-devtools 依序確認：
1. 預設直接顯示看板（無分頁列），三欄依序「未開始／進行中／已完成」，種子資料 3 筆任務各自落在正確欄位（`整理需求訪談紀錄`＝未開始且未歸類、`實作看板拖曳`＝進行中、`設計登入頁`＝已完成）
2. 用 `mcp__plugin_chrome-devtools-mcp_chrome-devtools__drag` 把「整理需求訪談紀錄」從未開始拖到進行中，畫面立即反映（樂觀更新），重新整理頁面後狀態仍保留（確認後端真的收到 `/move`）
3. 點卡片開 modal，改標題／指派人／類別後按「儲存」，畫面即時反映新值
4. 「新增任務」建立一筆未歸類任務，確認落在未開始欄最底部
5. console 面板確認無錯誤；截圖存證（依專案 CLAUDE.md「有畫面就有截圖」規則）

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 看板前端改為真正拖曳看板（取代佔位符），移除樹編輯器/人員派工/甘特三分頁"
```

---

## Task 10: 品牌文字改名（7 處 "WBS 管理系統" → 新名稱）

**Files:**
- Modify: `src/main/resources/templates/fragments/header.html:10`
- Modify: `src/main/resources/templates/fragments/footer.html:5`
- Modify: `src/main/resources/templates/project/detail.html:5`
- Modify: `src/main/resources/templates/home.html:5`
- Modify: `src/main/resources/templates/project/list.html:5`
- Modify: `src/main/resources/templates/auth/login.html:5`
- Modify: `src/main/resources/templates/auth/login.html:10`

規格「改名範圍與方式」表格只明文列出目錄／repo／package／Maven 座標，UI 顯示字串不在表列範圍——但規格背景段落明確定位為「任務導向」「以任務看板為核心體驗」，且改名 commit（`df9d49a`）訊息本身寫明「（程式碼層）」，代表 UI 文字本就是刻意留到這次一併處理。統一改為「MissionBoard 任務管理系統」。

- [ ] **Step 1: 逐檔替換**

`fragments/header.html:10`：
```html
<a th:href="@{/home}">MissionBoard 任務管理系統</a>
```

`fragments/footer.html:5`：
```html
<p>MissionBoard 任務管理系統 &copy; 2026</p>
```

`project/detail.html:5`：
```html
<title>專案詳情 - MissionBoard 任務管理系統</title>
```

`home.html:5`：
```html
<title>首頁 - MissionBoard 任務管理系統</title>
```

`project/list.html:5`：
```html
<title>專案列表 - MissionBoard 任務管理系統</title>
```

`auth/login.html:5`：
```html
<title>登入 - MissionBoard 任務管理系統</title>
```

`auth/login.html:10`：
```html
<h1>MissionBoard 任務管理系統</h1>
```

- [ ] **Step 2: 確認無殘留舊字串**

```bash
grep -rn "WBS 管理系統" src/main/resources/templates/
```

Expected: 無輸出。

- [ ] **Step 3: 執行既有頁面測試確認未破壞**

```bash
mvn test -Dtest=ProjectDetailPageTest,ProjectPageTest,AuthFlowTest
```

Expected: 全數 PASS（這些測試只斷言 view name 與狀態碼，不斷言頁面文字，理論上不受影響——執行是為了保險確認）。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates
git commit -m "docs: UI 品牌文字改為「MissionBoard 任務管理系統」，呼應任務導向重新定位"
```

---

## Task 11: 更新 `CLAUDE.md` 反映新資料模型

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- 無程式介面；文件任務。

- [ ] **Step 1: 改寫「專案狀態」段落**

移除「待決的重大轉向（尚未動手）」框架敘述（該轉向本計畫執行完後已不再「待決」），改為敘述目前已是任務導向模型：`tasks`/`task_categories`/`task_category_presets` 取代 `wbs_nodes`/`wbs_presets`；看板為預設分頁且可用；樹編輯器/人員派工/甘特已移除，留待後續子專案依新模型重做。真相來源改指向 `docs/superpowers/specs/2026-08-08-missionboard-task-oriented-rewrite-design.md`。

- [ ] **Step 2: 改寫「核心架構決策」段落**

把「單一資料模型：WBS 節點即任務」與「三層固定語意」兩節，改為描述新模型：
- 任務天生扁平獨立（`category_id` 可為 NULL）
- `task_categories` 選配最多兩層，深度上限由 **service 層驗證**（非 DB CHECK）
- `task_category_presets` 選單快照規則不變（`section_id` NULL＝全域）
- 保留「權限與安全」「API 慣例」兩節不動（`ProjectService.canRead/canWrite`、IDOR 防護、指派人須為成員、封存語意皆沿用）。

- [ ] **Step 3: 改寫「前端模式」段落**

改為描述看板已是預設且可運作的檢視，REST＋樂觀更新模式落實於 `KanbanView`；移除「僅樹編輯器落實」的舊敘述。

- [ ] **Step 4: 改寫「測試重點」段落**

改為：
```markdown
## 測試重點

權限矩陣（4 角色 × 讀／寫／封存，套用到 `tasks`／`task_categories` 的 CRUD）、任務可獨立存在（`category_id = NULL` 建立/查詢/指派/看板拖曳皆正常）、兩層深度上限（`task_categories` 第三層應被拒絕）、指派人須為專案成員／移除成員解除指派、看板 `move` 端點的欄內與跨欄重新編號、`task_category_presets` 科別隔離、IDOR（`task`／`task_category` 的 `project_id` 與 URL 路徑不一致應拒絕）。
```

- [ ] **Step 5: 改寫「驗證與完成定義」段落**

把「登入後四檢視（樹編輯器／看板／人員派工／甘特）可操作」改為「登入後看板可操作（拖曳、建立任務、歸類、指派）」，反映三個分頁已移除的現況。

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 反映任務導向重構後的現況（tasks/task_categories 取代 wbs_nodes）"
```

---

## 收尾：全量驗證

- [ ] **Step 1: 全新資料庫＋全量測試＋實際啟動**

```bash
docker compose down -v && docker compose up -d
mvn test
mvn spring-boot:run
```

Expected: 容器 healthy、`mvn test` 全綠、應用程式啟動成功。

- [ ] **Step 2: chrome-devtools 實測看板**

以 `leader`/`password123` 登入、開啟「MissionBoard 範例專案」詳情頁，確認：預設開啟看板分頁、三個狀態欄顯示種子任務、拖曳卡片跨欄後樂觀更新＋重新整理仍保留新狀態、點卡片開 modal 可編輯並存檔、新增任務預設「未歸類」、console 無錯誤。依專案 CLAUDE.md「有畫面就有截圖」規則截圖存證。

- [ ] **Step 3: 提醒使用者 `/sync-docs`**

若上述皆通過，提醒使用者可執行 `/sync-docs` 同步 `docs/dev.md`／`README.md`（若存在）與本次變更。
