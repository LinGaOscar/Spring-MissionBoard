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
