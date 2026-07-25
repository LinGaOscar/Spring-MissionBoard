package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.project.Project;
import com.wbsflow.project.ProjectRepository;
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
class WbsNodeServiceTest {

    @Autowired
    private WbsNodeService wbsNodeService;

    @Autowired
    private WbsNodeRepository wbsNodeRepository;

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    private Department sectionA;
    private Department sectionB;
    private Project project;
    private WbsPreset stagePreset;
    private WbsPreset categoryPreset;
    private WbsPreset otherSectionStagePreset;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        User owner = userRepository.save(newUser("leader", sectionA));

        Project p = new Project();
        p.setName("測試專案");
        p.setSection(sectionA);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        project = projectRepository.save(p);

        stagePreset = newPreset(WbsPreset.Type.STAGE, "SIT", null);
        categoryPreset = newPreset(WbsPreset.Type.CATEGORY, "程式開發", null);
        otherSectionStagePreset = newPreset(WbsPreset.Type.STAGE, "網路科限定", sectionB);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private User newUser(String username, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("hash");
        u.setDisplayName(username);
        u.setRole(User.Role.PROJECT_LEADER);
        u.setDepartment(dept);
        return u;
    }

    private WbsPreset newPreset(WbsPreset.Type type, String name, Department section) {
        WbsPreset preset = new WbsPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(1);
        preset.setSection(section);
        return wbsPresetRepository.save(preset);
    }

    private WbsNode newL1(String title) {
        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setLevel((short) 1);
        node.setTitle(title);
        return wbsNodeRepository.save(node);
    }

    private WbsNode newL2(WbsNode parent, String title) {
        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setParent(parent);
        node.setLevel((short) 2);
        node.setTitle(title);
        return wbsNodeRepository.save(node);
    }

    private WbsNode newL3(WbsNode parent, String title) {
        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setParent(parent);
        node.setLevel((short) 3);
        node.setTitle(title);
        return wbsNodeRepository.save(node);
    }

    @Test
    void createsL1NodeFromStagePresetSnapshot() {
        WbsNode created = wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(null, stagePreset.getId(), null, null));

        assertThat(created.getLevel()).isEqualTo((short) 1);
        assertThat(created.getTitle()).isEqualTo("SIT");
        assertThat(created.getParent()).isNull();
    }

    @Test
    void createsL2NodeFromCategoryPresetUnderL1Parent() {
        WbsNode l1 = newL1("SIT");

        WbsNode created = wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l1.getId(), categoryPreset.getId(), null, null));

        assertThat(created.getLevel()).isEqualTo((short) 2);
        assertThat(created.getTitle()).isEqualTo("程式開發");
        assertThat(created.getParent().getId()).isEqualTo(l1.getId());
    }

    @Test
    void createsL3NodeWithFreeTextTitleUnderL2Parent() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");

        WbsNode created = wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l2.getId(), null, "登入功能開發", null));

        assertThat(created.getLevel()).isEqualTo((short) 3);
        assertThat(created.getTitle()).isEqualTo("登入功能開發");
    }

    @Test
    void rejectsCreatingChildUnderL3Node() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l3.getId(), null, "超過三層", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsL3CreationWithoutTitle() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");

        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l2.getId(), null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsL1CreationWithoutPresetId() {
        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(null, null, "自己亂打", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPresetFromOtherSectionNotVisibleToThisProject() {
        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(null, otherSectionStagePreset.getId(), null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsParentNodeFromAnotherProject() {
        Project otherProject = new Project();
        otherProject.setName("別的專案");
        otherProject.setSection(sectionA);
        otherProject.setOwner(project.getOwner());
        otherProject.setCreatedBy(project.getOwner());
        otherProject = projectRepository.save(otherProject);

        WbsNode foreignL1 = new WbsNode();
        foreignL1.setProject(otherProject);
        foreignL1.setLevel((short) 1);
        foreignL1.setTitle("別專案的階段");
        WbsNode savedForeignL1 = wbsNodeRepository.save(foreignL1);

        assertThatThrownBy(() -> wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(savedForeignL1.getId(), categoryPreset.getId(), null, null)))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void updatesTitleAndNotesOnAnyLevel() {
        WbsNode l1 = newL1("SIT");

        WbsNode updated = wbsNodeService.updateNode(project.getId(), l1.getId(),
            new WbsNodeDto.UpdateRequest("改過的標題", "備註", null, null, null));

        assertThat(updated.getTitle()).isEqualTo("改過的標題");
        assertThat(updated.getNotes()).isEqualTo("備註");
    }

    @Test
    void updatesPriorityAndDatesOnL3Node() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        WbsNode updated = wbsNodeService.updateNode(project.getId(), l3.getId(),
            new WbsNodeDto.UpdateRequest(null, null, "HIGH",
                java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 15)));

        assertThat(updated.getPriority()).isEqualTo(WbsNode.Priority.HIGH);
        assertThat(updated.getStartDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
    }

    @Test
    void rejectsPriorityUpdateOnNonL3Node() {
        WbsNode l1 = newL1("SIT");

        assertThatThrownBy(() -> wbsNodeService.updateNode(project.getId(), l1.getId(),
            new WbsNodeDto.UpdateRequest(null, null, "HIGH", null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteNodeCascadesToChildren() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        newL3(l2, "登入功能開發");

        wbsNodeService.deleteNode(project.getId(), l1.getId());

        assertThat(wbsNodeRepository.findById(l1.getId())).isEmpty();
        assertThat(wbsNodeRepository.findById(l2.getId())).isEmpty();
    }

    @Test
    void deleteThrowsNotFoundForNonExistentNode() {
        assertThatThrownBy(() -> wbsNodeService.deleteNode(project.getId(), 999999L))
            .isInstanceOf(EntityNotFoundException.class);
    }
}
