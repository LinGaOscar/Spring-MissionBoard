package com.missionboard.project;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import com.missionboard.wbs.WbsNode;
import com.missionboard.wbs.WbsNodeRepository;
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

    @Autowired
    private WbsNodeRepository wbsNodeRepository;

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

    @Test
    void removeMemberThrowsNotFoundForNonMemberUser() {
        assertThatThrownBy(() -> projectService.removeMember(projectA.getId(), outsiderA.getId()))
            .isInstanceOf(EntityNotFoundException.class)
            .hasMessageContaining("該使用者不是此專案成員");
    }
}
