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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class DashboardServiceTest {

    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private TaskRepository taskRepository;

    private Department sectionA;
    private Department sectionB;
    private User leaderA;
    private User memberA;
    private User chiefA;
    private User director;
    private Project projectA;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        director = userRepository.save(newUser("directorX", User.Role.DIRECTOR, sectionA));
        chiefA = userRepository.save(newUser("chiefX", User.Role.SECTION_CHIEF, sectionA));
        leaderA = userRepository.save(newUser("leaderX", User.Role.PROJECT_LEADER, sectionA));
        memberA = userRepository.save(newUser("memberX", User.Role.PROJECT_MEMBER, sectionA));

        Project p = new Project();
        p.setName("專案A");
        p.setSection(sectionA);
        p.setOwner(leaderA);
        p.setCreatedBy(leaderA);
        projectA = projectRepository.save(p);
        addMember(projectA, leaderA);
        addMember(projectA, memberA);
    }

    @Test
    void projectLeaderGetsPersonalViewWithOwnProjectsAndAssignedTasks() {
        Task assigned = newTask(projectA, "指派給我的任務", leaderA, Task.Status.NOT_STARTED, LocalDate.now().plusDays(3));
        taskRepository.save(assigned);
        Task others = newTask(projectA, "別人的任務", memberA, Task.Status.NOT_STARTED, LocalDate.now().plusDays(1));
        taskRepository.save(others);

        DashboardDto.Response response = dashboardService.getDashboard(leaderA);

        assertThat(response.viewType()).isEqualTo("PERSONAL");
        assertThat(response.activeProjects()).extracting("id").containsExactly(projectA.getId());
        assertThat(response.myTasks()).extracting("title").containsExactly("指派給我的任務");
    }

    @Test
    void personalViewExcludesDoneTasksAndArchivedProjectTasks() {
        Task done = newTask(projectA, "已完成任務", memberA, Task.Status.DONE, null);
        taskRepository.save(done);

        Project archived = new Project();
        archived.setName("已封存專案");
        archived.setSection(sectionA);
        archived.setOwner(leaderA);
        archived.setCreatedBy(leaderA);
        archived.setArchived(true);
        archived = projectRepository.save(archived);
        addMember(archived, memberA);
        Task fromArchived = newTask(archived, "封存專案任務", memberA, Task.Status.NOT_STARTED, LocalDate.now());
        taskRepository.save(fromArchived);

        DashboardDto.Response response = dashboardService.getDashboard(memberA);

        assertThat(response.myTasks()).isEmpty();
        assertThat(response.activeProjects()).extracting("id").containsExactly(projectA.getId());
    }

    @Test
    void personalViewSortsTasksByDueDateWithNullsLast() {
        Task noDueDate = newTask(projectA, "無到期日", memberA, Task.Status.NOT_STARTED, null);
        taskRepository.save(noDueDate);
        Task earlier = newTask(projectA, "較早到期", memberA, Task.Status.NOT_STARTED, LocalDate.now().plusDays(1));
        taskRepository.save(earlier);
        Task later = newTask(projectA, "較晚到期", memberA, Task.Status.NOT_STARTED, LocalDate.now().plusDays(5));
        taskRepository.save(later);

        DashboardDto.Response response = dashboardService.getDashboard(memberA);

        assertThat(response.myTasks()).extracting("title")
            .containsExactly("較早到期", "較晚到期", "無到期日");
    }

    @Test
    void sectionChiefGetsSectionViewWithAllSectionProjectsRegardlessOfMembership() {
        Task overdueTask = newTask(projectA, "逾期任務", memberA, Task.Status.NOT_STARTED, LocalDate.now().minusDays(1));
        taskRepository.save(overdueTask);
        Task doneTask = newTask(projectA, "完成任務", memberA, Task.Status.DONE, LocalDate.now().plusDays(1));
        taskRepository.save(doneTask);

        Project projectB = new Project();
        projectB.setName("科長不是成員的專案");
        projectB.setSection(sectionA);
        projectB.setOwner(leaderA);
        projectB.setCreatedBy(leaderA);
        projectRepository.save(projectB);

        DashboardDto.Response response = dashboardService.getDashboard(chiefA);

        assertThat(response.viewType()).isEqualTo("SECTION");
        assertThat(response.sectionName()).isEqualTo("系統科");
        assertThat(response.projectSummaries()).hasSize(2);
        DashboardDto.ProjectSummary summaryA = response.projectSummaries().stream()
            .filter(s -> s.projectId().equals(projectA.getId())).findFirst().orElseThrow();
        assertThat(summaryA.taskCount()).isEqualTo(2);
        assertThat(summaryA.overdueCount()).isEqualTo(1);
        assertThat(summaryA.completionLabel()).isEqualTo("50%");
    }

    @Test
    void sectionViewExcludesOtherSectionsAndArchivedProjects() {
        Project projectB = new Project();
        projectB.setName("網路科專案");
        projectB.setSection(sectionB);
        projectB.setOwner(leaderA);
        projectB.setCreatedBy(leaderA);
        projectRepository.save(projectB);

        DashboardDto.Response response = dashboardService.getDashboard(chiefA);

        assertThat(response.projectSummaries()).extracting("projectId").containsExactly(projectA.getId());
    }

    @Test
    void directorGetsOrgViewGroupedBySection() {
        Task task = newTask(projectA, "系統科任務", memberA, Task.Status.NOT_STARTED, LocalDate.now().minusDays(1));
        taskRepository.save(task);

        Project projectB = new Project();
        projectB.setName("網路科專案");
        projectB.setSection(sectionB);
        projectB.setOwner(leaderA);
        projectB.setCreatedBy(leaderA);
        projectRepository.save(projectB);

        DashboardDto.Response response = dashboardService.getDashboard(director);

        assertThat(response.viewType()).isEqualTo("ORG");
        assertThat(response.sectionSummaries()).hasSize(2);
        DashboardDto.SectionSummary sectionASummary = response.sectionSummaries().stream()
            .filter(s -> s.sectionId().equals(sectionA.getId())).findFirst().orElseThrow();
        assertThat(sectionASummary.projectCount()).isEqualTo(1);
        assertThat(sectionASummary.overdueCount()).isEqualTo(1);
    }

    @Test
    void orgViewExcludesArchivedProjects() {
        Project archived = new Project();
        archived.setName("已封存專案");
        archived.setSection(sectionB);
        archived.setOwner(leaderA);
        archived.setCreatedBy(leaderA);
        archived.setArchived(true);
        projectRepository.save(archived);

        DashboardDto.Response response = dashboardService.getDashboard(director);

        assertThat(response.sectionSummaries()).extracting("sectionId").containsExactly(sectionA.getId());
    }

    @Test
    void emptyResultsReturnEmptyListsNotNullAndCompletionLabelIsDashWhenNoTasks() {
        Project emptyProject = new Project();
        emptyProject.setName("無任務專案");
        emptyProject.setSection(sectionA);
        emptyProject.setOwner(leaderA);
        emptyProject.setCreatedBy(leaderA);
        projectRepository.save(emptyProject);

        DashboardDto.Response personalResponse = dashboardService.getDashboard(memberA);
        assertThat(personalResponse.myTasks()).isNotNull().isEmpty();

        DashboardDto.Response sectionResponse = dashboardService.getDashboard(chiefA);
        DashboardDto.ProjectSummary emptySummary = sectionResponse.projectSummaries().stream()
            .filter(s -> s.projectId().equals(emptyProject.getId())).findFirst().orElseThrow();
        assertThat(emptySummary.completionLabel()).isEqualTo("--");
    }

    private Task newTask(Project project, String title, User assignee, Task.Status status, LocalDate dueDate) {
        Task t = new Task();
        t.setProject(project);
        t.setTitle(title);
        t.setAssignee(assignee);
        t.setStatus(status);
        t.setDueDate(dueDate);
        return t;
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
}
