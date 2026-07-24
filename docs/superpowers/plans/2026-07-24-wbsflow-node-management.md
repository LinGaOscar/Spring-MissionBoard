# 子專案 D：WBS 節點與選單管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 實作 `wbs_nodes` 的 CRUD、三層固定語意的應用層強制、reorder（含跨層級搬移與子樹位移）、父層狀態/日期即時彙總、建專案 L1 骨架初始化，以及 `wbs_presets` 選單管理。

**Architecture:** 新增 `com.wbsflow.wbs` package 下的 `WbsNodeService`（節點核心邏輯）、`WbsNodeDto`、`WbsNodeController`（REST，`/api/projects/{id}/nodes/**`）；`WbsPresetService`、`WbsPresetDto`、`WbsPresetController`（REST，`/api/presets/**`）。擴充既有 `WbsNodeRepository`/`WbsPresetRepository`（子專案 A 已建 entity，本次補查詢方法）。純後端 REST，不加 Thymeleaf 頁殼、不做匯出。

**Tech Stack:** Spring Data JPA（含樹狀結構的記憶體內遞迴運算，不在 DB 層做遞迴 CTE）、MockMvc + `spring-security-test`。

## Global Constraints

- **CSRF 是預設開啟的**：所有 Controller 測試中對 POST/PUT/PATCH/DELETE 的 MockMvc 呼叫都要加 `.with(csrf())`（`import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;`）
- **權限檢查一律放在 Controller**：呼叫 `ProjectService.canRead`/`canWrite`（子專案 A/C 已建好，不需修改），本子專案的 service 方法彼此沒有「內部互相呼叫導致卡權限檢查」的情況（不像子專案 C 的 `addMember`），所以不需要子專案 C 那種「archive 例外放 service 內部」的分工，全部端點一律 controller 層擋
- **IDOR 防護**：所有帶 `nodeId`/`parentId` 的操作，一律先驗證該節點（與其 parent，若有）的 `project.id` 等於路徑上的 `projectId`
- **三層固定語意由 DB CHECK 約束保底**（`sql/01_ddl.sql` 已有：`chk_level_parent`、`chk_assignee_l3_only`、`chk_status_l3_only`、`chk_priority_l3_only`、`chk_dates_l3_only`），應用層要在送資料庫前就先擋掉違反這些約束的請求，回傳有意義的 400 訊息（不要讓使用者看到 DB 約束的原始錯誤）
- `GlobalExceptionHandler` 已存在且**不需修改**：`EntityNotFoundException`→404、`IllegalArgumentException`→400、`SecurityException`→403。`WbsNode.Status.valueOf(...)`／`WbsNode.Priority.valueOf(...)` 對非法字串會自動拋 `IllegalArgumentException`，直接讓它往外拋、不需要額外 catch
- 所有 Controller 測試使用 `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional`，透過 `formLogin(...)` 取得 `SESSION` cookie
- 所有 Service 測試使用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)` + `@ActiveProfiles("test")` + `@Transactional`（比照 `ProjectServiceTest` 既有風格）
- `mvn test` 必須全數通過才能進入下一個 Task 的 commit

---

### Task 1: Repository 查詢方法擴充（WbsNode / WbsPreset）

**Files:**
- Modify: `src/main/java/com/wbsflow/wbs/WbsNodeRepository.java`
- Modify: `src/main/java/com/wbsflow/wbs/WbsPresetRepository.java`
- Test: `src/test/java/com/wbsflow/wbs/WbsNodeRepositoryTest.java`
- Test: `src/test/java/com/wbsflow/wbs/WbsPresetRepositoryTest.java`

**Interfaces:**
- Consumes: 既有 `WbsNode`、`WbsPreset` entity（子專案 A）
- Produces:
  - `WbsNodeRepository.findByProjectId(Long): List<WbsNode>`
  - `WbsNodeRepository.existsByProjectId(Long): boolean`
  - `WbsPresetRepository.findVisiblePresets(WbsPreset.Type, Long sectionId): List<WbsPreset>`（全域 `section IS NULL` ＋指定科別合併，僅 `enabled=true`，依 `sortOrder` 排序）

- [ ] **Step 1: 寫失敗測試**

在 `WbsNodeRepositoryTest.java` 現有類別內、`clearsAssigneeForUserInProjectOnly` 方法之後加入：

```java
    @Test
    void findsAllNodesByProjectId() {
        Project project = newProject();
        Project otherProject = newProject();

        WbsNode l1 = new WbsNode();
        l1.setProject(project);
        l1.setLevel((short) 1);
        l1.setTitle("SIT");
        wbsNodeRepository.save(l1);

        WbsNode otherL1 = new WbsNode();
        otherL1.setProject(otherProject);
        otherL1.setLevel((short) 1);
        otherL1.setTitle("UAT");
        wbsNodeRepository.save(otherL1);

        List<WbsNode> found = wbsNodeRepository.findByProjectId(project.getId());

        assertThat(found).extracting(WbsNode::getTitle).containsExactly("SIT");
    }

    @Test
    void existsByProjectIdReflectsCurrentNodeCount() {
        Project project = newProject();

        assertThat(wbsNodeRepository.existsByProjectId(project.getId())).isFalse();

        WbsNode l1 = new WbsNode();
        l1.setProject(project);
        l1.setLevel((short) 1);
        l1.setTitle("SIT");
        wbsNodeRepository.save(l1);

        assertThat(wbsNodeRepository.existsByProjectId(project.getId())).isTrue();
    }
