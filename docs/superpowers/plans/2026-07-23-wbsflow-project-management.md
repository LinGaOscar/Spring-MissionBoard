# 子專案 C：專案管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 實作 Project 生命週期管理——建立、列表、查詢、封存/還原、成員管理、換負責人——加上支援用的 `GET /api/users`，以及一個 Thymeleaf `/projects` 頁殼作為登入後的入口。

**Architecture:** 擴充既有 `com.wbsflow.project` package（不新建 package）：`ProjectService` 加入 `canArchive`/`createProject`/`listForUser`/`archiveProject`/`unarchiveProject`/`addMember`/`removeMember`/`changeOwner`；新增 `ProjectDto`（record 集合）與 `ProjectController`（頁面殼 + REST API 混合，`@Controller` 搭配逐方法 `@ResponseBody`，比照舊專案 `Spring-WbsScaff` 的 `ProjectController` 慣例）。`com.wbsflow.user.UserController` 擴充 `GET /api/users`。

**Tech Stack:** Spring MVC、Spring Data JPA（含一個 `@Modifying` bulk update）、MockMvc + `spring-security-test`（`formLogin` 走完整登入態）、Bean Validation（`@NotBlank`）。

## Global Constraints

- **範圍界線**：本子專案**不做**「建專案時勾選階段自動產生 L1 骨架」（留給節點子專案）、**不做** `GET /api/departments`（目前沒有前端場景需要）、**不做** Vue 互動（`/projects` 頁面是純伺服器渲染 + 原生 `fetch`，无 Vue）
- **CSRF 是預設開啟的**（`SecurityConfig` 沒有 `.csrf(...)` 停用設定）。所有測試中對 POST/PUT/PATCH/DELETE 的 MockMvc 呼叫，都必須加 `.with(csrf())`（`import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;`），否則會收到 403 而非預期狀態碼——這是本計畫最容易踩的坑，每個 Task 的測試都要注意
- **權限檢查分工**：`canRead`/`canWrite` 檢查放在 **Controller** 層（GET 詳情、GET 成員、POST 成員、DELETE 成員、PUT 換負責人），因為這些方法內部（`addMember`/`removeMember`/`changeOwner`）也被其他 service 方法內部呼叫（例如建立專案時呼叫 `addMember` 讓建立者自己成為成員），若把權限檢查放進這些方法本身,會在「建立者尚未是成員」的時間點卡住自己。`canArchive` 檢查則放在 **Service**（`archiveProject`/`unarchiveProject` 內部），因為這兩個方法不會被其他 service 方法呼叫，沒有上述問題，且集中在 service 更貼近 CLAUDE.md「集中於 ProjectService」的精神
- `GlobalExceptionHandler` 已存在且**不需修改**：`EntityNotFoundException`→404、`IllegalArgumentException`→400、`SecurityException`→403。建立專案時使用者無部門的邊界情況用 `IllegalArgumentException`（不是 `IllegalStateException`——後者沒有對應的 handler,會變成無法攔截的 500）
- 所有 Controller 測試使用 `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional`（H2,`spring.session.store-type: none`),透過 `formLogin(...)` 取得 `SESSION` cookie 後續請求帶上,比照現有 `AuthFlowTest`/`UserControllerTest`
- `mvn test` 必須全數通過才能進入下一個 Task 的 commit

---

### Task 1: Repository 查詢方法擴充（Project / ProjectMember / WbsNode / User）

**Files:**
- Modify: `src/main/java/com/wbsflow/project/ProjectRepository.java`
- Modify: `src/main/java/com/wbsflow/project/ProjectMemberRepository.java`
- Modify: `src/main/java/com/wbsflow/wbs/WbsNodeRepository.java`
- Modify: `src/main/java/com/wbsflow/user/UserRepository.java`
- Test: `src/test/java/com/wbsflow/project/ProjectRepositoryTest.java`
- Test: `src/test/java/com/wbsflow/project/ProjectMemberRepositoryTest.java`
- Test: `src/test/java/com/wbsflow/wbs/WbsNodeRepositoryTest.java`

**Interfaces:**
- Consumes: 既有 `Project`、`ProjectMember`、`WbsNode`、`User` entity（子專案 A）
- Produces：
  - `ProjectRepository.findBySectionIdAndArchived(Long, boolean): List<Project>`
  - `ProjectRepository.findByArchived(boolean): List<Project>`
  - `ProjectRepository.findByMemberUserIdAndArchived(Long, boolean): List<Project>`
  - `ProjectMemberRepository.findByIdProjectId(Long): List<ProjectMember>`
  - `WbsNodeRepository.clearAssigneeForUserInProject(Long, Long): void`
  - `UserRepository.findByDepartmentId(Long): List<User>`

- [ ] **Step 1: 寫失敗測試**

在 `ProjectRepositoryTest.java` 現有類別內、`savesProjectWithSectionAndOwnerDefaultsNotArchived` 方法之後加入：

```java
    @Test
    void findsProjectsBySectionAndArchivedFlag() {
        Department sectionA = departmentRepository.save(newDept("系統科"));
        Department sectionB = departmentRepository.save(newDept("網路科"));
        User ownerA = userRepository.save(newUser("leaderA", User.Role.PROJECT_LEADER, sectionA));
        User ownerB = userRepository.save(newUser("leaderB", User.Role.PROJECT_LEADER, sectionB));

        projectRepository.save(newProject("專案A", sectionA, ownerA));
        Project archivedA = projectRepository.save(newProject("已封存A", sectionA, ownerA));
        archivedA.setArchived(true);
        projectRepository.save(archivedA);
        projectRepository.save(newProject("專案B", sectionB, ownerB));

        List<Project> activeInA = projectRepository.findBySectionIdAndArchived(sectionA.getId(), false);
        List<Project> allActive = projectRepository.findByArchived(false);

        assertThat(activeInA).extracting(Project::getName).containsExactly("專案A");
        assertThat(allActive).extracting(Project::getName).containsExactlyInAnyOrder("專案A", "專案B");
    }

    @Test
    void findsProjectsByMemberUserId() {
        Department section = departmentRepository.save(newDept("系統科"));
        User owner = userRepository.save(newUser("leader", User.Role.PROJECT_LEADER, section));
        User member = userRepository.save(newUser("member", User.Role.PROJECT_MEMBER, section));
        Project project = projectRepository.save(newProject("專案A", section, owner));
        addMember(project, member);
        projectRepository.save(newProject("無關專案", section, owner));

        List<Project> result = projectRepository.findByMemberUserIdAndArchived(member.getId(), false);

        assertThat(result).extracting(Project::getName).containsExactly("專案A");
    }

    private Project newProject(String name, Department section, User owner) {
        Project p = new Project();
        p.setName(name);
        p.setSection(section);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        return p;
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

    private void addMember(Project project, User user) {
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), user.getId()));
        pm.setAssignedBy(user);
        projectMemberRepository.save(pm);
    }
```

