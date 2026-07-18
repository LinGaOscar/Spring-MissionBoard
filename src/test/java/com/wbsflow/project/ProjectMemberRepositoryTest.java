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