```

在 `WbsPresetRepositoryTest.java` 現有類別內、`savesGlobalPresetWithNullSection` 方法之後加入（需在 import 區塊加入 `import com.wbsflow.department.Department;` 與 `import com.wbsflow.department.DepartmentRepository;` 與 `import java.util.List;`，並在 class 頂端新增 `@Autowired private DepartmentRepository departmentRepository;` 欄位）：

```java
    @Test
    void findsVisiblePresetsMergesGlobalAndSectionSpecific() {
        Department sectionA = new Department();
        sectionA.setName("系統科");
        sectionA = departmentRepository.save(sectionA);

        Department sectionB = new Department();
        sectionB.setName("網路科");
        sectionB = departmentRepository.save(sectionB);

        WbsPreset globalStage = new WbsPreset();
        globalStage.setType(WbsPreset.Type.STAGE);
        globalStage.setName("SIT");
        globalStage.setSortOrder(1);
        wbsPresetRepository.save(globalStage);

        WbsPreset sectionAStage = new WbsPreset();
        sectionAStage.setType(WbsPreset.Type.STAGE);
        sectionAStage.setName("系統科專用階段");
        sectionAStage.setSortOrder(2);
        sectionAStage.setSection(sectionA);
        wbsPresetRepository.save(sectionAStage);

        WbsPreset sectionBStage = new WbsPreset();
        sectionBStage.setType(WbsPreset.Type.STAGE);
        sectionBStage.setName("網路科專用階段");
        sectionBStage.setSortOrder(3);
        sectionBStage.setSection(sectionB);
        wbsPresetRepository.save(sectionBStage);

        WbsPreset disabledGlobal = new WbsPreset();
        disabledGlobal.setType(WbsPreset.Type.STAGE);
        disabledGlobal.setName("已停用");
        disabledGlobal.setSortOrder(0);
        disabledGlobal.setEnabled(false);
        wbsPresetRepository.save(disabledGlobal);

        List<WbsPreset> visible = wbsPresetRepository.findVisiblePresets(WbsPreset.Type.STAGE, sectionA.getId());

        assertThat(visible).extracting(WbsPreset::getName)
            .containsExactly("SIT", "系統科專用階段");
    }
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsNodeRepositoryTest,WbsPresetRepositoryTest`
Expected: FAIL（編譯錯誤，新方法尚不存在）

- [ ] **Step 3: 寫最小實作**

完整替換 `src/main/java/com/wbsflow/wbs/WbsNodeRepository.java`：

```java
package com.wbsflow.wbs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WbsNodeRepository extends JpaRepository<WbsNode, Long> {
    List<WbsNode> findByParentId(Long parentId);

    List<WbsNode> findByProjectId(Long projectId);

    boolean existsByProjectId(Long projectId);

    // 移除專案成員時連動清除其指派；clearAutomatically 避免呼叫端讀到 stale 的一級快取
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WbsNode n SET n.assignee = null WHERE n.project.id = :projectId AND n.assignee.id = :userId")
    void clearAssigneeForUserInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
```

完整替換 `src/main/java/com/wbsflow/wbs/WbsPresetRepository.java`：

```java
package com.wbsflow.wbs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WbsPresetRepository extends JpaRepository<WbsPreset, Long> {

    // 全域（section IS NULL）＋指定科別自訂合併，僅回傳啟用中的項目，依 sortOrder 排序
    @Query("SELECT p FROM WbsPreset p WHERE p.type = :type AND p.enabled = true "
        + "AND (p.section IS NULL OR p.section.id = :sectionId) ORDER BY p.sortOrder")
    List<WbsPreset> findVisiblePresets(@Param("type") WbsPreset.Type type, @Param("sectionId") Long sectionId);
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsNodeRepositoryTest,WbsPresetRepositoryTest`
Expected: PASS（全部測試，含新增的 3 個）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsNodeRepository.java \
        src/main/java/com/wbsflow/wbs/WbsPresetRepository.java \
        src/test/java/com/wbsflow/wbs/WbsNodeRepositoryTest.java \
        src/test/java/com/wbsflow/wbs/WbsPresetRepositoryTest.java
git commit -m "feat: 新增節點與選單的查詢方法"
```

---

### Task 2: WbsNodeService — 節點建立/更新/刪除

**Files:**
- Create: `src/main/java/com/wbsflow/wbs/WbsNodeService.java`
- Create: `src/main/java/com/wbsflow/wbs/WbsNodeDto.java`（本任務只加 `CreateRequest`/`UpdateRequest`，後續任務擴充）
- Test: `src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WbsNodeRepository.findByProjectId/existsByProjectId`、既有 `WbsPresetRepository`、`ProjectService.getById`（子專案 A/C）
- Produces:
  - `WbsNodeService.createNode(Long projectId, WbsNodeDto.CreateRequest req): WbsNode`
  - `WbsNodeService.updateNode(Long projectId, Long nodeId, WbsNodeDto.UpdateRequest req): WbsNode`
  - `WbsNodeService.deleteNode(Long projectId, Long nodeId): void`
  - `WbsNodeDto.CreateRequest(Long parentId, Long presetId, String title, Integer sortOrder)`
  - `WbsNodeDto.UpdateRequest(String title, String notes, String priority, LocalDate startDate, LocalDate endDate)`

- [ ] **Step 1: 寫失敗測試**

建立 `src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java`：

```java
package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.project.Project;
import com.wbsflow.project.ProjectRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
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
class WbsNodeServiceTest {

    @Autowired
    private WbsNodeService wbsNodeService;

    @Autowired
    private WbsNodeRepository wbsNodeRepository;

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    private Department sectionA;
    private Department sectionB;
    private Project project;
    private WbsPreset stagePreset;
    private WbsPreset categoryPreset;
    private WbsPreset otherSectionStagePreset;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        User owner = userRepository.save(newUser("leader", sectionA));

        Project p = new Project();
        p.setName("測試專案");
        p.setSection(sectionA);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        project = projectRepository.save(p);

        stagePreset = newPreset(WbsPreset.Type.STAGE, "SIT", null);
        categoryPreset = newPreset(WbsPreset.Type.CATEGORY, "程式開發", null);
        otherSectionStagePreset = newPreset(WbsPreset.Type.STAGE, "網路科限定", sectionB);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private User newUser(String username, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("hash");
        u.setDisplayName(username);
        u.setRole(User.Role.PROJECT_LEADER);
        u.setDepartment(dept);
        return u;
    }

    private WbsPreset newPreset(WbsPreset.Type type, String name, Department section) {
        WbsPreset preset = new WbsPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(1);
        preset.setSection(section);
        return wbsPresetRepository.save(preset);
    }

    private WbsNode newL1(String title) {
        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setLevel((short) 1);
        node.setTitle(title);
        return wbsNodeRepository.save(node);
    }

    private WbsNode newL2(WbsNode parent, String title) {
        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setParent(parent);
        node.setLevel((short) 2);
        node.setTitle(title);
        return wbsNodeRepository.save(node);
    }

    private WbsNode newL3(WbsNode parent, String title) {
        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setParent(parent);
        node.setLevel((short) 3);
        node.setTitle(title);
        return wbsNodeRepository.save(node);
    }

    @Test
    void createsL1NodeFromStagePresetSnapshot() {
        WbsNode created = wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(null, stagePreset.getId(), null, null));

        assertThat(created.getLevel()).isEqualTo((short) 1);
        assertThat(created.getTitle()).isEqualTo("SIT");
        assertThat(created.getParent()).isNull();
    }

    @Test
    void createsL2NodeFromCategoryPresetUnderL1Parent() {
        WbsNode l1 = newL1("SIT");

        WbsNode created = wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l1.getId(), categoryPreset.getId(), null, null));

        assertThat(created.getLevel()).isEqualTo((short) 2);
        assertThat(created.getTitle()).isEqualTo("程式開發");
        assertThat(created.getParent().getId()).isEqualTo(l1.getId());
    }

    @Test
    void createsL3NodeWithFreeTextTitleUnderL2Parent() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");

        WbsNode created = wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l2.getId(), null, "登入功能開發", null));

        assertThat(created.getLevel()).isEqualTo((short) 3);
        assertThat(created.getTitle()).isEqualTo("登入功能開發");
    }

    @Test
    void rejectsCreatingChildUnderL3Node() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l3.getId(), null, "超過三層", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsL3CreationWithoutTitle() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");

        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l2.getId(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsL1CreationWithoutPresetId() {
        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(null, null, "自己亂打", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPresetFromOtherSectionNotVisibleToThisProject() {
        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(null, otherSectionStagePreset.getId(), null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsParentNodeFromAnotherProject() {
        Project otherProject = new Project();
        otherProject.setName("別的專案");
        otherProject.setSection(sectionA);
        otherProject.setOwner(project.getOwner());
        otherProject.setCreatedBy(project.getOwner());
        otherProject = projectRepository.save(otherProject);

        WbsNode foreignL1 = new WbsNode();
        foreignL1.setProject(otherProject);
        foreignL1.setLevel((short) 1);
        foreignL1.setTitle("別專案的階段");
        foreignL1 = wbsNodeRepository.save(foreignL1);

        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(foreignL1.getId(), categoryPreset.getId(), null, null)))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void updatesTitleAndNotesOnAnyLevel() {
        WbsNode l1 = newL1("SIT");

        WbsNode updated = wbsNodeService.updateNode(project.getId(), l1.getId(),
            new WbsNodeDto.UpdateRequest("改過的標題", "備註", null, null, null));

        assertThat(updated.getTitle()).isEqualTo("改過的標題");
        assertThat(updated.getNotes()).isEqualTo("備註");
    }

    @Test
    void updatesPriorityAndDatesOnL3Node() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        WbsNode updated = wbsNodeService.updateNode(project.getId(), l3.getId(),
            new WbsNodeDto.UpdateRequest(null, null, "HIGH",
                java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 15)));

        assertThat(updated.getPriority()).isEqualTo(WbsNode.Priority.HIGH);
        assertThat(updated.getStartDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
    }

    @Test
    void rejectsPriorityUpdateOnNonL3Node() {
        WbsNode l1 = newL1("SIT");

        assertThatThrownBy(() -> wbsNodeService.updateNode(project.getId(), l1.getId(),
            new WbsNodeDto.UpdateRequest(null, null, "HIGH", null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteNodeCascadesToChildren() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        newL3(l2, "登入功能開發");

        wbsNodeService.deleteNode(project.getId(), l1.getId());

        assertThat(wbsNodeRepository.findById(l1.getId())).isEmpty();
        assertThat(wbsNodeRepository.findById(l2.getId())).isEmpty();
    }

    @Test
    void deleteThrowsNotFoundForNonExistentNode() {
        assertThatThrownBy(() -> wbsNodeService.deleteNode(project.getId(), 999999L))
            .isInstanceOf(EntityNotFoundException.class);
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsNodeServiceTest`
Expected: FAIL（編譯錯誤，`WbsNodeService`/`WbsNodeDto` 不存在）

- [ ] **Step 3: 寫最小實作**

建立 `src/main/java/com/wbsflow/wbs/WbsNodeDto.java`：

```java
package com.wbsflow.wbs;

import java.time.LocalDate;

public class WbsNodeDto {

    public record CreateRequest(Long parentId, Long presetId, String title, Integer sortOrder) {
    }

    public record UpdateRequest(String title, String notes, String priority,
                                 LocalDate startDate, LocalDate endDate) {
    }
}
```

建立 `src/main/java/com/wbsflow/wbs/WbsNodeService.java`：

```java
package com.wbsflow.wbs;

import com.wbsflow.project.Project;
import com.wbsflow.project.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WbsNodeService {

    private final WbsNodeRepository wbsNodeRepository;
    private final WbsPresetRepository wbsPresetRepository;
    private final ProjectService projectService;

    // 建立節點：L1/L2 需選單項目（存文字快照），L3 為自由文字；層級由父節點推算，不接受前端指定
    @Transactional
    public WbsNode createNode(Long projectId, WbsNodeDto.CreateRequest req) {
        Project project = projectService.getById(projectId);
        WbsNode parent = null;
        short level = 1;
        if (req.parentId() != null) {
            parent = getNodeInProject(projectId, req.parentId());
            if (parent.getLevel() == 3) {
                throw new IllegalArgumentException("已達第三層，無法在細項下新增子節點");
            }
            level = (short) (parent.getLevel() + 1);
        }

        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setParent(parent);
        node.setLevel(level);

        if (level == 3) {
            if (req.title() == null || req.title().isBlank()) {
                throw new IllegalArgumentException("細項需要標題");
            }
            node.setTitle(req.title());
        } else {
            if (req.presetId() == null) {
                throw new IllegalArgumentException("階段/類別需要選擇選單項目");
            }
            WbsPreset preset = wbsPresetRepository.findById(req.presetId())
                .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
            WbsPreset.Type expectedType = level == 1 ? WbsPreset.Type.STAGE : WbsPreset.Type.CATEGORY;
            if (preset.getType() != expectedType) {
                throw new IllegalArgumentException("選單項目型別不符");
            }
            if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
                throw new IllegalArgumentException("選單項目不屬於此專案科別");
            }
            node.setTitle(preset.getName());
        }

        node.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        return wbsNodeRepository.save(node);
    }

    // title/notes 任何層級可改；priority/dates 僅 L3，非 L3 傳值直接拒絕（避免觸發 DB CHECK 約束產生不友善錯誤）
    @Transactional
    public WbsNode updateNode(Long projectId, Long nodeId, WbsNodeDto.UpdateRequest req) {
        WbsNode node = getNodeInProject(projectId, nodeId);

        if (req.title() != null) node.setTitle(req.title());
        if (req.notes() != null) node.setNotes(req.notes());

        boolean hasL3OnlyFields = req.priority() != null || req.startDate() != null || req.endDate() != null;
        if (hasL3OnlyFields && node.getLevel() != 3) {
            throw new IllegalArgumentException("僅細項（L3）可設定優先度或起迄日");
        }
        if (req.priority() != null) node.setPriority(WbsNode.Priority.valueOf(req.priority()));
        if (req.startDate() != null) node.setStartDate(req.startDate());
        if (req.endDate() != null) node.setEndDate(req.endDate());

        return wbsNodeRepository.save(node);
    }

    // DB parent_id 有 ON DELETE CASCADE，刪除根節點即完整清除整棵子樹
    @Transactional
    public void deleteNode(Long projectId, Long nodeId) {
        WbsNode node = getNodeInProject(projectId, nodeId);
        wbsNodeRepository.delete(node);
    }

    // 統一的 IDOR 防護：確認節點存在且屬於路徑上的專案
    private WbsNode getNodeInProject(Long projectId, Long nodeId) {
        WbsNode node = wbsNodeRepository.findById(nodeId)
            .orElseThrow(() -> new EntityNotFoundException("節點不存在"));
        if (!node.getProject().getId().equals(projectId)) {
            throw new SecurityException("節點不屬於此專案");
        }
        return node;
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsNodeServiceTest`
Expected: PASS（12 個測試）；同時跑 `mvn test` 全套確認未影響先前子專案

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsNodeDto.java \
        src/main/java/com/wbsflow/wbs/WbsNodeService.java \
        src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java
git commit -m "feat: WbsNodeService 新增節點建立/更新/刪除"
```

---

### Task 3: WbsNodeService — GET tree 父層彙總

**Files:**
- Modify: `src/main/java/com/wbsflow/wbs/WbsNodeDto.java`
- Modify: `src/main/java/com/wbsflow/wbs/WbsNodeService.java`
- Modify: `src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WbsNodeRepository.findByProjectId`
- Produces:
  - `WbsNodeDto.Response(Long id, Long parentId, short level, String title, Long assigneeId, String assigneeDisplayName, String status, String priority, LocalDate startDate, LocalDate endDate, String notes, int sortOrder)`
  - `WbsNodeService.getTree(Long projectId): List<WbsNodeDto.Response>`

- [ ] **Step 1: 寫失敗測試**

在 `WbsNodeServiceTest.java` 現有類別內、`deleteThrowsNotFoundForNonExistentNode` 方法之後加入：

```java
    @Test
    void getTreeAggregatesEmptyNodeAsNotStarted() {
        newL1("SIT");

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).status()).isEqualTo("NOT_STARTED");
        assertThat(tree.get(0).startDate()).isNull();
    }

    @Test
    void getTreeAggregatesAllDoneAsL2Done() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3a = newL3(l2, "功能A");
        l3a.setStatus(WbsNode.Status.DONE);
        l3a.setStartDate(java.time.LocalDate.of(2026, 8, 1));
        l3a.setEndDate(java.time.LocalDate.of(2026, 8, 5));
        wbsNodeRepository.save(l3a);
        WbsNode l3b = newL3(l2, "功能B");
        l3b.setStatus(WbsNode.Status.DONE);
        l3b.setStartDate(java.time.LocalDate.of(2026, 8, 3));
        l3b.setEndDate(java.time.LocalDate.of(2026, 8, 10));
        wbsNodeRepository.save(l3b);

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        WbsNodeDto.Response l2Response = tree.stream().filter(r -> r.id().equals(l2.getId())).findFirst().orElseThrow();
        assertThat(l2Response.status()).isEqualTo("DONE");
        assertThat(l2Response.startDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
        assertThat(l2Response.endDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 10));
    }

    @Test
    void getTreeAggregatesMixedStatusAsInProgress() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3a = newL3(l2, "功能A");
        l3a.setStatus(WbsNode.Status.DONE);
        wbsNodeRepository.save(l3a);
        WbsNode l3b = newL3(l2, "功能B");
        l3b.setStatus(WbsNode.Status.NOT_STARTED);
        wbsNodeRepository.save(l3b);

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        WbsNodeDto.Response l2Response = tree.stream().filter(r -> r.id().equals(l2.getId())).findFirst().orElseThrow();
        assertThat(l2Response.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void getTreeAggregatesL1FromL2AggregatedResultsNotDirectlyFromL3() {
        WbsNode l1 = newL1("SIT");
        WbsNode doneCategory = newL2(l1, "已完成類別");
        WbsNode l3Done = newL3(doneCategory, "功能A");
        l3Done.setStatus(WbsNode.Status.DONE);
        wbsNodeRepository.save(l3Done);
        // 第二個 L2 是空節點（無子節點），依規則視為 NOT_STARTED，
        // 使 L1 的彙總來源是「DONE、NOT_STARTED」混合 → IN_PROGRESS
        newL2(l1, "空類別");

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        WbsNodeDto.Response l1Response = tree.stream().filter(r -> r.id().equals(l1.getId())).findFirst().orElseThrow();
        assertThat(l1Response.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void getTreeReturnsStoredValuesDirectlyForL3() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        l3.setStatus(WbsNode.Status.IN_PROGRESS);
        l3.setPriority(WbsNode.Priority.HIGH);
        wbsNodeRepository.save(l3);

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        WbsNodeDto.Response l3Response = tree.stream().filter(r -> r.id().equals(l3.getId())).findFirst().orElseThrow();
        assertThat(l3Response.status()).isEqualTo("IN_PROGRESS");
        assertThat(l3Response.priority()).isEqualTo("HIGH");
        assertThat(l3Response.assigneeId()).isNull();
    }
```

在檔案 import 區塊加入：
```java
import java.util.List;
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsNodeServiceTest`
Expected: FAIL（編譯錯誤，`WbsNodeDto.Response`/`getTree` 不存在）

- [ ] **Step 3: 寫最小實作**

在 `WbsNodeDto.java` 的 `UpdateRequest` record 之後加入：

```java
    public record Response(Long id, Long parentId, short level, String title,
                            Long assigneeId, String assigneeDisplayName,
                            String status, String priority,
                            LocalDate startDate, LocalDate endDate,
                            String notes, int sortOrder) {
    }
```

在 `WbsNodeService.java` 中，import 區塊加入：
```java
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
```

在 `deleteNode` 方法之後、`getNodeInProject` 私有方法之前，加入：

```java
    // 依角色範圍已在 controller 層檢查過 canRead，這裡只負責彙總計算
    @Transactional(readOnly = true)
    public List<WbsNodeDto.Response> getTree(Long projectId) {
        List<WbsNode> allNodes = wbsNodeRepository.findByProjectId(projectId);
        Map<Long, List<WbsNode>> childrenByParent = allNodes.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));

        Map<Long, Aggregate> aggregateByNodeId = new HashMap<>();
        // 由下往上：先算所有 L2（依其 L3 子節點的儲存值），再算所有 L1（依其 L2 子節點「已彙總」的結果）
        for (WbsNode node : allNodes) {
            if (node.getLevel() == 2) {
                aggregateByNodeId.put(node.getId(), aggregateFromL3Children(node, childrenByParent));
            }
        }
        for (WbsNode node : allNodes) {
            if (node.getLevel() == 1) {
                aggregateByNodeId.put(node.getId(), aggregateFromL2Children(node, childrenByParent, aggregateByNodeId));
            }
        }

        return allNodes.stream().map(node -> toResponse(node, aggregateByNodeId)).toList();
    }

    private record Aggregate(WbsNode.Status status, LocalDate startDate, LocalDate endDate) {
    }

    private Aggregate aggregateFromL3Children(WbsNode l2Node, Map<Long, List<WbsNode>> childrenByParent) {
        List<WbsNode> children = childrenByParent.getOrDefault(l2Node.getId(), List.of());
        return aggregate(
            children.stream().map(WbsNode::getStatus).toList(),
            children.stream().map(WbsNode::getStartDate).filter(Objects::nonNull).toList(),
            children.stream().map(WbsNode::getEndDate).filter(Objects::nonNull).toList()
        );
    }

    private Aggregate aggregateFromL2Children(WbsNode l1Node, Map<Long, List<WbsNode>> childrenByParent,
            Map<Long, Aggregate> aggregateByNodeId) {
        List<WbsNode> children = childrenByParent.getOrDefault(l1Node.getId(), List.of());
        return aggregate(
            children.stream().map(c -> aggregateByNodeId.get(c.getId()).status()).toList(),
            children.stream().map(c -> aggregateByNodeId.get(c.getId()).startDate())
                .filter(Objects::nonNull).toList(),
            children.stream().map(c -> aggregateByNodeId.get(c.getId()).endDate())
                .filter(Objects::nonNull).toList()
        );
    }

    // 全部 DONE→DONE；全部 NOT_STARTED（含無子節點的空節點）→NOT_STARTED；其餘→IN_PROGRESS
    private Aggregate aggregate(List<WbsNode.Status> statuses, List<LocalDate> starts, List<LocalDate> ends) {
        WbsNode.Status status;
        if (statuses.isEmpty() || statuses.stream().allMatch(s -> s == WbsNode.Status.NOT_STARTED)) {
            status = WbsNode.Status.NOT_STARTED;
        } else if (statuses.stream().allMatch(s -> s == WbsNode.Status.DONE)) {
            status = WbsNode.Status.DONE;
        } else {
            status = WbsNode.Status.IN_PROGRESS;
        }
        LocalDate start = starts.stream().min(LocalDate::compareTo).orElse(null);
        LocalDate end = ends.stream().max(LocalDate::compareTo).orElse(null);
        return new Aggregate(status, start, end);
    }

    private WbsNodeDto.Response toResponse(WbsNode node, Map<Long, Aggregate> aggregateByNodeId) {
        Long parentId = node.getParent() != null ? node.getParent().getId() : null;
        if (node.getLevel() == 3) {
            return new WbsNodeDto.Response(
                node.getId(), parentId, node.getLevel(), node.getTitle(),
                node.getAssignee() != null ? node.getAssignee().getId() : null,
                node.getAssignee() != null ? node.getAssignee().getDisplayName() : null,
                node.getStatus() != null ? node.getStatus().name() : null,
                node.getPriority() != null ? node.getPriority().name() : null,
                node.getStartDate(), node.getEndDate(),
                node.getNotes(), node.getSortOrder()
            );
        }
        Aggregate agg = aggregateByNodeId.get(node.getId());
        return new WbsNodeDto.Response(
            node.getId(), parentId, node.getLevel(), node.getTitle(),
            null, null,
            agg.status().name(), null,
            agg.startDate(), agg.endDate(),
            node.getNotes(), node.getSortOrder()
        );
    }
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsNodeServiceTest`
Expected: PASS（17 個測試）；同時跑 `mvn test` 全套確認全綠

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsNodeDto.java \
        src/main/java/com/wbsflow/wbs/WbsNodeService.java \
        src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java
git commit -m "feat: WbsNodeService 新增父層狀態/日期彙總"
```

