package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
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
