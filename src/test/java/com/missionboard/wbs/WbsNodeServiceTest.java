package com.missionboard.wbs;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectMember;
import com.missionboard.project.ProjectMemberId;
import com.missionboard.project.ProjectMemberRepository;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

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
    void createsL3NodeWithDefaultStatusNotStarted() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");

        WbsNode created = wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l2.getId(), null, "登入功能開發", null));

        assertThat(created.getStatus()).isEqualTo(WbsNode.Status.NOT_STARTED);
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

    @Test
    void getTreeAggregatesEmptyNodeAsNotStarted() {
        newL1("SIT");

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).status()).isEqualTo("NOT_STARTED");
        assertThat(tree.get(0).startDate()).isNull();
    }

    @Test
    void getTreeAggregatesAllDoneAsL2Done() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3a = newL3(l2, "功能A");
        l3a.setStatus(WbsNode.Status.DONE);
        l3a.setStartDate(java.time.LocalDate.of(2026, 8, 1));
        l3a.setEndDate(java.time.LocalDate.of(2026, 8, 5));
        wbsNodeRepository.save(l3a);
        WbsNode l3b = newL3(l2, "功能B");
        l3b.setStatus(WbsNode.Status.DONE);
        l3b.setStartDate(java.time.LocalDate.of(2026, 8, 3));
        l3b.setEndDate(java.time.LocalDate.of(2026, 8, 10));
        wbsNodeRepository.save(l3b);

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        WbsNodeDto.Response l2Response = tree.stream().filter(r -> r.id().equals(l2.getId())).findFirst().orElseThrow();
        assertThat(l2Response.status()).isEqualTo("DONE");
        assertThat(l2Response.startDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
        assertThat(l2Response.endDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 10));
    }

    @Test
    void getTreeAggregatesMixedStatusAsInProgress() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3a = newL3(l2, "功能A");
        l3a.setStatus(WbsNode.Status.DONE);
        wbsNodeRepository.save(l3a);
        WbsNode l3b = newL3(l2, "功能B");
        l3b.setStatus(WbsNode.Status.NOT_STARTED);
        wbsNodeRepository.save(l3b);

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        WbsNodeDto.Response l2Response = tree.stream().filter(r -> r.id().equals(l2.getId())).findFirst().orElseThrow();
        assertThat(l2Response.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void getTreeDoesNotMisclassifyNewL3NodeAsInProgress() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");

        // Create L3 via service (which now sets default status to NOT_STARTED)
        WbsNode l3 = wbsNodeService.createNode(project.getId(),
            new WbsNodeDto.CreateRequest(l2.getId(), null, "登入功能開發", null));

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        // L2's aggregated status should be NOT_STARTED (only child is L3 with NOT_STARTED)
        WbsNodeDto.Response l2Response = tree.stream().filter(r -> r.id().equals(l2.getId())).findFirst().orElseThrow();
        assertThat(l2Response.status()).isEqualTo("NOT_STARTED");

        // L1's aggregated status should also be NOT_STARTED
        WbsNodeDto.Response l1Response = tree.stream().filter(r -> r.id().equals(l1.getId())).findFirst().orElseThrow();
        assertThat(l1Response.status()).isEqualTo("NOT_STARTED");
    }

    @Test
    void getTreeAggregatesL1FromL2AggregatedResultsNotDirectlyFromL3() {
        WbsNode l1 = newL1("SIT");
        WbsNode doneCategory = newL2(l1, "已完成類別");
        WbsNode l3Done = newL3(doneCategory, "功能A");
        l3Done.setStatus(WbsNode.Status.DONE);
        wbsNodeRepository.save(l3Done);
        // 第二個 L2 是空節點（無子節點），依規則視為 NOT_STARTED，
        // 使 L1 的彙總來源是「DONE、NOT_STARTED」混合 → IN_PROGRESS
        newL2(l1, "空類別");

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        WbsNodeDto.Response l1Response = tree.stream().filter(r -> r.id().equals(l1.getId())).findFirst().orElseThrow();
        assertThat(l1Response.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void getTreeReturnsStoredValuesDirectlyForL3() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        l3.setStatus(WbsNode.Status.IN_PROGRESS);
        l3.setPriority(WbsNode.Priority.HIGH);
        wbsNodeRepository.save(l3);

        List<WbsNodeDto.Response> tree = wbsNodeService.getTree(project.getId());

        WbsNodeDto.Response l3Response = tree.stream().filter(r -> r.id().equals(l3.getId())).findFirst().orElseThrow();
        assertThat(l3Response.status()).isEqualTo("IN_PROGRESS");
        assertThat(l3Response.priority()).isEqualTo("HIGH");
        assertThat(l3Response.assigneeId()).isNull();
    }

    @Test
    void reorderChangesSortOrderWithinSameParent() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2a = newL2(l1, "類別A");
        WbsNode l2b = newL2(l1, "類別B");

        wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l2a.getId(), l1.getId(), 1),
            new WbsNodeDto.ReorderItem(l2b.getId(), l1.getId(), 0)
        ));

        assertThat(wbsNodeRepository.findById(l2a.getId()).orElseThrow().getSortOrder()).isEqualTo(1);
        assertThat(wbsNodeRepository.findById(l2b.getId()).orElseThrow().getSortOrder()).isEqualTo(0);
    }

    @Test
    void reorderAcrossLevelsPromotesL3ToL1() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l3.getId(), null, 0)
        ));

        WbsNode moved = wbsNodeRepository.findById(l3.getId()).orElseThrow();
        assertThat(moved.getLevel()).isEqualTo((short) 1);
        assertThat(moved.getParent()).isNull();
    }

    @Test
    void reorderClearsL3OnlyFieldsWhenNodeNoLongerL3() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        l3.setStatus(WbsNode.Status.IN_PROGRESS);
        l3.setPriority(WbsNode.Priority.HIGH);
        wbsNodeRepository.save(l3);

        wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l3.getId(), null, 0)
        ));

        WbsNode moved = wbsNodeRepository.findById(l3.getId()).orElseThrow();
        assertThat(moved.getStatus()).isNull();
        assertThat(moved.getPriority()).isNull();
    }

    @Test
    void reorderCascadesLevelShiftToDescendants() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        WbsNode anotherL1 = newL1("UAT");

        // 把 L2（帶著它的 L3 子節點）搬到另一個 L1 底下，level 應維持 2/3 不變（同層搬移，delta=0）
        wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l2.getId(), anotherL1.getId(), 0)
        ));

        assertThat(wbsNodeRepository.findById(l2.getId()).orElseThrow().getLevel()).isEqualTo((short) 2);
        assertThat(wbsNodeRepository.findById(l3.getId()).orElseThrow().getLevel()).isEqualTo((short) 3);
        assertThat(wbsNodeRepository.findById(l3.getId()).orElseThrow().getParent().getId()).isEqualTo(l2.getId());
    }

    @Test
    void reorderRejectsMoveThatWouldExceedThreeLevels() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        WbsNode anotherL1 = newL1("UAT");
        WbsNode anotherL2 = newL2(anotherL1, "環境建置");
        WbsNode anotherL3 = newL3(anotherL2, "防火牆申請");

        // 把帶有 L3 子節點的 L2 搬到別的 L2 底下會變成 L3，其子節點會變成第四層 → 拒絕
        assertThatThrownBy(() -> wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l2.getId(), anotherL2.getId(), 0)
        ))).isInstanceOf(IllegalArgumentException.class);

        // 確認拒絕後完全沒有套用（level 與 parent 都維持原狀）
        assertThat(wbsNodeRepository.findById(l2.getId()).orElseThrow().getLevel()).isEqualTo((short) 2);
        assertThat(wbsNodeRepository.findById(l2.getId()).orElseThrow().getParent().getId()).isEqualTo(l1.getId());
    }

    @Test
    void reorderRejectsNodeAsItsOwnParent() {
        WbsNode l1 = newL1("SIT");

        // parentId 等於 nodeId 本身會形成自我循環參照，即使深度檢查會放行也必須明確拒絕
        assertThatThrownBy(() -> wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(l1.getId(), l1.getId(), 0)
        ))).isInstanceOf(IllegalArgumentException.class);

        // 確認拒絕後完全沒有套用（level 與 parent 都維持原狀）
        WbsNode reloaded = wbsNodeRepository.findById(l1.getId()).orElseThrow();
        assertThat(reloaded.getLevel()).isEqualTo((short) 1);
        assertThat(reloaded.getParent()).isNull();
    }

    @Test
    void reorderRejectsNodeNotBelongingToProject() {
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

        assertThatThrownBy(() -> wbsNodeService.reorder(project.getId(), List.of(
            new WbsNodeDto.ReorderItem(savedForeignL1.getId(), null, 0)
        ))).isInstanceOf(SecurityException.class);
    }

    @Test
    void updateStatusOnL3Node() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        WbsNode updated = wbsNodeService.updateStatus(project.getId(), l3.getId(), "IN_PROGRESS");

        assertThat(updated.getStatus()).isEqualTo(WbsNode.Status.IN_PROGRESS);
    }

    @Test
    void updateStatusRejectedOnNonL3Node() {
        WbsNode l1 = newL1("SIT");

        assertThatThrownBy(() -> wbsNodeService.updateStatus(project.getId(), l1.getId(), "DONE"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateStatusRejectsInvalidEnumValue() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");

        assertThatThrownBy(() -> wbsNodeService.updateStatus(project.getId(), l3.getId(), "NOT_A_STATUS"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateAssigneeRequiresProjectMembership() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        User outsider = userRepository.save(newUser("outsider", sectionA));

        assertThatThrownBy(() -> wbsNodeService.updateAssignee(project.getId(), l3.getId(), outsider.getId()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateAssigneeSucceedsForProjectMember() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        User member = userRepository.save(newUser("member", sectionA));
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), member.getId()));
        pm.setAssignedBy(member);
        projectMemberRepository.save(pm);

        WbsNode updated = wbsNodeService.updateAssignee(project.getId(), l3.getId(), member.getId());

        assertThat(updated.getAssignee().getId()).isEqualTo(member.getId());
    }

    @Test
    void updateAssigneeWithNullClearsAssignment() {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = newL2(l1, "程式開發");
        WbsNode l3 = newL3(l2, "登入功能開發");
        User member = userRepository.save(newUser("member", sectionA));
        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), member.getId()));
        pm.setAssignedBy(member);
        projectMemberRepository.save(pm);
        wbsNodeService.updateAssignee(project.getId(), l3.getId(), member.getId());

        WbsNode cleared = wbsNodeService.updateAssignee(project.getId(), l3.getId(), null);

        assertThat(cleared.getAssignee()).isNull();
    }

    @Test
    void updateAssigneeRejectedOnNonL3Node() {
        WbsNode l1 = newL1("SIT");
        User member = userRepository.save(newUser("member", sectionA));

        assertThatThrownBy(() -> wbsNodeService.updateAssignee(project.getId(), l1.getId(), member.getId()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initStagesCreatesL1NodeForEachSelectedStage() {
        wbsNodeService.initStages(project.getId(), List.of(stagePreset.getId()));

        List<WbsNode> nodes = wbsNodeRepository.findByProjectId(project.getId());
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getLevel()).isEqualTo((short) 1);
        assertThat(nodes.get(0).getTitle()).isEqualTo("SIT");
    }

    @Test
    void initStagesDefaultsToAllVisibleStagesWhenNoneSpecified() {
        wbsNodeService.initStages(project.getId(), null);

        List<WbsNode> nodes = wbsNodeRepository.findByProjectId(project.getId());
        // setUp 只建立了一個對本專案科別可見的 STAGE（stagePreset，全域）；
        // otherSectionStagePreset 屬於 sectionB，對本專案（sectionA）不可見
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getTitle()).isEqualTo("SIT");
    }

    @Test
    void initStagesRejectsWhenProjectAlreadyHasNodes() {
        newL1("既有節點");

        assertThatThrownBy(() -> wbsNodeService.initStages(project.getId(), List.of(stagePreset.getId())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initStagesRejectsPresetFromOtherSection() {
        assertThatThrownBy(() -> wbsNodeService.initStages(project.getId(), List.of(otherSectionStagePreset.getId())))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
