# 子專案 A：資料層與權限核心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 Spring-WbsFlow 六張核心表對應的 JPA Entity/Repository、統一 API 信封與例外處理、以及 `ProjectService.canRead/canWrite` 權限核心（四角色矩陣，一律先 null 防禦），作為後續所有功能（認證、專案管理、WBS 樹編輯、看板、派工、甘特）的地基。

**Architecture:** Package-by-domain（`department`/`user`/`project`/`wbs`/`common`），每個領域內部平放 Entity/Repository/Service，不再往下分子資料夾，沿用 Spring-TaskFlow / Spring-WbsScaff 已驗證慣例。Entity 對應 `sql/01_ddl.sql` 手寫 schema（`ddl-auto: none`），測試環境用 H2（`ddl-auto: create-drop`，schema 由 Entity 自動產生，不含手寫 DB CHECK 約束——CHECK 約束已在骨架階段以實際 Postgres 容器驗證過，此處測試聚焦 Entity mapping 與 service 權限邏輯）。

**Tech Stack:** Java 21、Spring Boot 3.4.0、Spring Data JPA（Hibernate 6）、Lombok、JUnit 5 + AssertJ（`spring-boot-starter-test` 已含）、H2（test scope，`MODE=PostgreSQL`）。

## Global Constraints

- 根 package：`com.wbsflow`；領域子 package：`department`、`user`、`project`、`wbs`、`common`
- Entity 用 Lombok `@Getter @Setter`（不用 `@Data`，避免 JPA 代理物件的 equals/hashCode 問題），欄位命名與 `sql/01_ddl.sql` 完全對應，`@Column(name = "...")` 明確指定 snake_case 欄名
- 所有 Entity 的 `@Id` 皆為 `@GeneratedValue(strategy = GenerationType.IDENTITY)`（對應 `bigserial`）
- 時間戳記用 `org.hibernate.annotations.CreationTimestamp` / `UpdateTimestamp`，型別 `LocalDateTime`
- 測試一律 `@ActiveProfiles("test")`，Repository 測試用 `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)`（強制使用 `application-test.yml` 既有的 H2 設定，不讓 Spring Boot 另外套用內建的自動替換資料源）
- Service 測試用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)` + `@ActiveProfiles("test")` + `@Transactional`（每個測試方法結束自動 rollback，測試間不互相汙染）
- 權限核心方法簽章固定為 `ProjectService.canRead(Long projectId, User user)` / `canWrite(Long projectId, User user)`，回傳 `boolean`，**一律先 null 防禦**（`projectId`/`user`/`user.getRole()` 為 null 時直接回傳 `false`，不拋例外；`projectId` 非 null 但查無專案時透過 `getById` 拋 `EntityNotFoundException`，交由 `GlobalExceptionHandler` 轉 404）
- `mvn test` 必須全數通過才能進入下一個 Task 的 commit

---

### Task 1: Department Entity + Repository

**Files:**
- Create: `src/main/java/com/wbsflow/department/Department.java`
- Create: `src/main/java/com/wbsflow/department/DepartmentRepository.java`
- Test: `src/test/java/com/wbsflow/department/DepartmentRepositoryTest.java`

**Interfaces:**
- Produces: `Department`（欄位：`id: Long`、`name: String`、`parent: Department`、`createdAt: LocalDateTime`）、`DepartmentRepository.findByParentId(Long parentId): List<Department>`

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.department;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DepartmentRepositoryTest {

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesAndFindsChildDepartmentByParent() {
        Department parent = new Department();
        parent.setName("資訊部");
        parent = departmentRepository.save(parent);

        Department child = new Department();
        child.setName("系統科");
        child.setParent(parent);
        departmentRepository.save(child);

        List<Department> children = departmentRepository.findByParentId(parent.getId());

        assertThat(children).hasSize(1);
        assertThat(children.get(0).getName()).isEqualTo("系統科");
    }

    @Test
    void topLevelDepartmentHasNullParent() {
        Department dept = new Department();
        dept.setName("資訊部");
        Department saved = departmentRepository.save(dept);

        assertThat(saved.getParent()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=DepartmentRepositoryTest`
