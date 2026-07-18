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