同時在 class 頂端新增 `@Autowired private ProjectMemberRepository projectMemberRepository;` 欄位，並在檔案 import 區塊加入：
```java
import java.util.List;
```

在 `ProjectMemberRepositoryTest.java` 的 `savesMembershipAndChecksExistence` 方法之後加入：

```java
    @Test
    void findsAllMembersOfProject() {
        Department section = departmentRepository.save(newSection());
        User owner = userRepository.save(newUser("leader", User.Role.PROJECT_LEADER, section));
        User member = userRepository.save(newUser("member", User.Role.PROJECT_MEMBER, section));

        Project project = new Project();
        project.setName("測試專案");
        project.setSection(section);
        project.setOwner(owner);
        project.setCreatedBy(owner);
        project = projectRepository.save(project);

        ProjectMember pmOwner = new ProjectMember();
        pmOwner.setId(new ProjectMemberId(project.getId(), owner.getId()));
        pmOwner.setAssignedBy(owner);
        projectMemberRepository.save(pmOwner);

        ProjectMember pmMember = new ProjectMember();
        pmMember.setId(new ProjectMemberId(project.getId(), member.getId()));
        pmMember.setAssignedBy(owner);
        projectMemberRepository.save(pmMember);

        List<ProjectMember> members = projectMemberRepository.findByIdProjectId(project.getId());

        assertThat(members).hasSize(2);
    }
```

並在 import 區塊加入：
```java
import java.util.List;
```

在 `WbsNodeRepositoryTest.java` 的 `savesL3NodeWithAssigneeStatusPriorityAndDates` 方法之後加入：

```java
    @Test
    void clearsAssigneeForUserInProjectOnly() {
        Project project = newProject();
        User member = userRepository.save(newUser("member", User.Role.PROJECT_MEMBER, project.getSection()));

        WbsNode l1 = new WbsNode();
        l1.setProject(project);
        l1.setLevel((short) 1);
        l1.setTitle("SIT");
        WbsNode savedL1 = wbsNodeRepository.save(l1);

        WbsNode l3 = new WbsNode();
        l3.setProject(project);
        l3.setParent(savedL1);
        l3.setLevel((short) 3);
        l3.setTitle("細項");
        l3.setAssignee(member);
        WbsNode savedL3 = wbsNodeRepository.save(l3);

        wbsNodeRepository.clearAssigneeForUserInProject(project.getId(), member.getId());

        assertThat(wbsNodeRepository.findById(savedL3.getId()).orElseThrow().getAssignee()).isNull();
    }
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectRepositoryTest,ProjectMemberRepositoryTest,WbsNodeRepositoryTest`
Expected: FAIL（編譯錯誤，新方法尚不存在）

- [ ] **Step 3: 寫最小實作**

`src/main/java/com/wbsflow/project/ProjectRepository.java`：

```java
package com.wbsflow.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findBySectionIdAndArchived(Long sectionId, boolean archived);

    List<Project> findByArchived(boolean archived);

    @Query("SELECT p FROM Project p JOIN ProjectMember pm ON pm.id.projectId = p.id "
        + "WHERE pm.id.userId = :userId AND p.archived = :archived")
    List<Project> findByMemberUserIdAndArchived(@Param("userId") Long userId, @Param("archived") boolean archived);
}
```

`src/main/java/com/wbsflow/project/ProjectMemberRepository.java`：

```java
package com.wbsflow.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {
    boolean existsByIdProjectIdAndIdUserId(Long projectId, Long userId);

    List<ProjectMember> findByIdProjectId(Long projectId);
}
```

`src/main/java/com/wbsflow/wbs/WbsNodeRepository.java`：

```java
package com.wbsflow.wbs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WbsNodeRepository extends JpaRepository<WbsNode, Long> {
    List<WbsNode> findByParentId(Long parentId);

    // 移除專案成員時連動清除其指派；clearAutomatically 避免呼叫端讀到 stale 的一級快取
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WbsNode n SET n.assignee = null WHERE n.project.id = :projectId AND n.assignee.id = :userId")
    void clearAssigneeForUserInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
```

`src/main/java/com/wbsflow/user/UserRepository.java`：

```java
package com.wbsflow.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    List<User> findByDepartmentId(Long departmentId);
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectRepositoryTest,ProjectMemberRepositoryTest,WbsNodeRepositoryTest`
Expected: PASS（全部測試,含新增的 4 個）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/project/ProjectRepository.java \
        src/main/java/com/wbsflow/project/ProjectMemberRepository.java \
        src/main/java/com/wbsflow/wbs/WbsNodeRepository.java \
        src/main/java/com/wbsflow/user/UserRepository.java \
        src/test/java/com/wbsflow/project/ProjectRepositoryTest.java \
        src/test/java/com/wbsflow/project/ProjectMemberRepositoryTest.java \
        src/test/java/com/wbsflow/wbs/WbsNodeRepositoryTest.java