Expected: FAIL（編譯錯誤，`Department`/`DepartmentRepository` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.department;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "departments")
@Getter
@Setter
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    // parent == null 代表「部」，parent != null 代表「科」（屬於某部）
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Department parent;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

```java
package com.wbsflow.department;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
    List<Department> findByParentId(Long parentId);
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=DepartmentRepositoryTest`
Expected: PASS（2 個測試）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/department src/test/java/com/wbsflow/department
git commit -m "feat: 新增 Department entity 與 repository"
```

---

### Task 2: User Entity + Repository

**Files:**
- Create: `src/main/java/com/wbsflow/user/User.java`
- Create: `src/main/java/com/wbsflow/user/UserRepository.java`
- Test: `src/test/java/com/wbsflow/user/UserRepositoryTest.java`

**Interfaces:**
- Consumes: `Department`（Task 1）
- Produces: `User`（欄位：`id`、`username`、`password`、`displayName`、`role: User.Role`、`department: Department`、`createdAt`）、`User.Role` enum（`DIRECTOR`/`SECTION_CHIEF`/`PROJECT_LEADER`/`PROJECT_MEMBER`）、`UserRepository.findByUsername(String): Optional<User>`

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.user;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesUserAndFindsByUsername() {
        Department section = new Department();
        section.setName("系統科");
        section = departmentRepository.save(section);

        User u = new User();
        u.setUsername("chief");
        u.setPassword("$2b$10$hash");
        u.setDisplayName("科長");
        u.setRole(User.Role.SECTION_CHIEF);
        u.setDepartment(section);
        userRepository.save(u);

        Optional<User> found = userRepository.findByUsername("chief");

        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo(User.Role.SECTION_CHIEF);
        assertThat(found.get().getDepartment().getName()).isEqualTo("系統科");
    }

    @Test
    void findByUsernameReturnsEmptyWhenNotFound() {
        assertThat(userRepository.findByUsername("nobody")).isEmpty();
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=UserRepositoryTest`
Expected: FAIL（`User`/`UserRepository` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.user;

import com.wbsflow.department.Department;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum Role {
        DIRECTOR,        // 主任：跨科唯讀
        SECTION_CHIEF,   // 科長：科內全權
        PROJECT_LEADER,  // 專案負責人：自有專案
        PROJECT_MEMBER   // 專案成員：僅參與專案
    }
}
```

```java
package com.wbsflow.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=UserRepositoryTest`
Expected: PASS（2 個測試）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/user src/test/java/com/wbsflow/user
git commit -m "feat: 新增 User entity 與 repository（四角色）"
```

---

### Task 3: Project Entity + Repository

**Files:**
- Create: `src/main/java/com/wbsflow/project/Project.java`
- Create: `src/main/java/com/wbsflow/project/ProjectRepository.java`
- Test: `src/test/java/com/wbsflow/project/ProjectRepositoryTest.java`

