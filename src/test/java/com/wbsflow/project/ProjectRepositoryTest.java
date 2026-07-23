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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

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
}