git commit -m "feat: 新增專案/成員/節點/使用者查詢方法"
```

---

### Task 2: ProjectService — 封存權限與建立/列表邏輯

**Files:**
- Modify: `src/main/java/com/wbsflow/project/ProjectService.java`
- Test: `src/test/java/com/wbsflow/project/ProjectServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ProjectRepository.findBySectionIdAndArchived/findByArchived/findByMemberUserIdAndArchived`
- Produces:
  - `ProjectService.canArchive(Long, User): boolean`
  - `ProjectService.createProject(String, String, User): Project`
  - `ProjectService.listForUser(User, boolean): List<Project>`
  - `ProjectService.archiveProject(Long, User): void`
  - `ProjectService.unarchiveProject(Long, User): void`
  - `ProjectService.addMember(Long, Long, User): void`（內部共用，`createProject` 與 `changeOwner` 都會呼叫）
  - `ProjectService.removeMember(Long, Long): void`（連動清除節點指派，供 Task 4 controller 呼叫）
  - `ProjectService.changeOwner(Long, Long, User): void`（供 Task 4 controller 呼叫）

- [ ] **Step 1: 寫失敗測試**

在 `ProjectServiceTest.java` 現有類別內、`canReadThrowsNotFoundForNonExistentProject` 方法之後加入（需在檔案頂端 import 區塊加入 `import com.wbsflow.wbs.WbsNode;` 與 `import com.wbsflow.wbs.WbsNodeRepository;`，並在既有 `@Autowired` 欄位群組中新增 `@Autowired private WbsNodeRepository wbsNodeRepository;`）：

```java
    @Test
    void canArchiveMirrorsCanWriteRulesButIgnoresArchivedFlag() {
        assertThat(projectService.canArchive(projectA.getId(), director)).isFalse();
        assertThat(projectService.canArchive(projectA.getId(), chiefA)).isTrue();
        assertThat(projectService.canArchive(projectA.getId(), leaderA)).isTrue();
        assertThat(projectService.canArchive(projectA.getId(), memberA)).isTrue();
        assertThat(projectService.canArchive(projectA.getId(), outsiderA)).isFalse();
    }

    @Test
    void canArchiveStillTrueAfterProjectIsArchived() {
        projectA.setArchived(true);
        projectRepository.save(projectA);

        assertThat(projectService.canArchive(projectA.getId(), chiefA)).isTrue();
        assertThat(projectService.canArchive(projectA.getId(), leaderA)).isTrue();
    }

    @Test
    void createProjectAutoAssignsCreatorsDepartmentAsSectionAndAddsCreatorAsMember() {
        Project created = projectService.createProject("新專案", "描述", leaderA);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getSection().getId()).isEqualTo(sectionA.getId());
        assertThat(created.getOwner().getId()).isEqualTo(leaderA.getId());
        assertThat(created.getCreatedBy().getId()).isEqualTo(leaderA.getId());
        assertThat(created.isArchived()).isFalse();
        assertThat(projectService.isMember(created.getId(), leaderA.getId())).isTrue();
    }

    @Test
    void createProjectThrowsWhenCreatorHasNoDepartment() {
        User noDept = userRepository.save(newUser("floating", User.Role.PROJECT_MEMBER, null));

        assertThatThrownBy(() -> projectService.createProject("新專案", "描述", noDept))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listForUserReturnsRoleScopedResults() {
        Project projectB = new Project();
        projectB.setName("專案B");
        projectB.setSection(sectionB);
        User chiefB = userRepository.save(newUser("chiefBForList", User.Role.SECTION_CHIEF, sectionB));
        projectB.setOwner(chiefB);
        projectB.setCreatedBy(chiefB);
        projectRepository.save(projectB);

        assertThat(projectService.listForUser(director, false)).extracting(Project::getName)
            .containsExactlyInAnyOrder("專案A", "專案B");
        assertThat(projectService.listForUser(chiefA, false)).extracting(Project::getName)
            .containsExactly("專案A");
        assertThat(projectService.listForUser(leaderA, false)).extracting(Project::getName)
            .containsExactly("專案A");
        assertThat(projectService.listForUser(outsiderA, false)).isEmpty();
    }

    @Test
    void archiveProjectIsIdempotentAndUnarchiveRestoresWriteAccess() {
        projectService.archiveProject(projectA.getId(), chiefA);
        assertThat(projectRepository.findById(projectA.getId()).orElseThrow().isArchived()).isTrue();

        projectService.archiveProject(projectA.getId(), chiefA);
        assertThat(projectRepository.findById(projectA.getId()).orElseThrow().isArchived()).isTrue();

        projectService.unarchiveProject(projectA.getId(), chiefA);
        assertThat(projectRepository.findById(projectA.getId()).orElseThrow().isArchived()).isFalse();
        assertThat(projectService.canWrite(projectA.getId(), chiefA)).isTrue();
    }

    @Test
    void archiveProjectDeniedForNonAuthorizedCaller() {
        assertThatThrownBy(() -> projectService.archiveProject(projectA.getId(), outsiderA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void changeOwnerAutoAddsNewOwnerAsMemberWhenNotAlreadyOne() {
        projectService.changeOwner(projectA.getId(), outsiderA.getId(), leaderA);

        Project updated = projectRepository.findById(projectA.getId()).orElseThrow();
        assertThat(updated.getOwner().getId()).isEqualTo(outsiderA.getId());
        assertThat(projectService.isMember(projectA.getId(), outsiderA.getId())).isTrue();
    }

    @Test
    void removeMemberClearsAssigneeOnNodesWithinSameProjectOnly() {
        WbsNode l1 = new WbsNode();
        l1.setProject(projectA);
        l1.setLevel((short) 1);
        l1.setTitle("SIT");
        WbsNode savedL1 = wbsNodeRepository.save(l1);

        WbsNode l3 = new WbsNode();
        l3.setProject(projectA);
        l3.setParent(savedL1);
        l3.setLevel((short) 3);
        l3.setTitle("細項");
        l3.setAssignee(memberA);
        WbsNode savedL3 = wbsNodeRepository.save(l3);

        projectService.removeMember(projectA.getId(), memberA.getId());

        assertThat(projectMemberRepository.existsByIdProjectIdAndIdUserId(projectA.getId(), memberA.getId())).isFalse();
        assertThat(wbsNodeRepository.findById(savedL3.getId()).orElseThrow().getAssignee()).isNull();
    }
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectServiceTest`
Expected: FAIL（編譯錯誤，新方法尚不存在）

- [ ] **Step 3: 寫最小實作**

完整替換 `src/main/java/com/wbsflow/project/ProjectService.java`：

```java
package com.wbsflow.project;

import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import com.wbsflow.wbs.WbsNodeRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final WbsNodeRepository wbsNodeRepository;

    // 統一拋 EntityNotFoundException，controller 層交給 GlobalExceptionHandler 轉 404
    public Project getById(Long id) {
        return projectRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("專案不存在"));
    }

    public boolean isMember(Long projectId, Long userId) {
        if (projectId == null || userId == null) return false;
        return projectMemberRepository.existsByIdProjectIdAndIdUserId(projectId, userId);
    }

    // 讀取權限：DIRECTOR 跨科唯讀、SECTION_CHIEF 科內全權、PROJECT_LEADER/PROJECT_MEMBER 僅參與專案
    public boolean canRead(Long projectId, User user) {
        if (projectId == null || user == null || user.getRole() == null) return false;
        Project project = getById(projectId);
        return switch (user.getRole()) {
            case DIRECTOR -> true;
            case SECTION_CHIEF -> sameSection(project, user);
            case PROJECT_LEADER, PROJECT_MEMBER -> isMember(projectId, user.getId());
        };
    }

    // 寫入權限：封存專案全員唯讀；DIRECTOR 一律唯讀；其餘同讀取權限範圍
    public boolean canWrite(Long projectId, User user) {
        if (projectId == null || user == null || user.getRole() == null) return false;
        Project project = getById(projectId);
        if (project.isArchived()) return false;
        return switch (user.getRole()) {
            case DIRECTOR -> false;
            case SECTION_CHIEF -> sameSection(project, user);
            case PROJECT_LEADER, PROJECT_MEMBER -> isMember(projectId, user.getId());
        };
    }

    // 封存/還原專用權限：邏輯與 canWrite 相同但不看 archived 旗標，
    // 否則已封存的專案因 canWrite 對封存旗標永遠回 false，會變成沒有人能還原
    public boolean canArchive(Long projectId, User user) {
        if (projectId == null || user == null || user.getRole() == null) return false;
        Project project = getById(projectId);
        return switch (user.getRole()) {
            case DIRECTOR -> false;
            case SECTION_CHIEF -> sameSection(project, user);
            case PROJECT_LEADER, PROJECT_MEMBER -> isMember(projectId, user.getId());
        };
    }

    // 任何已登入使用者皆可建立；section 自動代入建立者部門，建立者自動成為 owner 與成員
    @Transactional
    public Project createProject(String name, String description, User creator) {
        if (creator.getDepartment() == null) {
            throw new IllegalArgumentException("使用者無所屬部門，無法建立專案");
        }
        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setSection(creator.getDepartment());
        project.setOwner(creator);
        project.setCreatedBy(creator);
        Project saved = projectRepository.save(project);
        addMember(saved.getId(), creator.getId(), creator);
        return saved;
    }

    // 依角色回傳可見範圍：DIRECTOR 全部、SECTION_CHIEF 同科、LEADER/MEMBER 僅參與
    @Transactional(readOnly = true)
    public List<Project> listForUser(User user, boolean archived) {
        return switch (user.getRole()) {
            case DIRECTOR -> projectRepository.findByArchived(archived);
            case SECTION_CHIEF -> projectRepository.findBySectionIdAndArchived(user.getDepartment().getId(), archived);
            case PROJECT_LEADER, PROJECT_MEMBER -> projectRepository.findByMemberUserIdAndArchived(user.getId(), archived);
        };
    }

    // 封存冪等：已封存的專案再封存一次不報錯
    @Transactional
    public void archiveProject(Long projectId, User caller) {
        if (!canArchive(projectId, caller)) {
            throw new SecurityException("無權限封存此專案");
        }
        Project project = getById(projectId);
        project.setArchived(true);
        projectRepository.save(project);
    }

    // 還原冪等：未封存的專案再還原一次不報錯
    @Transactional
    public void unarchiveProject(Long projectId, User caller) {
        if (!canArchive(projectId, caller)) {
            throw new SecurityException("無權限還原此專案");
        }
        Project project = getById(projectId);
        project.setArchived(false);
        projectRepository.save(project);
    }

    // 內部共用：冪等新增成員，不含權限檢查——呼叫端（controller 或 createProject/changeOwner）已確認過權限，
    // 若在此重複檢查 canWrite，會在「建立者尚未是成員」的時間點卡住建立流程本身
    @Transactional
    public void addMember(Long projectId, Long userId, User assignedBy) {
        if (projectMemberRepository.existsByIdProjectIdAndIdUserId(projectId, userId)) return;
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(projectId, user.getId()));
        pm.setAssignedBy(assignedBy);
        projectMemberRepository.save(pm);
    }

    // 移除成員時連動清除其在該專案下的節點指派（CLAUDE.md 核心規則，即使節點 CRUD 尚未實作也要保證）
    @Transactional
    public void removeMember(Long projectId, Long userId) {
        projectMemberRepository.deleteById(new ProjectMemberId(projectId, userId));
        wbsNodeRepository.clearAssigneeForUserInProject(projectId, userId);
    }

    // 換負責人：若新 owner 尚未是成員自動補加，確保新 owner 一定對自己的專案有 canWrite
    @Transactional
    public void changeOwner(Long projectId, Long newOwnerId, User actor) {
        Project project = getById(projectId);
        User newOwner = userRepository.findById(newOwnerId)
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        project.setOwner(newOwner);
        projectRepository.save(project);
        addMember(projectId, newOwnerId, actor);
    }

    private boolean sameSection(Project project, User user) {
        if (project.getSection() == null || user.getDepartment() == null) return false;
        return project.getSection().getId().equals(user.getDepartment().getId());
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectServiceTest`
Expected: PASS（全部測試，含新增的 9 個）；同時跑 `mvn test` 全套確認未影響其他子專案

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/project/ProjectService.java \
        src/test/java/com/wbsflow/project/ProjectServiceTest.java
git commit -m "feat: ProjectService 新增封存權限與建立/列表/成員管理邏輯"
```

---

### Task 3: ProjectDto + ProjectController — 專案 CRUD 端點

**Files:**
- Create: `src/main/java/com/wbsflow/project/ProjectDto.java`
- Create: `src/main/java/com/wbsflow/project/ProjectController.java`
- Test: `src/test/java/com/wbsflow/project/ProjectControllerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ProjectService.createProject/listForUser/archiveProject/unarchiveProject`、既有 `canRead`
- Produces:
  - `ProjectDto.Response(Long id, String name, String description, Long sectionId, String sectionName, Long ownerId, String ownerUsername, String ownerDisplayName, boolean archived, LocalDateTime createdAt)`
  - `ProjectDto.CreateRequest(String name, String description)`
  - `GET/POST /api/projects`、`GET /api/projects/{id}`、`PATCH /api/projects/{id}/archive`、`PATCH /api/projects/{id}/unarchive`

- [ ] **Step 1: 寫失敗測試**

建立 `src/test/java/com/wbsflow/project/ProjectControllerTest.java`：

```java
package com.wbsflow.project;

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
class ProjectControllerTest {

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
    private PasswordEncoder passwordEncoder;

    private Department sectionA;
    private Department sectionB;
    private User leaderA;
    private Project projectA;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        leaderA = saveUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
        saveUser("chiefA", User.Role.SECTION_CHIEF, sectionA);
        saveUser("chiefB", User.Role.SECTION_CHIEF, sectionB);

        Project p = new Project();
        p.setName("既有專案");
        p.setSection(sectionA);
        p.setOwner(leaderA);
        p.setCreatedBy(leaderA);
        projectA = projectRepository.save(p);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(projectA.getId(), leaderA.getId()));
        pm.setAssignedBy(leaderA);
        projectMemberRepository.save(pm);
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

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void createProjectAutoAssignsSectionAndOwner() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/projects").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新專案\",\"description\":\"說明\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("新專案"))
            .andExpect(jsonPath("$.data.sectionId").value(sectionA.getId()))
            .andExpect(jsonPath("$.data.ownerUsername").value("leaderA"));
    }

    @Test
    void listReturnsOnlySameSectionProjectsForSectionChief() throws Exception {
        Cookie session = loginAs("chiefA");

        mockMvc.perform(get("/api/projects").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("既有專案"));
    }

    @Test
    void listReturnsEmptyForChiefFromOtherSection() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(get("/api/projects").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getProjectDeniedForNonMemberOutsideSection() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(get("/api/projects/" + projectA.getId()).cookie(session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void archiveThenUnarchiveBySectionChiefIsIdempotent() throws Exception {
        Cookie session = loginAs("chiefA");

        mockMvc.perform(patch("/api/projects/" + projectA.getId() + "/archive").cookie(session).with(csrf()))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/projects/" + projectA.getId() + "/archive").cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(projectRepository.findById(projectA.getId()).orElseThrow().isArchived()).isTrue();

        mockMvc.perform(patch("/api/projects/" + projectA.getId() + "/unarchive").cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(projectRepository.findById(projectA.getId()).orElseThrow().isArchived()).isFalse();
    }

    @Test
    void directorCannotArchive() throws Exception {
        saveUser("director1", User.Role.DIRECTOR, sectionA);
        Cookie session = loginAs("director1");

        mockMvc.perform(patch("/api/projects/" + projectA.getId() + "/archive").cookie(session).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectControllerTest`
Expected: FAIL（`ProjectDto`/`ProjectController` 不存在）

- [ ] **Step 3: 寫最小實作**

`src/main/java/com/wbsflow/project/ProjectDto.java`：

```java
package com.wbsflow.project;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public class ProjectDto {

    public record Response(Long id, String name, String description,
                            Long sectionId, String sectionName,
                            Long ownerId, String ownerUsername, String ownerDisplayName,
                            boolean archived, LocalDateTime createdAt) {
        public static Response from(Project p) {
            return new Response(
                p.getId(), p.getName(), p.getDescription(),
                p.getSection().getId(), p.getSection().getName(),
                p.getOwner().getId(), p.getOwner().getUsername(), p.getOwner().getDisplayName(),
                p.isArchived(), p.getCreatedAt()
            );
        }
    }

    public record CreateRequest(@NotBlank String name, String description) {
    }
}
```

`src/main/java/com/wbsflow/project/ProjectController.java`：

```java
package com.wbsflow.project;

import com.wbsflow.common.ApiResponse;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final UserRepository userRepository;

    @GetMapping("/projects")
    public String projectsPage() {
        return "project/list";
    }

    @GetMapping("/api/projects")
    @ResponseBody
    public ApiResponse<List<ProjectDto.Response>> list(
            @RequestParam(defaultValue = "false") boolean archived, Principal principal) {
        User user = currentUser(principal);
        List<ProjectDto.Response> result = projectService.listForUser(user, archived)
            .stream().map(ProjectDto.Response::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/projects")
    @ResponseBody
    public ApiResponse<ProjectDto.Response> create(
            @Valid @RequestBody ProjectDto.CreateRequest req, Principal principal) {
        User user = currentUser(principal);
        Project project = projectService.createProject(req.name(), req.description(), user);
        return ApiResponse.ok(ProjectDto.Response.from(project));
    }

    @GetMapping("/api/projects/{id}")
    @ResponseBody
    public ApiResponse<ProjectDto.Response> get(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            throw new SecurityException("無存取權限");
        }
        return ApiResponse.ok(ProjectDto.Response.from(projectService.getById(id)));
    }

    @PatchMapping("/api/projects/{id}/archive")
    @ResponseBody
    public ApiResponse<Void> archive(@PathVariable Long id, Principal principal) {
        projectService.archiveProject(id, currentUser(principal));
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{id}/unarchive")
    @ResponseBody
    public ApiResponse<Void> unarchive(@PathVariable Long id, Principal principal) {
        projectService.unarchiveProject(id, currentUser(principal));
        return ApiResponse.ok(null);
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectControllerTest`
Expected: PASS（6 個測試）；同時跑 `mvn test` 全套確認未影響先前子專案

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/project/ProjectDto.java \
        src/main/java/com/wbsflow/project/ProjectController.java \
        src/test/java/com/wbsflow/project/ProjectControllerTest.java
git commit -m "feat: 新增專案 CRUD REST 端點（list/create/get/archive/unarchive）"
```

---

### Task 4: ProjectController — 成員管理與換負責人端點

**Files:**
- Modify: `src/main/java/com/wbsflow/project/ProjectDto.java`
- Modify: `src/main/java/com/wbsflow/project/ProjectController.java`
- Modify: `src/test/java/com/wbsflow/project/ProjectControllerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ProjectService.addMember/removeMember/changeOwner`、既有 `canWrite`、Task 1 的 `ProjectMemberRepository.findByIdProjectId`
- Produces:
  - `ProjectDto.MemberResponse(Long userId, String username, String displayName, String role, LocalDateTime joinedAt)`
  - `ProjectDto.MemberRequest(Long userId)`
  - `GET/POST /api/projects/{id}/members`、`DELETE /api/projects/{id}/members/{userId}`、`PUT /api/projects/{id}/owner`

- [ ] **Step 1: 寫失敗測試**

在 `ProjectControllerTest.java` 的 `directorCannotArchive` 方法之後加入（需要 import `com.wbsflow.wbs.WbsNode` 與 `com.wbsflow.wbs.WbsNodeRepository`，並新增一個 `@Autowired private WbsNodeRepository wbsNodeRepository;` 欄位）：

```java
    @Test
    void addMemberBySectionChiefIsIdempotent() throws Exception {
        User memberX = saveUser("memberX", User.Role.PROJECT_MEMBER, sectionA);
        Cookie session = loginAs("chiefA");

        mockMvc.perform(post("/api/projects/" + projectA.getId() + "/members").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + memberX.getId() + "}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + projectA.getId() + "/members").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + memberX.getId() + "}"))
            .andExpect(status().isOk());

        assertThat(projectMemberRepository.existsByIdProjectIdAndIdUserId(projectA.getId(), memberX.getId())).isTrue();
    }

    @Test
    void getMembersListsExistingMembers() throws Exception {
        Cookie session = loginAs("chiefA");

        mockMvc.perform(get("/api/projects/" + projectA.getId() + "/members").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].username").value("leaderA"));
    }

    @Test
    void removeMemberClearsNodeAssignee() throws Exception {
        User memberX = saveUser("memberX", User.Role.PROJECT_MEMBER, sectionA);
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(projectA.getId(), memberX.getId()));
        pm.setAssignedBy(leaderA);
        projectMemberRepository.save(pm);

        WbsNode l1 = new WbsNode();
        l1.setProject(projectA);
        l1.setLevel((short) 1);
        l1.setTitle("SIT");
        WbsNode savedL1 = wbsNodeRepository.save(l1);
        WbsNode l3 = new WbsNode();
        l3.setProject(projectA);
        l3.setParent(savedL1);
        l3.setLevel((short) 3);
        l3.setTitle("細項");
        l3.setAssignee(memberX);
        WbsNode savedL3 = wbsNodeRepository.save(l3);

        Cookie session = loginAs("chiefA");
        mockMvc.perform(delete("/api/projects/" + projectA.getId() + "/members/" + memberX.getId())
                .cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(savedL3.getId()).orElseThrow().getAssignee()).isNull();
    }

    @Test
    void changeOwnerAutoAddsNewOwnerAsMember() throws Exception {
        User memberX = saveUser("memberX", User.Role.PROJECT_MEMBER, sectionA);
        Cookie session = loginAs("chiefA");

        mockMvc.perform(put("/api/projects/" + projectA.getId() + "/owner").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + memberX.getId() + "}"))
            .andExpect(status().isOk());

        Project updated = projectRepository.findById(projectA.getId()).orElseThrow();
        assertThat(updated.getOwner().getUsername()).isEqualTo("memberX");
        assertThat(projectMemberRepository.existsByIdProjectIdAndIdUserId(projectA.getId(), memberX.getId())).isTrue();
    }

    @Test
    void memberManagementDeniedForOutsideSectionChief() throws Exception {
        User memberX = saveUser("memberX", User.Role.PROJECT_MEMBER, sectionA);
        Cookie session = loginAs("chiefB");

        mockMvc.perform(post("/api/projects/" + projectA.getId() + "/members").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + memberX.getId() + "}"))
            .andExpect(status().isForbidden());
    }
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectControllerTest`
Expected: FAIL（`ProjectDto.MemberResponse`/`MemberRequest` 與對應端點尚不存在）

- [ ] **Step 3: 寫最小實作**

在 `ProjectDto.java` 的 `CreateRequest` record 之後加入：

```java
    public record MemberResponse(Long userId, String username, String displayName,
                                  String role, LocalDateTime joinedAt) {
        public static MemberResponse from(ProjectMember pm) {
            return new MemberResponse(
                pm.getUser().getId(), pm.getUser().getUsername(), pm.getUser().getDisplayName(),
                pm.getUser().getRole().name(), pm.getJoinedAt()
            );
        }
    }

    public record MemberRequest(Long userId) {
    }