**Interfaces:**
- Consumes: `Department`（Task 1）、`User`（Task 2）
- Produces: `Project`（欄位：`id`、`name`、`description`、`section: Department`、`owner: User`、`createdBy: User`、`archived: boolean`、`createdAt`、`updatedAt`）、`ProjectRepository extends JpaRepository<Project, Long>`

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.project;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void savesProjectWithSectionAndOwnerDefaultsNotArchived() {
        Department section = new Department();
        section.setName("系統科");
        section = departmentRepository.save(section);

        User owner = new User();
        owner.setUsername("leader");
        owner.setPassword("hash");
        owner.setDisplayName("負責人");
        owner.setRole(User.Role.PROJECT_LEADER);
        owner.setDepartment(section);
        owner = userRepository.save(owner);

        Project p = new Project();
        p.setName("測試專案");
        p.setDescription("描述文字");
        p.setSection(section);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        Project saved = projectRepository.save(p);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isArchived()).isFalse();
        assertThat(saved.getSection().getName()).isEqualTo("系統科");
        assertThat(saved.getOwner().getUsername()).isEqualTo("leader");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectRepositoryTest`
Expected: FAIL（`Project`/`ProjectRepository` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.project;

import com.wbsflow.department.Department;
import com.wbsflow.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Getter
@Setter
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false)
    private Department section;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false)
    private boolean archived = false;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

```java
package com.wbsflow.project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/project/Project.java src/main/java/com/wbsflow/project/ProjectRepository.java src/test/java/com/wbsflow/project/ProjectRepositoryTest.java
git commit -m "feat: 新增 Project entity 與 repository"
```

---

### Task 4: ProjectMember(Id) Entity + Repository

**Files:**
- Create: `src/main/java/com/wbsflow/project/ProjectMemberId.java`
- Create: `src/main/java/com/wbsflow/project/ProjectMember.java`
- Create: `src/main/java/com/wbsflow/project/ProjectMemberRepository.java`
- Test: `src/test/java/com/wbsflow/project/ProjectMemberRepositoryTest.java`

**Interfaces:**
- Consumes: `Project`（Task 3）、`User`（Task 2）、`Department`（Task 1）
- Produces: `ProjectMemberId`（複合鍵：`projectId`、`userId`）、`ProjectMember`（欄位：`id`、`project`、`user`、`assignedBy: User`、`joinedAt`）、`ProjectMemberRepository.existsByIdProjectIdAndIdUserId(Long, Long): boolean`

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.project;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProjectMemberRepositoryTest {

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesMembershipAndChecksExistence() {
        Department section = departmentRepository.save(newSection());
        User owner = userRepository.save(newUser("leader", User.Role.PROJECT_LEADER, section));
        User member = userRepository.save(newUser("member", User.Role.PROJECT_MEMBER, section));

        Project project = new Project();
        project.setName("測試專案");
        project.setSection(section);
        project.setOwner(owner);
        project.setCreatedBy(owner);
        project = projectRepository.save(project);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), member.getId()));
        pm.setAssignedBy(owner);
        projectMemberRepository.save(pm);

        assertThat(projectMemberRepository.existsByIdProjectIdAndIdUserId(project.getId(), member.getId())).isTrue();
        assertThat(projectMemberRepository.existsByIdProjectIdAndIdUserId(project.getId(), owner.getId())).isFalse();
    }

    private Department newSection() {
        Department d = new Department();
        d.setName("系統科");
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
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectMemberRepositoryTest`
Expected: FAIL（`ProjectMemberId`/`ProjectMember`/`ProjectMemberRepository` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.project;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ProjectMemberId implements Serializable {
    // 明確指定欄位名稱，避免 Hibernate 與 @JoinColumn 產生重複 mapping 衝突
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "user_id")
    private Long userId;
}
```

```java
package com.wbsflow.project;

import com.wbsflow.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_members")
@Getter
@Setter
public class ProjectMember {