---

### Task 4: WbsNodeService — reorder（跨層級搬移＋子樹位移）

**Files:**
- Modify: `src/main/java/com/wbsflow/wbs/WbsNodeDto.java`
- Modify: `src/main/java/com/wbsflow/wbs/WbsNodeService.java`
- Modify: `src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WbsNodeRepository.findByProjectId`
- Produces:
  - `WbsNodeDto.ReorderItem(Long nodeId, Long parentId, int sortOrder)`
  - `WbsNodeService.reorder(Long projectId, List<WbsNodeDto.ReorderItem> items): void`

**注意**：本任務假設同一批次 `items` 內不會同時搬移一個節點與它的新父節點（典型拖拉 UI 一次只搬一個節點，批次呼叫是為了同時更新其他手足節點的 `sortOrder`）。層級計算一律以搬移前（批次套用前）的節點狀態為準，不處理批次內互相影響的複合情境。

- [ ] **Step 1: 寫失敗測試**

在 `WbsNodeServiceTest.java` 現有類別內、`getTreeReturnsStoredValuesDirectlyForL3` 方法之後加入：

```java
    @Test
    void reorderChangesSortOrderWithinSameParent() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2a = newL2(l1, "類別A");
        WbsNode l2b = newL2(l1, "類別B");

        wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l2a.getId(), l1.getId(), 1),
            new WbsNodeDto.ReorderItem(l2b.getId(), l1.getId(), 0)
        ));

        assertThat(wbsNodeRepository.findById(l2a.getId()).orElseThrow().getSortOrder()).isEqualTo(1);
        assertThat(wbsNodeRepository.findById(l2b.getId()).orElseThrow().getSortOrder()).isEqualTo(0);
    }

    @Test
    void reorderAcrossLevelsPromotesL3ToL1() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l3.getId(), null, 0)
        ));

        WbsNode moved = wbsNodeRepository.findById(l3.getId()).orElseThrow();
        assertThat(moved.getLevel()).isEqualTo((short) 1);
        assertThat(moved.getParent()).isNull();
    }

    @Test
    void reorderClearsL3OnlyFieldsWhenNodeNoLongerL3() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        l3.setStatus(WbsNode.Status.IN_PROGRESS);
        l3.setPriority(WbsNode.Priority.HIGH);
        wbsNodeRepository.save(l3);

        wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l3.getId(), null, 0)
        ));

        WbsNode moved = wbsNodeRepository.findById(l3.getId()).orElseThrow();
        assertThat(moved.getStatus()).isNull();
        assertThat(moved.getPriority()).isNull();
    }

    @Test
    void reorderCascadesLevelShiftToDescendants() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        WbsNode anotherL1 = newL1("UAT");

        // 把 L2（帶著它的 L3 子節點）搬到另一個 L1 底下，level 應維持 2/3 不變（同層搬移，delta=0）
        wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l2.getId(), anotherL1.getId(), 0)
        ));

        assertThat(wbsNodeRepository.findById(l2.getId()).orElseThrow().getLevel()).isEqualTo((short) 2);
        assertThat(wbsNodeRepository.findById(l3.getId()).orElseThrow().getLevel()).isEqualTo((short) 3);
        assertThat(wbsNodeRepository.findById(l3.getId()).orElseThrow().getParent().getId()).isEqualTo(l2.getId());
    }

    @Test
    void reorderRejectsMoveThatWouldExceedThreeLevels() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        WbsNode anotherL1 = newL1("UAT");
        WbsNode anotherL2 = newL2(anotherL1, "環境建置");
        WbsNode anotherL3 = newL3(anotherL2, "防火牆申請");

        // 把帶有 L3 子節點的 L2 搬到別的 L2 底下會變成 L3，其子節點會變成第四層 → 拒絕
        assertThatThrownBy(() -> wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l2.getId(), anotherL2.getId(), 0)
        ))).isInstanceOf(IllegalArgumentException.class);

        // 確認拒絕後完全沒有套用（level 與 parent 都維持原狀）
        assertThat(wbsNodeRepository.findById(l2.getId()).orElseThrow().getLevel()).isEqualTo((short) 2);
        assertThat(wbsNodeRepository.findById(l2.getId()).orElseThrow().getParent().getId()).isEqualTo(l1.getId());
    }

    @Test
    void reorderRejectsNodeNotBelongingToProject() {
        Project otherProject = new Project();
        otherProject.setName("別的專案");
        otherProject.setSection(sectionA);
        otherProject.setOwner(project.getOwner());
        otherProject.setCreatedBy(project.getOwner());
        otherProject = projectRepository.save(otherProject);

        WbsNode foreignL1 = new WbsNode();
        foreignL1.setProject(otherProject);
        foreignL1.setLevel((short) 1);
        foreignL1.setTitle("別專案的階段");
        foreignL1 = wbsNodeRepository.save(foreignL1);

        assertThatThrownBy(() -> wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(foreignL1.getId(), null, 0)
        ))).isInstanceOf(SecurityException.class);
    }
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsNodeServiceTest`
Expected: FAIL（編譯錯誤，`WbsNodeDto.ReorderItem`/`reorder` 不存在）