```

在 `ProjectController.java` 中：
1. 建構子注入新增 `ProjectMemberRepository projectMemberRepository`（於 `RequiredArgsConstructor` 生成，只需新增欄位）；
2. import 區塊加入 `import java.util.List;`（若尚未加入，Task 3 已加）；
3. 在 `unarchive` 方法之後、`currentUser` 私有方法之前，加入：

```java
    @GetMapping("/api/projects/{id}/members")
    @ResponseBody
    public ApiResponse<List<ProjectDto.MemberResponse>> members(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            throw new SecurityException("無存取權限");
        }
        List<ProjectDto.MemberResponse> result = projectMemberRepository.findByIdProjectId(id)
            .stream().map(ProjectDto.MemberResponse::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/projects/{id}/members")
    @ResponseBody
    public ApiResponse<Void> addMember(@PathVariable Long id,
            @RequestBody ProjectDto.MemberRequest req, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限管理此專案成員");
        }
        projectService.addMember(id, req.userId(), user);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/projects/{id}/members/{userId}")
    @ResponseBody
    public ApiResponse<Void> removeMember(@PathVariable Long id, @PathVariable Long userId, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限管理此專案成員");
        }
        projectService.removeMember(id, userId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/projects/{id}/owner")
    @ResponseBody
    public ApiResponse<Void> changeOwner(@PathVariable Long id,
            @RequestBody ProjectDto.MemberRequest req, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限變更負責人");
        }
        projectService.changeOwner(id, req.userId(), user);
        return ApiResponse.ok(null);
    }
```

完整最終 `ProjectController.java`（供對照，避免遺漏欄位或 import）：

```java
package com.wbsflow.project;

import com.wbsflow.common.ApiResponse;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;

    @GetMapping("/projects")
    public String projectsPage() {
        return "project/list";
    }

    @GetMapping("/api/projects")
    @ResponseBody
    public ApiResponse<List<ProjectDto.Response>> list(
            @RequestParam(defaultValue = "false") boolean archived, Principal principal) {
        User user = currentUser(principal);
        List<ProjectDto.Response> result = projectService.listForUser(user, archived)
            .stream().map(ProjectDto.Response::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/projects")
    @ResponseBody
    public ApiResponse<ProjectDto.Response> create(
            @Valid @RequestBody ProjectDto.CreateRequest req, Principal principal) {
        User user = currentUser(principal);
        Project project = projectService.createProject(req.name(), req.description(), user);
        return ApiResponse.ok(ProjectDto.Response.from(project));
    }

    @GetMapping("/api/projects/{id}")
    @ResponseBody
    public ApiResponse<ProjectDto.Response> get(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            throw new SecurityException("無存取權限");
        }
        return ApiResponse.ok(ProjectDto.Response.from(projectService.getById(id)));
    }

    @PatchMapping("/api/projects/{id}/archive")
    @ResponseBody
    public ApiResponse<Void> archive(@PathVariable Long id, Principal principal) {
        projectService.archiveProject(id, currentUser(principal));
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{id}/unarchive")
    @ResponseBody
    public ApiResponse<Void> unarchive(@PathVariable Long id, Principal principal) {
        projectService.unarchiveProject(id, currentUser(principal));
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/projects/{id}/members")
    @ResponseBody
    public ApiResponse<List<ProjectDto.MemberResponse>> members(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            throw new SecurityException("無存取權限");
        }
        List<ProjectDto.MemberResponse> result = projectMemberRepository.findByIdProjectId(id)
            .stream().map(ProjectDto.MemberResponse::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/projects/{id}/members")
    @ResponseBody
    public ApiResponse<Void> addMember(@PathVariable Long id,
            @RequestBody ProjectDto.MemberRequest req, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限管理此專案成員");
        }
        projectService.addMember(id, req.userId(), user);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/projects/{id}/members/{userId}")
    @ResponseBody
    public ApiResponse<Void> removeMember(@PathVariable Long id, @PathVariable Long userId, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限管理此專案成員");
        }
        projectService.removeMember(id, userId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/projects/{id}/owner")
    @ResponseBody
    public ApiResponse<Void> changeOwner(@PathVariable Long id,
            @RequestBody ProjectDto.MemberRequest req, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canWrite(id, user)) {
            throw new SecurityException("無權限變更負責人");
        }
        projectService.changeOwner(id, req.userId(), user);
        return ApiResponse.ok(null);
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectControllerTest`
Expected: PASS（11 個測試）；同時跑 `mvn test` 全套確認全綠

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/project/ProjectDto.java \
        src/main/java/com/wbsflow/project/ProjectController.java \
        src/test/java/com/wbsflow/project/ProjectControllerTest.java
git commit -m "feat: 新增專案成員管理與換負責人端點"
```

---

### Task 5: UserController — GET /api/users 與部門篩選

**Files:**
- Modify: `src/main/java/com/wbsflow/user/UserController.java`
- Modify: `src/test/java/com/wbsflow/user/UserControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的 `UserRepository.findByDepartmentId`
- Produces: `GET /api/users?departmentId=` → `ApiResponse<List<UserController.UserSummary>>`；`UserSummary` 取代原本的 `UserMeResponse`（欄位形狀相同：`id, username, displayName, role`），`/api/users/me` 也改回傳此型別

- [ ] **Step 1: 寫失敗測試**

完整替換 `src/test/java/com/wbsflow/user/UserControllerTest.java`：

```java
package com.wbsflow.user;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department sectionA;
    private Department sectionB;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        saveUser("leader", "負責人", User.Role.PROJECT_LEADER, sectionA);
        saveUser("chief", "科長", User.Role.SECTION_CHIEF, sectionA);
        saveUser("memberB", "另科成員", User.Role.PROJECT_MEMBER, sectionB);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private void saveUser(String username, String displayName, User.Role role, Department dept) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setDepartment(dept);
        userRepository.save(user);
    }

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void returnsAuthenticatedUserProfile() throws Exception {
        Cookie session = loginAs("leader");

        mockMvc.perform(get("/api/users/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.username").value("leader"))
            .andExpect(jsonPath("$.data.displayName").value("負責人"))
            .andExpect(jsonPath("$.data.role").value("PROJECT_LEADER"));
    }

    @Test
    void unauthenticatedRequestReturnsUnauthorizedJson() throws Exception {
        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void listReturnsAllUsersWithoutFilter() throws Exception {
        Cookie session = loginAs("chief");

        mockMvc.perform(get("/api/users").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void listFiltersByDepartmentId() throws Exception {
        Cookie session = loginAs("chief");

        mockMvc.perform(get("/api/users").param("departmentId", String.valueOf(sectionA.getId())).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[*].username", containsInAnyOrder("leader", "chief")));
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=UserControllerTest`
Expected: FAIL（`GET /api/users` 端點不存在，404）

- [ ] **Step 3: 寫最小實作**

完整替換 `src/main/java/com/wbsflow/user/UserController.java`：

```java
package com.wbsflow.user;

import com.wbsflow.common.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/api/users/me")
    public ApiResponse<UserSummary> me(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        return ApiResponse.ok(UserSummary.from(user));
    }

    // 供成員新增等下拉選單使用；帶 departmentId 只回同科人員，不帶則回全部
    @GetMapping("/api/users")
    public ApiResponse<List<UserSummary>> list(@RequestParam(required = false) Long departmentId) {
        List<User> users = departmentId != null
            ? userRepository.findByDepartmentId(departmentId)
            : userRepository.findAll();
        return ApiResponse.ok(users.stream().map(UserSummary::from).toList());
    }

    public record UserSummary(Long id, String username, String displayName, String role) {
        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name());
        }
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=UserControllerTest`
Expected: PASS（4 個測試）；同時跑 `mvn test` 全套確認全綠

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/user/UserController.java \
        src/test/java/com/wbsflow/user/UserControllerTest.java
git commit -m "feat: 新增 GET /api/users 與部門篩選"
```

---

### Task 6: Thymeleaf /projects 頁殼與 sidebar 連結

**Files:**
- Create: `src/main/resources/templates/project/list.html`
- Modify: `src/main/resources/templates/fragments/sidebar.html`
- Test: `src/test/java/com/wbsflow/project/ProjectPageTest.java`

**Interfaces:**
- Consumes: Task 3 的 `GET /projects`（`ProjectController.projectsPage()`）、`GET /api/projects`
- Produces: 登入後可從側邊欄點「專案列表」進入的靜態頁面

- [ ] **Step 1: 寫失敗測試**

建立 `src/test/java/com/wbsflow/project/ProjectPageTest.java`：

```java
package com.wbsflow.project;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectPageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Department section = new Department();
        section.setName("系統科");
        section = departmentRepository.save(section);

        User user = new User();
        user.setUsername("leader");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setDisplayName("負責人");
        user.setRole(User.Role.PROJECT_LEADER);
        user.setDepartment(section);
        userRepository.save(user);
    }

    @Test
    void projectsPageRendersAfterLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin("/auth/login").user("leader").password("password123"))
            .andExpect(authenticated())
            .andReturn();
        Cookie session = loginResult.getResponse().getCookie("SESSION");

        mockMvc.perform(get("/projects").cookie(session))
            .andExpect(status().isOk())
            .andExpect(view().name("project/list"));
    }

    @Test
    void unauthenticatedAccessRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/projects"))
            .andExpect(status().is3xxRedirection());
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectPageTest`
Expected: FAIL（樣板 `project/list` 不存在，`TemplateInputException`）

- [ ] **Step 3: 寫最小實作**

建立 `src/main/resources/templates/project/list.html`：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <title>專案列表 - WBS 管理系統</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<div th:replace="~{fragments/header :: header}"></div>
<div class="layout">
  <div th:replace="~{fragments/sidebar :: sidebar}"></div>
  <main class="main-content">
    <div class="page-header">
      <h1>專案列表</h1>
    </div>
    <div id="project-grid" class="project-grid"></div>
  </main>
</div>
<div th:replace="~{fragments/footer :: footer}"></div>
<script>
  // 純伺服器渲染頁的最小互動：抓取後用 DOM API 逐一組卡片，避免 innerHTML 拼接使用者輸入內容導致 XSS
  fetch('/api/projects')
    .then(function (res) { return res.json(); })
    .then(function (body) {
      var grid = document.getElementById('project-grid');
      if (!body.success || body.data.length === 0) {
        grid.textContent = '目前沒有專案';
        return;
      }
      body.data.forEach(function (project) {
        var card = document.createElement('div');
        card.className = 'project-card';

        var name = document.createElement('div');
        name.className = 'project-card-name';
        name.textContent = project.name;

        var meta = document.createElement('div');
        meta.className = 'project-card-meta';
        meta.textContent = project.sectionName + ' · 負責人：' + project.ownerDisplayName;

        card.appendChild(name);
        card.appendChild(meta);
        grid.appendChild(card);
      });
    });
</script>
</body>
</html>
```

完整替換 `src/main/resources/templates/fragments/sidebar.html`：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<nav th:fragment="sidebar" class="sidebar">
  <ul>
    <li><a th:href="@{/home}">首頁</a></li>
    <li><a th:href="@{/projects}">專案列表</a></li>
  </ul>
</nav>
</body>
</html>
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectPageTest`
Expected: PASS（2 個測試）；同時跑 `mvn test` 全套確認全專案（子專案 A/B/C 至此累積的所有測試）皆綠燈

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/project/list.html \
        src/main/resources/templates/fragments/sidebar.html \
        src/test/java/com/wbsflow/project/ProjectPageTest.java
git commit -m "feat: 新增專案列表頁殼與 sidebar 連結"
```

---

## 全部完成後

- [ ] 執行 `mvn test` 確認全專案測試皆綠燈
- [ ] 實際啟動應用程式（`docker compose up -d` + `mvn spring-boot:run`），用瀏覽器依序驗證：
  - `chief` 登入 → `/projects` 看到既有專案卡片 → 建立新專案 → 卡片即時可見（重新整理後）
  - `chief` 封存專案 → 再次封存不報錯（冪等）→ 解封存恢復
  - `leader` 換一個同科成員為新 owner → 新 owner 登入後對該專案可寫
  - 移除一個已指派節點的成員後（可用 `docker compose exec db psql` 手動塞一筆 `wbs_nodes` 測資）→ 確認其 `assignee_id` 被清空
  - 用別科帳號嘗試存取不屬於自己的專案 → 確認被拒絕（403 JSON）
- [ ] 回報使用者：本子專案完成，等待核准後進入節點子專案（WBS 樹編輯與 L1 骨架初始化）
