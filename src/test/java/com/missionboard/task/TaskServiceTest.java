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