- [ ] **Step 3: 寫最小實作**

在 `WbsNodeDto.java` 的 `Response` record 之後加入：

```java
    public record ReorderItem(Long nodeId, Long parentId, int sortOrder) {
    }
```

不需要新增 import——`Map`／`List`／`HashMap`／`Collectors` 皆已在 Task 3 加入過。

在 `getTree` 方法群組之後、`getNodeInProject` 私有方法之前，加入：

```java
    // 支援跨層級搬移：套用前先驗證整批操作都不會讓任何子孫超過三層上限，超限則整批拒絕、不部分套用
    @Transactional
    public void reorder(Long projectId, List<WbsNodeDto.ReorderItem> items) {
        List<WbsNode> allNodes = wbsNodeRepository.findByProjectId(projectId);
        Map<Long, WbsNode> nodeById = allNodes.stream()
            .collect(Collectors.toMap(WbsNode::getId, n -> n));
        Map<Long, List<WbsNode>> childrenByParent = allNodes.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));

        for (WbsNodeDto.ReorderItem item : items) {
            if (!nodeById.containsKey(item.nodeId())) {
                throw new SecurityException("節點不屬於此專案: " + item.nodeId());
            }
            if (item.parentId() != null && !nodeById.containsKey(item.parentId())) {
                throw new SecurityException("父節點不屬於此專案: " + item.parentId());
            }
        }

        Map<Long, Integer> deltaByNodeId = new HashMap<>();
        for (WbsNodeDto.ReorderItem item : items) {
            WbsNode node = nodeById.get(item.nodeId());
            int newLevel = item.parentId() == null ? 1 : nodeById.get(item.parentId()).getLevel() + 1;
            int subtreeDepth = maxDepth(node.getId(), childrenByParent);
            if (newLevel + subtreeDepth - 1 > 3) {
                throw new IllegalArgumentException("搬移後子樹層級將超過三層上限");
            }
            deltaByNodeId.put(item.nodeId(), newLevel - node.getLevel());
        }

        for (WbsNodeDto.ReorderItem item : items) {
            WbsNode node = nodeById.get(item.nodeId());
            WbsNode newParent = item.parentId() == null ? null : nodeById.get(item.parentId());
            node.setParent(newParent);
            node.setSortOrder(item.sortOrder());
            int delta = deltaByNodeId.get(item.nodeId());
            applyLevelShift(node, delta);
            wbsNodeRepository.save(node);
            for (WbsNode child : childrenByParent.getOrDefault(node.getId(), List.of())) {
                shiftDescendant(child, delta, childrenByParent);
            }
        }
    }

    // 回傳以 nodeId 為根的子樹最大深度（葉節點本身深度為 1）
    private int maxDepth(Long nodeId, Map<Long, List<WbsNode>> childrenByParent) {
        List<WbsNode> children = childrenByParent.getOrDefault(nodeId, List.of());
        if (children.isEmpty()) return 1;
        int max = 0;
        for (WbsNode child : children) {
            max = Math.max(max, maxDepth(child.getId(), childrenByParent));
        }
        return 1 + max;
    }

    // 套用 level 位移；若節點因此不再是 L3，清空 L3 專屬欄位以符合 DB CHECK 約束
    private void applyLevelShift(WbsNode node, int delta) {
        if (delta == 0) return;
        node.setLevel((short) (node.getLevel() + delta));
        if (node.getLevel() != 3) {
            node.setAssignee(null);
            node.setStatus(null);
            node.setPriority(null);
            node.setStartDate(null);
            node.setEndDate(null);
        }
    }

    private void shiftDescendant(WbsNode node, int delta, Map<Long, List<WbsNode>> childrenByParent) {
        applyLevelShift(node, delta);
        wbsNodeRepository.save(node);
        for (WbsNode child : childrenByParent.getOrDefault(node.getId(), List.of())) {
            shiftDescendant(child, delta, childrenByParent);
        }
    }
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsNodeServiceTest`
Expected: PASS（23 個測試）；同時跑 `mvn test` 全套確認全綠

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsNodeDto.java \
        src/main/java/com/wbsflow/wbs/WbsNodeService.java \
        src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java