    @EmbeddedId
    private ProjectMemberId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", insertable = false, updatable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by", nullable = false)
    private User assignedBy;

    @CreationTimestamp
    @Column(name = "joined_at")
    private LocalDateTime joinedAt;
}
```

```java
package com.wbsflow.project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {
    boolean existsByIdProjectIdAndIdUserId(Long projectId, Long userId);
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectMemberRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/project/ProjectMemberId.java src/main/java/com/wbsflow/project/ProjectMember.java src/main/java/com/wbsflow/project/ProjectMemberRepository.java src/test/java/com/wbsflow/project/ProjectMemberRepositoryTest.java
git commit -m "feat: 新增 ProjectMember 複合鍵 entity 與 repository"
```

---

### Task 5: WbsPreset Entity + Repository

**Files:**
- Create: `src/main/java/com/wbsflow/wbs/WbsPreset.java`
- Create: `src/main/java/com/wbsflow/wbs/WbsPresetRepository.java`
- Test: `src/test/java/com/wbsflow/wbs/WbsPresetRepositoryTest.java`

**Interfaces:**
- Consumes: `Department`（Task 1）
- Produces: `WbsPreset`（欄位：`id`、`type: WbsPreset.Type`、`name`、`sortOrder`、`enabled`、`section: Department`、`createdAt`）、`WbsPreset.Type` enum（`STAGE`/`CATEGORY`）

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.wbs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WbsPresetRepositoryTest {

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

    @Test
    void savesGlobalPresetWithNullSection() {
        WbsPreset stage = new WbsPreset();
        stage.setType(WbsPreset.Type.STAGE);
        stage.setName("SIT");
        stage.setSortOrder(1);
        WbsPreset saved = wbsPresetRepository.save(stage);

        assertThat(saved.getSection()).isNull();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getType()).isEqualTo(WbsPreset.Type.STAGE);
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsPresetRepositoryTest`
Expected: FAIL（`WbsPreset`/`WbsPresetRepository` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "wbs_presets")
@Getter
@Setter
public class WbsPreset {

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

```java
package com.wbsflow.wbs;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WbsPresetRepository extends JpaRepository<WbsPreset, Long> {
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsPresetRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsPreset.java src/main/java/com/wbsflow/wbs/WbsPresetRepository.java src/test/java/com/wbsflow/wbs/WbsPresetRepositoryTest.java
git commit -m "feat: 新增 WbsPreset entity 與 repository（STAGE/CATEGORY 選單）"
```

---

### Task 6: WbsNode Entity + Repository

**Files:**
- Create: `src/main/java/com/wbsflow/wbs/WbsNode.java`
- Create: `src/main/java/com/wbsflow/wbs/WbsNodeRepository.java`
- Test: `src/test/java/com/wbsflow/wbs/WbsNodeRepositoryTest.java`

**Interfaces:**
- Consumes: `Project`（Task 3）、`User`（Task 2）
- Produces: `WbsNode`（欄位：`id`、`project`、`parent: WbsNode`、`level: short`、`title`、`assignee: User`、`status: WbsNode.Status`、`priority: WbsNode.Priority`、`startDate`、`endDate`、`notes`、`sortOrder`、`createdAt`、`updatedAt`）、`WbsNode.Status`/`WbsNode.Priority` enum、`WbsNodeRepository.findByParentId(Long): List<WbsNode>`

**備註：** 三層固定語意（L1/L2 不可寫 assignee/status/priority/日期）已在 `sql/01_ddl.sql` 以 DB CHECK 約束保證，並於骨架階段對真實 Postgres 容器實測過；H2 測試環境的 schema 由 Entity 自動產生（`ddl-auto: create-drop`），不含這些 CHECK 約束，此處測試只驗證 Entity mapping。三層語意的**應用層**強制（含錯誤訊息、reorder、父層彙總）留給子專案 D（WBS 樹編輯器）的 `WbsNodeService`。

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.project.Project;
import com.wbsflow.project.ProjectRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WbsNodeRepositoryTest {

    @Autowired
    private WbsNodeRepository wbsNodeRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    private Project newProject() {
        Department section = departmentRepository.save(newSection());
        User owner = userRepository.save(newUser("leader", User.Role.PROJECT_LEADER, section));
        Project p = new Project();
        p.setName("測試專案");
        p.setSection(section);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        return projectRepository.save(p);
    }

    private Department newSection() {
        Department d = new Department();
        d.setName("系統科");
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
    void savesParentChildHierarchy() {
        Project project = newProject();

        WbsNode l1 = new WbsNode();
        l1.setProject(project);
        l1.setLevel((short) 1);
        l1.setTitle("SIT");
        WbsNode savedL1 = wbsNodeRepository.save(l1);

        WbsNode l2 = new WbsNode();
        l2.setProject(project);
        l2.setParent(savedL1);
        l2.setLevel((short) 2);
        l2.setTitle("程式開發");
        wbsNodeRepository.save(l2);

        List<WbsNode> children = wbsNodeRepository.findByParentId(savedL1.getId());

        assertThat(children).hasSize(1);
        assertThat(children.get(0).getTitle()).isEqualTo("程式開發");
    }

    @Test
    void savesL3NodeWithAssigneeStatusPriorityAndDates() {
        Project project = newProject();
        User member = userRepository.save(newUser("member", User.Role.PROJECT_MEMBER, project.getSection()));

        WbsNode l1 = new WbsNode();
        l1.setProject(project);
        l1.setLevel((short) 1);
        l1.setTitle("SIT");
        WbsNode savedL1 = wbsNodeRepository.save(l1);

        WbsNode l2 = new WbsNode();
        l2.setProject(project);
        l2.setParent(savedL1);
        l2.setLevel((short) 2);
        l2.setTitle("程式開發");
        WbsNode savedL2 = wbsNodeRepository.save(l2);

        WbsNode l3 = new WbsNode();
        l3.setProject(project);
        l3.setParent(savedL2);
        l3.setLevel((short) 3);
        l3.setTitle("登入功能開發");
        l3.setAssignee(member);
        l3.setStatus(WbsNode.Status.IN_PROGRESS);
        l3.setPriority(WbsNode.Priority.HIGH);
        l3.setStartDate(LocalDate.of(2026, 7, 20));
        l3.setEndDate(LocalDate.of(2026, 7, 31));
        WbsNode saved = wbsNodeRepository.save(l3);

        assertThat(saved.getStatus()).isEqualTo(WbsNode.Status.IN_PROGRESS);
        assertThat(saved.getPriority()).isEqualTo(WbsNode.Priority.HIGH);
        assertThat(saved.getAssignee().getUsername()).isEqualTo("member");
        assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=WbsNodeRepositoryTest`
Expected: FAIL（`WbsNode`/`WbsNodeRepository` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.wbs;

import com.wbsflow.project.Project;
import com.wbsflow.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "wbs_nodes")
@Getter
@Setter
public class WbsNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private WbsNode parent;

    @Column(nullable = false)
    private short level;

    @Column(nullable = false, length = 300)
    private String title;

    // 僅 L3 允許有值，由 sql/01_ddl.sql 的 CHECK 約束保證，應用層強制在子專案 D 實作
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Priority priority;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(columnDefinition = "text")
    private String notes;

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

```java
package com.wbsflow.wbs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WbsNodeRepository extends JpaRepository<WbsNode, Long> {
    List<WbsNode> findByParentId(Long parentId);
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=WbsNodeRepositoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/wbs/WbsNode.java src/main/java/com/wbsflow/wbs/WbsNodeRepository.java src/test/java/com/wbsflow/wbs/WbsNodeRepositoryTest.java
git commit -m "feat: 新增 WbsNode entity 與 repository（節點即任務核心表）"
```

---

### Task 7: ApiResponse + GlobalExceptionHandler

**Files:**
- Create: `src/main/java/com/wbsflow/common/ApiResponse.java`
- Create: `src/main/java/com/wbsflow/common/GlobalExceptionHandler.java`
- Test: `src/test/java/com/wbsflow/common/ApiResponseTest.java`
- Test: `src/test/java/com/wbsflow/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `ApiResponse<T>.ok(T): ApiResponse<T>`、`ApiResponse<T>.error(String): ApiResponse<T>`、`GlobalExceptionHandler`（`@RestControllerAdvice`，攔截 `EntityNotFoundException`→404、`IllegalArgumentException`→400、`SecurityException`→403、`AccessDeniedException`→403）

**備註：** 移植自 `com.wbsscaff.common`（與 `com.taskflow.common` 逐字相同），僅 package 名稱調整。此階段尚無 Controller，故 `GlobalExceptionHandlerTest` 直接呼叫 handler 方法驗證，不透過 HTTP 請求（HTTP 層整合測試留到子專案 B/C 有 Controller 後補上）。

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void okWrapsDataWithSuccessTrueAndNullMessage() {
        ApiResponse<String> res = ApiResponse.ok("hello");

        assertThat(res.isSuccess()).isTrue();
        assertThat(res.getData()).isEqualTo("hello");
        assertThat(res.getMessage()).isNull();
    }

    @Test
    void errorWrapsMessageWithSuccessFalseAndNullData() {
        ApiResponse<Void> res = ApiResponse.error("失敗原因");

        assertThat(res.isSuccess()).isFalse();
        assertThat(res.getMessage()).isEqualTo("失敗原因");
        assertThat(res.getData()).isNull();
    }
}
```

```java
package com.wbsflow.common;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsEntityNotFoundTo404() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleNotFound(new EntityNotFoundException("專案不存在"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getMessage()).isEqualTo("專案不存在");
    }

    @Test
    void mapsIllegalArgumentTo400() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleBadRequest(new IllegalArgumentException("父節點超出層級"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).isEqualTo("父節點超出層級");
    }

    @Test
    void mapsSecurityExceptionTo403() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleForbidden(new SecurityException("非成員"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getMessage()).isEqualTo("非成員");
    }

    @Test
    void mapsAccessDeniedTo403WithGenericMessage() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getMessage()).isEqualTo("存取被拒絕");
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ApiResponseTest,GlobalExceptionHandlerTest`
Expected: FAIL（`ApiResponse`/`GlobalExceptionHandler` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
```

```java
package com.wbsflow.common;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

// 統一將 domain exception 轉換成 HTTP 狀態碼，前端只需判斷 success 旗標，不需要處理 500
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 查不到資料（例如專案、節點、使用者）→ 404
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error(e.getMessage()));
    }

    // 業務規則違反（例如重複帳號、父節點超出層級）→ 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(e.getMessage()));
    }

    // 權限不足（例如非成員、封存狀態下編輯）→ 403
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(SecurityException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error(e.getMessage()));
    }

    // Spring Security 框架層拋出的 403（例如 @PreAuthorize 失敗）→ 403
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error("存取被拒絕"));
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ApiResponseTest,GlobalExceptionHandlerTest`
Expected: PASS（6 個測試）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/common src/test/java/com/wbsflow/common
git commit -m "feat: 移植 ApiResponse 與 GlobalExceptionHandler"
```

---

### Task 8: ProjectService.canRead / canWrite（權限核心）

**Files:**
- Create: `src/main/java/com/wbsflow/project/ProjectService.java`
- Test: `src/test/java/com/wbsflow/project/ProjectServiceTest.java`

**Interfaces:**
- Consumes: `ProjectRepository`（Task 3）、`ProjectMemberRepository`（Task 4）、`User`（Task 2）
- Produces: `ProjectService.getById(Long): Project`（查無拋 `EntityNotFoundException`）、`ProjectService.isMember(Long projectId, Long userId): boolean`、`ProjectService.canRead(Long projectId, User user): boolean`、`ProjectService.canWrite(Long projectId, User user): boolean`——後續所有 Controller（子專案 B 起）皆呼叫這兩個方法做存取控管，簽章不可再變動

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.project;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
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
class ProjectServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    private Department sectionA;
    private Department sectionB;
    private User director;
    private User chiefA;
    private User leaderA;
    private User memberA;
    private User outsiderA;
    private Project projectA;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        director = userRepository.save(newUser("director", User.Role.DIRECTOR, sectionA));
        chiefA = userRepository.save(newUser("chiefA", User.Role.SECTION_CHIEF, sectionA));
        leaderA = userRepository.save(newUser("leaderA", User.Role.PROJECT_LEADER, sectionA));
        memberA = userRepository.save(newUser("memberA", User.Role.PROJECT_MEMBER, sectionA));
        outsiderA = userRepository.save(newUser("outsiderA", User.Role.PROJECT_MEMBER, sectionA));

        Project p = new Project();
        p.setName("專案A");
        p.setSection(sectionA);
        p.setOwner(leaderA);
        p.setCreatedBy(leaderA);
        projectA = projectRepository.save(p);

        addMember(projectA, leaderA);
        addMember(projectA, memberA);
    }

    private void addMember(Project project, User user) {
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), user.getId()));
        pm.setAssignedBy(user);
        projectMemberRepository.save(pm);
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
    void directorCanReadAnyProjectAcrossSectionsButCannotWrite() {
        assertThat(projectService.canRead(projectA.getId(), director)).isTrue();
        assertThat(projectService.canWrite(projectA.getId(), director)).isFalse();
    }

    @Test
    void sectionChiefCanReadAndWriteSameSectionProject() {
        assertThat(projectService.canRead(projectA.getId(), chiefA)).isTrue();
        assertThat(projectService.canWrite(projectA.getId(), chiefA)).isTrue();
    }

    @Test
    void sectionChiefFromOtherSectionCannotAccess() {
        User chiefB = userRepository.save(newUser("chiefB", User.Role.SECTION_CHIEF, sectionB));

        assertThat(projectService.canRead(projectA.getId(), chiefB)).isFalse();
        assertThat(projectService.canWrite(projectA.getId(), chiefB)).isFalse();
    }

    @Test
    void projectLeaderAsMemberCanReadAndWrite() {
        assertThat(projectService.canRead(projectA.getId(), leaderA)).isTrue();
        assertThat(projectService.canWrite(projectA.getId(), leaderA)).isTrue();
    }

    @Test
    void projectMemberAsMemberCanReadAndWrite() {
        assertThat(projectService.canRead(projectA.getId(), memberA)).isTrue();
        assertThat(projectService.canWrite(projectA.getId(), memberA)).isTrue();
    }

    @Test
    void nonMemberInSameSectionCannotAccess() {
        assertThat(projectService.canRead(projectA.getId(), outsiderA)).isFalse();
        assertThat(projectService.canWrite(projectA.getId(), outsiderA)).isFalse();
    }

    @Test
    void archivedProjectIsReadOnlyForEveryoneIncludingSectionChief() {
        projectA.setArchived(true);
        projectRepository.save(projectA);

        assertThat(projectService.canRead(projectA.getId(), chiefA)).isTrue();
        assertThat(projectService.canWrite(projectA.getId(), chiefA)).isFalse();
        assertThat(projectService.canRead(projectA.getId(), memberA)).isTrue();
        assertThat(projectService.canWrite(projectA.getId(), memberA)).isFalse();
    }

    @Test
    void nullArgumentsReturnFalseInsteadOfThrowing() {
        assertThat(projectService.canRead(null, chiefA)).isFalse();
        assertThat(projectService.canRead(projectA.getId(), null)).isFalse();
        assertThat(projectService.canWrite(null, chiefA)).isFalse();
        assertThat(projectService.canWrite(projectA.getId(), null)).isFalse();
    }

    @Test
    void canReadThrowsNotFoundForNonExistentProject() {
        assertThatThrownBy(() -> projectService.canRead(999999L, chiefA))
            .isInstanceOf(EntityNotFoundException.class);
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectServiceTest`
Expected: FAIL（`ProjectService` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.project;

import com.wbsflow.user.User;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

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

    private boolean sameSection(Project project, User user) {
        if (project.getSection() == null || user.getDepartment() == null) return false;
        return project.getSection().getId().equals(user.getDepartment().getId());
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectServiceTest`
Expected: PASS（9 個測試）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/project/ProjectService.java src/test/java/com/wbsflow/project/ProjectServiceTest.java
git commit -m "feat: 新增 ProjectService.canRead/canWrite 權限核心"
```

---

## 全部完成後

- [ ] 執行 `mvn test` 確認全專案測試皆綠燈
- [ ] 執行 `mvn -q compile` 確認無編譯警告殘留
- [ ] 回報使用者：本子專案完成，等待核准後進入子專案 B（認證與登入）