git commit -m "feat: WbsNodeService 新增 reorder（含跨層級搬移與子樹位移）"
```

---

### Task 5: WbsNodeService — 狀態/指派專用方法 + L1 骨架初始化

**Files:**
- Modify: `src/main/java/com/wbsflow/wbs/WbsNodeDto.java`
- Modify: `src/main/java/com/wbsflow/wbs/WbsNodeService.java`
- Modify: `src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java`

**Interfaces:**
- Consumes: 既有 `ProjectService.isMember`（子專案 A/C）、`UserRepository`、Task 1 的 `WbsPresetRepository.findVisiblePresets`/`WbsNodeRepository.existsByProjectId`
- Produces:
  - `WbsNodeDto.StatusRequest(String status)`
  - `WbsNodeDto.AssigneeRequest(Long assigneeId)`
  - `WbsNodeDto.InitRequest(List<Long> stagePresetIds)`
  - `WbsNodeService.updateStatus(Long projectId, Long nodeId, String status): WbsNode`
  - `WbsNodeService.updateAssignee(Long projectId, Long nodeId, Long assigneeId): WbsNode`
  - `WbsNodeService.initStages(Long projectId, List<Long> stagePresetIds): void`

- [ ] **Step 1: 寫失敗測試**

在 `WbsNodeServiceTest.java` 的 import 區塊加入：
```java
import com.wbsflow.project.ProjectMember;
import com.wbsflow.project.ProjectMemberId;
import com.wbsflow.project.ProjectMemberRepository;
```

在 class 頂端新增欄位：
```java
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
```

在 `reorderRejectsNodeNotBelongingToProject` 方法之後加入：

```java
    @Test
    void updateStatusOnL3Node() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        WbsNode updated = wbsNodeService.updateStatus(project.getId(), l3.getId(), "IN_PROGRESS");

        assertThat(updated.getStatus()).isEqualTo(WbsNode.Status.IN_PROGRESS);
    }

    @Test
    void updateStatusRejectedOnNonL3Node() {
        WbsNode l1 = newL1("SIT");

        assertThatThrownBy(() -> wbsNodeService.updateStatus(project.getId(), l1.getId(), "DONE"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateStatusRejectsInvalidEnumValue() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        assertThatThrownBy(() -> wbsNodeService.updateStatus(project.getId(), l3.getId(), "NOT_A_STATUS"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateAssigneeRequiresProjectMembership() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        User outsider = userRepository.save(newUser("outsider", sectionA));

        assertThatThrownBy(() -> wbsNodeService.updateAssignee(project.getId(), l3.getId(), outsider.getId()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateAssigneeSucceedsForProjectMember() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        User member = userRepository.save(newUser("member", sectionA));
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), member.getId()));
        pm.setAssignedBy(member);
        projectMemberRepository.save(pm);

        WbsNode updated = wbsNodeService.updateAssignee(project.getId(), l3.getId(), member.getId());

        assertThat(updated.getAssignee().getId()).isEqualTo(member.getId());
    }

    @Test
    void updateAssigneeWithNullClearsAssignment() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        User member = userRepository.save(newUser("member", sectionA));
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), member.getId()));
        pm.setAssignedBy(member);
        projectMemberRepository.save(pm);
        wbsNodeService.updateAssignee(project.getId(), l3.getId(), member.getId());

        WbsNode cleared = wbsNodeService.updateAssignee(project.getId(), l3.getId(), null);

        assertThat(cleared.getAssignee()).isNull();
    }

    @Test
    void updateAssigneeRejectedOnNonL3Node() {
        WbsNode l1 = newL1("SIT");
        User member = userRepository.save(newUser("member", sectionA));

        assertThatThrownBy(() -> wbsNodeService.updateAssignee(project.getId(), l1.getId(), member.getId()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initStagesCreatesL1NodeForEachSelectedStage() {
        wbsNodeService.initStages(project.getId(), List.of(stagePreset.getId()));

        List<WbsNode> nodes = wbsNodeRepository.findByProjectId(project.getId());
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getLevel()).isEqualTo((short) 1);
        assertThat(nodes.get(0).getTitle()).isEqualTo("SIT");
    }

    @Test
    void initStagesDefaultsToAllVisibleStagesWhenNoneSpecified() {
        wbsNodeService.initStages(project.getId(), null);

        List<WbsNode> nodes = wbsNodeRepository.findByProjectId(project.getId());
        // setUp 只建立了一個對本專案科別可見的 STAGE（stagePreset，全域）；
        // otherSectionStagePreset 屬於 sectionB，對本專案（sectionA）不可見
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getTitle()).isEqualTo("SIT");
    }

    @Test
    void initStagesRejectsWhenProjectAlreadyHasNodes() {
        newL1("既有節點");

        assertThatThrownBy(() -> wbsNodeService.initStages(project.getId(), List.of(stagePreset.getId())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initStagesRejectsPresetFromOtherSection() {
        assertThatThrownBy(() -> wbsNodeService.initStages(project.getId(), List.of(otherSectionStagePreset.getId())))
            .isInstanceOf(IllegalArgumentException.class);
    }
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsNodeServiceTest`
Expected: FAIL（編譯錯誤，`updateStatus`/`updateAssignee`/`initStages`/對應 DTO 不存在）

- [ ] **Step 3: 寫最小實作**

在 `WbsNodeDto.java` 的 `ReorderItem` record 之後加入：

```java
    public record StatusRequest(String status) {
    }

    public record AssigneeRequest(Long assigneeId) {
    }

    public record InitRequest(java.util.List<Long> stagePresetIds) {
    }
```

在 `WbsNodeService.java` 的建構子欄位加入 `UserRepository`：
```java
    private final com.wbsflow.user.UserRepository userRepository;
```
（`@RequiredArgsConstructor` 會自動納入建構子，只需新增欄位）

在 import 區塊加入：
```java
import com.wbsflow.user.User;
```

在 `reorder` 方法群組之後、`getNodeInProject` 私有方法之前，加入：

```java
    // 僅 L3 可設定狀態；非法字串由 WbsNode.Status.valueOf 自動拋 IllegalArgumentException（GlobalExceptionHandler 已處理）
    @Transactional
    public WbsNode updateStatus(Long projectId, Long nodeId, String status) {
        WbsNode node = getNodeInProject(projectId, nodeId);
        if (node.getLevel() != 3) {
            throw new IllegalArgumentException("僅細項（L3）可設定狀態");
        }
        node.setStatus(WbsNode.Status.valueOf(status));
        return wbsNodeRepository.save(node);
    }

    // 僅 L3 可指派；assigneeId 為 null 表示取消指派；新指派對象必須是專案成員
    @Transactional
    public WbsNode updateAssignee(Long projectId, Long nodeId, Long assigneeId) {
        WbsNode node = getNodeInProject(projectId, nodeId);
        if (node.getLevel() != 3) {
            throw new IllegalArgumentException("僅細項（L3）可指派");
        }
        if (assigneeId == null) {
            node.setAssignee(null);
        } else {
            if (!projectService.isMember(projectId, assigneeId)) {
                throw new IllegalArgumentException("指派對象必須是專案成員");
            }
            User assignee = userRepository.findById(assigneeId)
                .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
            node.setAssignee(assignee);
        }
        return wbsNodeRepository.save(node);
    }

    // 建專案 L1 骨架：不傳 stagePresetIds 則預設該專案科別可見的全部已啟用 STAGE；僅空專案可初始化
    @Transactional
    public void initStages(Long projectId, List<Long> stagePresetIds) {
        Project project = projectService.getById(projectId);
        if (wbsNodeRepository.existsByProjectId(projectId)) {
            throw new IllegalArgumentException("專案已有節點，無法重複初始化");
        }

        List<WbsPreset> presets;
        if (stagePresetIds == null || stagePresetIds.isEmpty()) {
            presets = wbsPresetRepository.findVisiblePresets(WbsPreset.Type.STAGE, project.getSection().getId());
        } else {
            presets = wbsPresetRepository.findAllById(stagePresetIds);
            for (WbsPreset preset : presets) {
                if (preset.getType() != WbsPreset.Type.STAGE) {
                    throw new IllegalArgumentException("選單項目型別不符: " + preset.getId());
                }
                if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
                    throw new IllegalArgumentException("選單項目不屬於此專案科別: " + preset.getId());
                }
            }
        }

        int sortOrder = 0;
        for (WbsPreset preset : presets) {
            WbsNode node = new WbsNode();
            node.setProject(project);
            node.setLevel((short) 1);
            node.setTitle(preset.getName());
            node.setSortOrder(sortOrder++);
            wbsNodeRepository.save(node);
        }
    }
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsNodeServiceTest`
Expected: PASS（33 個測試）；同時跑 `mvn test` 全套確認全綠

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsNodeDto.java \
        src/main/java/com/wbsflow/wbs/WbsNodeService.java \
        src/test/java/com/wbsflow/wbs/WbsNodeServiceTest.java
git commit -m "feat: WbsNodeService 新增狀態/指派專用方法與 L1 骨架初始化"
```

---

### Task 6: WbsNodeController — 節點 REST 端點

**Files:**
- Create: `src/main/java/com/wbsflow/wbs/WbsNodeController.java`
- Test: `src/test/java/com/wbsflow/wbs/WbsNodeControllerTest.java`

**Interfaces:**
- Consumes: Task 2-5 的全部 `WbsNodeService` 方法與 `WbsNodeDto` 記錄、既有 `ProjectService.canRead/canWrite`
- Produces:
  - `GET/POST /api/projects/{projectId}/nodes`
  - `POST /api/projects/{projectId}/nodes/init`
  - `PUT/DELETE /api/projects/{projectId}/nodes/{nodeId}`
  - `PATCH /api/projects/{projectId}/nodes/reorder`
  - `PATCH /api/projects/{projectId}/nodes/{nodeId}/status`
  - `PATCH /api/projects/{projectId}/nodes/{nodeId}/assignee`

- [ ] **Step 1: 寫失敗測試**

建立 `src/test/java/com/wbsflow/wbs/WbsNodeControllerTest.java`：

```java
package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.project.Project;
import com.wbsflow.project.ProjectMember;
import com.wbsflow.project.ProjectMemberId;
import com.wbsflow.project.ProjectMemberRepository;
import com.wbsflow.project.ProjectRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WbsNodeControllerTest {

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
    private WbsNodeRepository wbsNodeRepository;

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department sectionA;
    private Department sectionB;
    private Project project;
    private WbsPreset stagePreset;
    private WbsPreset categoryPreset;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        User leader = saveUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
        saveUser("chiefB", User.Role.SECTION_CHIEF, sectionB);

        Project p = new Project();
        p.setName("測試專案");
        p.setSection(sectionA);
        p.setOwner(leader);
        p.setCreatedBy(leader);
        project = projectRepository.save(p);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), leader.getId()));
        pm.setAssignedBy(leader);
        projectMemberRepository.save(pm);

        stagePreset = newPreset(WbsPreset.Type.STAGE, "SIT", null);
        categoryPreset = newPreset(WbsPreset.Type.CATEGORY, "程式開發", null);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private User saveUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private WbsPreset newPreset(WbsPreset.Type type, String name, Department section) {
        WbsPreset preset = new WbsPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(1);
        preset.setSection(section);
        return wbsPresetRepository.save(preset);
    }

    private WbsNode newL1(String title) {
        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setLevel((short) 1);
        node.setTitle(title);
        return wbsNodeRepository.save(node);
    }

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void createL1NodeFromStagePreset() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/projects/" + project.getId() + "/nodes").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"presetId\":" + stagePreset.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("SIT"))
            .andExpect(jsonPath("$.data.level").value(1));
    }

    @Test
    void listReturnsFullTreeWithAggregation() throws Exception {
        WbsNode l1 = newL1("SIT");
        Cookie session = loginAs("leaderA");

        mockMvc.perform(get("/api/projects/" + project.getId() + "/nodes").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].status").value("NOT_STARTED"));
    }

    @Test
    void listDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(get("/api/projects/" + project.getId() + "/nodes").cookie(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateNodeTitle() throws Exception {
        WbsNode l1 = newL1("SIT");
        Cookie session = loginAs("leaderA");

        mockMvc.perform(put("/api/projects/" + project.getId() + "/nodes/" + l1.getId())
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"改過的標題\"}"))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(l1.getId()).orElseThrow().getTitle()).isEqualTo("改過的標題");
    }

    @Test
    void deleteNodeRemovesIt() throws Exception {
        WbsNode l1 = newL1("SIT");
        Cookie session = loginAs("leaderA");

        mockMvc.perform(delete("/api/projects/" + project.getId() + "/nodes/" + l1.getId())
                .cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(l1.getId())).isEmpty();
    }

    @Test
    void reorderUpdatesSortOrder() throws Exception {
        WbsNode l1a = newL1("SIT");
        WbsNode l1b = newL1("UAT");
        Cookie session = loginAs("leaderA");

        mockMvc.perform(patch("/api/projects/" + project.getId() + "/nodes/reorder")
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"nodeId\":" + l1a.getId() + ",\"parentId\":null,\"sortOrder\":1},"
                    + "{\"nodeId\":" + l1b.getId() + ",\"parentId\":null,\"sortOrder\":0}]"))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(l1a.getId()).orElseThrow().getSortOrder()).isEqualTo(1);
    }

    @Test
    void updateStatusOnL3Node() throws Exception {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = new WbsNode();
        l2.setProject(project);
        l2.setParent(l1);
        l2.setLevel((short) 2);
        l2.setTitle("程式開發");
        l2 = wbsNodeRepository.save(l2);
        WbsNode l3 = new WbsNode();
        l3.setProject(project);
        l3.setParent(l2);
        l3.setLevel((short) 3);
        l3.setTitle("登入功能開發");
        l3 = wbsNodeRepository.save(l3);

        Cookie session = loginAs("leaderA");
        mockMvc.perform(patch("/api/projects/" + project.getId() + "/nodes/" + l3.getId() + "/status")
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(l3.getId()).orElseThrow().getStatus())
            .isEqualTo(WbsNode.Status.IN_PROGRESS);
    }

    @Test
    void updateAssigneeRejectsNonMember() throws Exception {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = new WbsNode();
        l2.setProject(project);
        l2.setParent(l1);
        l2.setLevel((short) 2);
        l2.setTitle("程式開發");
        l2 = wbsNodeRepository.save(l2);
        WbsNode l3 = new WbsNode();
        l3.setProject(project);
        l3.setParent(l2);
        l3.setLevel((short) 3);
        l3.setTitle("登入功能開發");
        l3 = wbsNodeRepository.save(l3);
        User outsider = saveUser("outsiderX", User.Role.PROJECT_MEMBER, sectionA);

        Cookie session = loginAs("leaderA");
        mockMvc.perform(patch("/api/projects/" + project.getId() + "/nodes/" + l3.getId() + "/assignee")
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\":" + outsider.getId() + "}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void initEndpointCreatesL1SkeletonFromDefaultStages() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/projects/" + project.getId() + "/nodes/init")
                .cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findByProjectId(project.getId())).hasSize(1);
    }

    @Test
    void writeOperationsDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(post("/api/projects/" + project.getId() + "/nodes").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"presetId\":" + stagePreset.getId() + "}"))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsNodeControllerTest`
Expected: FAIL（`WbsNodeController` 不存在，404）

- [ ] **Step 3: 寫最小實作**

建立 `src/main/java/com/wbsflow/wbs/WbsNodeController.java`：

```java
package com.wbsflow.wbs;

import com.wbsflow.common.ApiResponse;
import com.wbsflow.project.ProjectService;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class WbsNodeController {

    private final WbsNodeService wbsNodeService;
    private final ProjectService projectService;
    private final UserRepository userRepository;

    @GetMapping("/api/projects/{projectId}/nodes")
    public ApiResponse<List<WbsNodeDto.Response>> list(@PathVariable Long projectId, Principal principal) {
        checkRead(projectId, principal);
        return ApiResponse.ok(wbsNodeService.getTree(projectId));
    }

    @PostMapping("/api/projects/{projectId}/nodes")
    public ApiResponse<WbsNodeDto.Response> create(@PathVariable Long projectId,
            @RequestBody WbsNodeDto.CreateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        WbsNode node = wbsNodeService.createNode(projectId, req);
        return ApiResponse.ok(toNewNodeResponse(node));
    }

    @PostMapping("/api/projects/{projectId}/nodes/init")
    public ApiResponse<Void> init(@PathVariable Long projectId,
            @RequestBody(required = false) WbsNodeDto.InitRequest req, Principal principal) {
        checkWrite(projectId, principal);
        List<Long> stagePresetIds = req != null ? req.stagePresetIds() : null;
        wbsNodeService.initStages(projectId, stagePresetIds);
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/projects/{projectId}/nodes/{nodeId}")
    public ApiResponse<Void> update(@PathVariable Long projectId, @PathVariable Long nodeId,
            @RequestBody WbsNodeDto.UpdateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.updateNode(projectId, nodeId, req);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/projects/{projectId}/nodes/{nodeId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long nodeId, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.deleteNode(projectId, nodeId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/nodes/reorder")
    public ApiResponse<Void> reorder(@PathVariable Long projectId,
            @RequestBody List<WbsNodeDto.ReorderItem> items, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.reorder(projectId, items);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/nodes/{nodeId}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long projectId, @PathVariable Long nodeId,
            @RequestBody WbsNodeDto.StatusRequest req, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.updateStatus(projectId, nodeId, req.status());
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/nodes/{nodeId}/assignee")
    public ApiResponse<Void> updateAssignee(@PathVariable Long projectId, @PathVariable Long nodeId,
            @RequestBody WbsNodeDto.AssigneeRequest req, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.updateAssignee(projectId, nodeId, req.assigneeId());
        return ApiResponse.ok(null);
    }

    // 新建節點必無子節點，L1/L2 的彙總狀態可直接視為 NOT_STARTED、日期為 null，不需查詢子節點
    private WbsNodeDto.Response toNewNodeResponse(WbsNode node) {
        Long parentId = node.getParent() != null ? node.getParent().getId() : null;
        String status = node.getLevel() == 3
            ? (node.getStatus() != null ? node.getStatus().name() : null)
            : WbsNode.Status.NOT_STARTED.name();
        return new WbsNodeDto.Response(
            node.getId(), parentId, node.getLevel(), node.getTitle(),
            null, null, status, null, null, null,
            node.getNotes(), node.getSortOrder()
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

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsNodeControllerTest`
Expected: PASS（10 個測試）；同時跑 `mvn test` 全套確認全綠

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsNodeController.java \
        src/test/java/com/wbsflow/wbs/WbsNodeControllerTest.java
git commit -m "feat: 新增節點 REST 端點"
```

---

### Task 7: wbs_presets 選單管理（Service + Controller）

**Files:**
- Create: `src/main/java/com/wbsflow/wbs/WbsPresetDto.java`
- Create: `src/main/java/com/wbsflow/wbs/WbsPresetService.java`
- Create: `src/main/java/com/wbsflow/wbs/WbsPresetController.java`
- Test: `src/test/java/com/wbsflow/wbs/WbsPresetServiceTest.java`
- Test: `src/test/java/com/wbsflow/wbs/WbsPresetControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `WbsPresetRepository.findVisiblePresets`
- Produces:
  - `WbsPresetService.list(WbsPreset.Type type, Long sectionId): List<WbsPreset>`
  - `WbsPresetService.create(WbsPreset.Type type, String name, int sortOrder, User actor): WbsPreset`
  - `WbsPresetService.update(Long presetId, String name, Integer sortOrder, Boolean enabled, User actor): WbsPreset`
  - `WbsPresetService.delete(Long presetId, User actor): void`
  - `GET/POST/PUT/DELETE /api/presets`（`GET`/`POST` 在 `/api/presets`，`PUT`/`DELETE` 在 `/api/presets/{id}`）

- [ ] **Step 1: 寫失敗測試**

建立 `src/test/java/com/wbsflow/wbs/WbsPresetServiceTest.java`：

```java
package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
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
class WbsPresetServiceTest {

    @Autowired
    private WbsPresetService wbsPresetService;

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

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
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));
        chiefA = userRepository.save(newUser("chiefA", User.Role.SECTION_CHIEF, sectionA));
        chiefB = userRepository.save(newUser("chiefB", User.Role.SECTION_CHIEF, sectionB));
        leaderA = userRepository.save(newUser("leaderA", User.Role.PROJECT_LEADER, sectionA));
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private User newUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("hash");
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return u;
    }

    @Test
    void sectionChiefCreatesPresetScopedToOwnSection() {
        WbsPreset created = wbsPresetService.create(WbsPreset.Type.STAGE, "自訂階段", 1, chiefA);

        assertThat(created.getSection().getId()).isEqualTo(sectionA.getId());
        assertThat(created.getType()).isEqualTo(WbsPreset.Type.STAGE);
    }

    @Test
    void nonChiefCannotCreatePreset() {
        assertThatThrownBy(() -> wbsPresetService.create(WbsPreset.Type.STAGE, "自訂階段", 1, leaderA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCanUpdateOwnSectionPreset() {
        WbsPreset preset = wbsPresetService.create(WbsPreset.Type.STAGE, "自訂階段", 1, chiefA);

        WbsPreset updated = wbsPresetService.update(preset.getId(), "改過的名稱", null, null, chiefA);

        assertThat(updated.getName()).isEqualTo("改過的名稱");
    }

    @Test
    void sectionChiefCannotUpdateOtherSectionPreset() {
        WbsPreset presetB = wbsPresetService.create(WbsPreset.Type.STAGE, "B科自訂", 1, chiefB);

        assertThatThrownBy(() -> wbsPresetService.update(presetB.getId(), "想改別科", null, null, chiefA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCannotUpdateGlobalPreset() {
        WbsPreset global = new WbsPreset();
        global.setType(WbsPreset.Type.STAGE);
        global.setName("全域預設");
        global.setSortOrder(1);
        global = wbsPresetRepository.save(global);
        Long globalId = global.getId();

        assertThatThrownBy(() -> wbsPresetService.update(globalId, "想改全域", null, null, chiefA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCanDeleteOwnSectionPreset() {
        WbsPreset preset = wbsPresetService.create(WbsPreset.Type.CATEGORY, "自訂類別", 1, chiefA);

        wbsPresetService.delete(preset.getId(), chiefA);

        assertThat(wbsPresetRepository.findById(preset.getId())).isEmpty();
    }
}
```

建立 `src/test/java/com/wbsflow/wbs/WbsPresetControllerTest.java`：

```java
package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WbsPresetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department sectionA;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        saveUser("chiefA", User.Role.SECTION_CHIEF, sectionA);
        saveUser("leaderA", User.Role.PROJECT_LEADER, sectionA);

        WbsPreset global = new WbsPreset();
        global.setType(WbsPreset.Type.STAGE);
        global.setName("SIT");
        global.setSortOrder(1);
        wbsPresetRepository.save(global);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private void saveUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        userRepository.save(u);
    }

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void listReturnsVisiblePresetsForType() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(get("/api/presets").param("type", "STAGE").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("SIT"));
    }

    @Test
    void sectionChiefCreatesPreset() throws Exception {
        Cookie session = loginAs("chiefA");

        mockMvc.perform(post("/api/presets").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CATEGORY\",\"name\":\"自訂類別\",\"sortOrder\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("自訂類別"));
    }

    @Test
    void nonChiefCannotCreatePreset() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/presets").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CATEGORY\",\"name\":\"自訂類別\",\"sortOrder\":1}"))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsPresetServiceTest,WbsPresetControllerTest`
Expected: FAIL（`WbsPresetService`/`WbsPresetController`/`WbsPresetDto` 不存在）

- [ ] **Step 3: 寫最小實作**

建立 `src/main/java/com/wbsflow/wbs/WbsPresetDto.java`：

```java
package com.wbsflow.wbs;

public class WbsPresetDto {

    public record Response(Long id, WbsPreset.Type type, String name, int sortOrder,
                            boolean enabled, Long sectionId) {
        public static Response from(WbsPreset preset) {
            return new Response(
                preset.getId(), preset.getType(), preset.getName(), preset.getSortOrder(),
                preset.isEnabled(), preset.getSection() != null ? preset.getSection().getId() : null
            );
        }
    }

    public record CreateRequest(WbsPreset.Type type, String name, int sortOrder) {
    }

    public record UpdateRequest(String name, Integer sortOrder, Boolean enabled) {
    }
}
```

建立 `src/main/java/com/wbsflow/wbs/WbsPresetService.java`：

```java
package com.wbsflow.wbs;

import com.wbsflow.user.User;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WbsPresetService {

    private final WbsPresetRepository wbsPresetRepository;

    public List<WbsPreset> list(WbsPreset.Type type, Long sectionId) {
        return wbsPresetRepository.findVisiblePresets(type, sectionId);
    }

    // 只有科長可管理選單，且新建項目一律歸屬建立者自己的科別
    @Transactional
    public WbsPreset create(WbsPreset.Type type, String name, int sortOrder, User actor) {
        requireSectionChief(actor);
        WbsPreset preset = new WbsPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(sortOrder);
        preset.setSection(actor.getDepartment());
        return wbsPresetRepository.save(preset);
    }

    @Transactional
    public WbsPreset update(Long presetId, String name, Integer sortOrder, Boolean enabled, User actor) {
        WbsPreset preset = getOwnedPreset(presetId, actor);
        if (name != null) preset.setName(name);
        if (sortOrder != null) preset.setSortOrder(sortOrder);
        if (enabled != null) preset.setEnabled(enabled);
        return wbsPresetRepository.save(preset);
    }

    @Transactional
    public void delete(Long presetId, User actor) {
        WbsPreset preset = getOwnedPreset(presetId, actor);
        wbsPresetRepository.delete(preset);
    }

    // 全域預設（section 為 null）任何角色皆不可修改；自訂項目僅同科科長可管理
    private WbsPreset getOwnedPreset(Long presetId, User actor) {
        requireSectionChief(actor);
        WbsPreset preset = wbsPresetRepository.findById(presetId)
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

建立 `src/main/java/com/wbsflow/wbs/WbsPresetController.java`：

```java
package com.wbsflow.wbs;

import com.wbsflow.common.ApiResponse;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class WbsPresetController {

    private final WbsPresetService wbsPresetService;
    private final UserRepository userRepository;

    // 不傳 sectionId 時預設用呼叫者自己的部門，方便前端下拉選單直接查詢
    @GetMapping("/api/presets")
    public ApiResponse<List<WbsPresetDto.Response>> list(
            @RequestParam WbsPreset.Type type,
            @RequestParam(required = false) Long sectionId,
            Principal principal) {
        User user = currentUser(principal);
        Long effectiveSectionId = sectionId != null ? sectionId
            : (user.getDepartment() != null ? user.getDepartment().getId() : null);
        List<WbsPresetDto.Response> result = wbsPresetService.list(type, effectiveSectionId)
            .stream().map(WbsPresetDto.Response::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/presets")
    public ApiResponse<WbsPresetDto.Response> create(
            @RequestBody WbsPresetDto.CreateRequest req, Principal principal) {
        User user = currentUser(principal);
        WbsPreset preset = wbsPresetService.create(req.type(), req.name(), req.sortOrder(), user);
        return ApiResponse.ok(WbsPresetDto.Response.from(preset));
    }

    @PutMapping("/api/presets/{id}")
    public ApiResponse<WbsPresetDto.Response> update(@PathVariable Long id,
            @RequestBody WbsPresetDto.UpdateRequest req, Principal principal) {
        User user = currentUser(principal);
        WbsPreset preset = wbsPresetService.update(id, req.name(), req.sortOrder(), req.enabled(), user);
        return ApiResponse.ok(WbsPresetDto.Response.from(preset));
    }

    @DeleteMapping("/api/presets/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        wbsPresetService.delete(id, user);
        return ApiResponse.ok(null);
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsPresetServiceTest,WbsPresetControllerTest`
Expected: PASS（6 個 Service 測試＋3 個 Controller 測試）；同時跑 `mvn test` 全套確認全專案（子專案 A/B/C/D 至此累積的所有測試）皆綠燈

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsPresetDto.java \
        src/main/java/com/wbsflow/wbs/WbsPresetService.java \
        src/main/java/com/wbsflow/wbs/WbsPresetController.java \
        src/test/java/com/wbsflow/wbs/WbsPresetServiceTest.java \
        src/test/java/com/wbsflow/wbs/WbsPresetControllerTest.java
git commit -m "feat: 新增 wbs_presets 選單管理"
```

---

## 全部完成後

- [ ] 執行 `mvn test` 確認全專案測試皆綠燈
- [ ] 實際啟動應用程式（`docker compose up -d` + `mvn spring-boot:run`），用測試帳號透過 API（curl 或瀏覽器開發工具）逐項驗證：
  - 建一個專案 → 呼叫 `POST .../nodes/init` 建 L1 骨架 → 重複呼叫應被拒絕
  - 建 L2/L3 節點、更新標題與 L3 專屬欄位、刪除節點確認子樹一併消失
  - 用實際的多層節點資料驗證彙總計算（全完成／部分完成／空節點三種情境），特別確認 L1 的彙總確實反映 L2 已彙總的結果、不是直接看 L3
  - reorder 跨層級搬移（含子樹連動位移、超過三層被拒絕且完全不套用）
  - 狀態/指派專用端點的層級限制與成員驗證
  - 選單管理的科別權限矩陣
- [ ] 回報使用者：本子專案完成，等待核准後進入前端子專案（Vue 樹編輯器等四檢視）
