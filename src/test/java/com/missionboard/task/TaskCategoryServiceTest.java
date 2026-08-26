package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
class TaskCategoryServiceTest {

    @Autowired
    private TaskCategoryService taskCategoryService;
    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private Project project;
    private Project otherProject;
    private TaskCategoryPreset stagePreset;
    private TaskCategoryPreset categoryPreset;
    private TaskCategoryPreset otherSectionStagePreset;

    @BeforeEach
    void setUp() {
        Department sectionA = newDept("系統科A");
        Department sectionB = newDept("系統科B");
        User owner = newUser("owner", sectionA);

        project = newProject(sectionA, owner);
        otherProject = newProject(sectionA, owner);

        stagePreset = newPreset(TaskCategoryPreset.Type.STAGE, "SIT", null);
        categoryPreset = newPreset(TaskCategoryPreset.Type.CATEGORY, "程式開發", null);
        otherSectionStagePreset = newPreset(TaskCategoryPreset.Type.STAGE, "B科限定", sectionB);
    }

    @Test
    void createsStageLevelCategoryFromStagePresetSnapshot() {
        TaskCategory category = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        assertThat(category.getName()).isEqualTo("SIT");
        assertThat(category.getParentCategory()).isNull();
    }

    @Test
    void createsSubCategoryUnderStageLevelParent() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null, null));
        assertThat(sub.getParentCategory().getId()).isEqualTo(stage.getId());
    }

    @Test
    void rejectsThirdLevelCategory() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null, null));

        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(sub.getId(), categoryPreset.getId(), null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsSubCategoryFromNameWhenPresetIdMissing() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), null, "自訂子類別", null));
        assertThat(sub.getName()).isEqualTo("自訂子類別");
        assertThat(sub.getParentCategory().getId()).isEqualTo(stage.getId());
    }

    @Test
    void rejectsCreateWhenBothPresetIdAndNameMissing() {
        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPresetFromOtherSectionNotVisibleToThisProject() {
        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(null, otherSectionStagePreset.getId(), null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsParentCategoryFromAnotherProject() {
        TaskCategory foreignStage = taskCategoryService.create(otherProject.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));

        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(foreignStage.getId(), categoryPreset.getId(), null, null)))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void updatesNameAndSortOrder() {
        TaskCategory category = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory updated = taskCategoryService.update(project.getId(), category.getId(),
            new TaskCategoryDto.UpdateRequest("改名後階段", 5, null));
        assertThat(updated.getName()).isEqualTo("改名後階段");
        assertThat(updated.getSortOrder()).isEqualTo(5);
    }

    @Test
    void reparentsSubCategoryToAnotherStage() {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory stageB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageA.getId(), categoryPreset.getId(), null, null));

        TaskCategory moved = taskCategoryService.update(project.getId(), sub.getId(),
            new TaskCategoryDto.UpdateRequest(null, 0, stageB.getId()));

        assertThat(moved.getParentCategory().getId()).isEqualTo(stageB.getId());
    }

    @Test
    void rejectsReparentingAStageItself() {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory stageB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));

        assertThatThrownBy(() -> taskCategoryService.update(project.getId(), stageA.getId(),
                new TaskCategoryDto.UpdateRequest(null, null, stageB.getId())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReparentingToASubCategory() {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory subA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageA.getId(), categoryPreset.getId(), null, null));
        TaskCategory stageB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory subB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageB.getId(), categoryPreset.getId(), null, null));

        assertThatThrownBy(() -> taskCategoryService.update(project.getId(), subA.getId(),
                new TaskCategoryDto.UpdateRequest(null, null, subB.getId())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReparentingToStageFromAnotherProject() {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory subA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageA.getId(), categoryPreset.getId(), null, null));
        TaskCategory foreignStage = taskCategoryService.create(otherProject.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));

        assertThatThrownBy(() -> taskCategoryService.update(project.getId(), subA.getId(),
                new TaskCategoryDto.UpdateRequest(null, null, foreignStage.getId())))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void deleteCascadesToChildCategory() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null, null));

        taskCategoryService.delete(project.getId(), stage.getId());

        assertThat(taskCategoryService.list(project.getId())).isEmpty();
    }

    // 整個「任務天生獨立、分類選配」設計都靠這條行為撐著：刪類別不能連帶刪任務，
    // 只能讓任務落回未歸類（category_id = NULL，見 Task.category 的 ON DELETE SET NULL）
    @Test
    void deletingCategoryOrphansItsTasksInsteadOfDeletingThem() {
        TaskCategory category = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));

        Task task = new Task();
        task.setProject(project);
        task.setCategory(category);
        task.setTitle("待歸類任務");
        Task savedTask = taskRepository.save(task);
        Long taskId = savedTask.getId();

        // 先把 Task 逐出一級快取：這條測試要驗證的是 DB 層 ON DELETE SET NULL 的效果，
        // 若 savedTask 仍留在同一 persistence context，Hibernate flush 前的一致性檢查
        // 會把它「還指著即將被刪除的類別」視為懸空參照丟 TransientObjectException，
        // 這是 Hibernate session 快取的干擾，跟我們要驗證的 DB 行為無關
        entityManager.detach(savedTask);

        taskCategoryService.delete(project.getId(), category.getId());

        // ON DELETE SET NULL 是 DB 層動作，Hibernate 一級快取不會知道，
        // 不 flush+clear 直接 findById 會拿到記憶體裡的舊快照（category 仍非 null）、蓋掉這條測試想驗證的行為
        entityManager.flush();
        entityManager.clear();

        Task reloaded = taskRepository.findById(taskId).orElseThrow();
        assertThat(reloaded.getCategory()).isNull();
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return departmentRepository.save(d);
    }

    private User newUser(String username, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("x");
        u.setDisplayName(username);
        u.setRole(User.Role.PROJECT_LEADER);
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

    private TaskCategoryPreset newPreset(TaskCategoryPreset.Type type, String name, Department section) {
        TaskCategoryPreset preset = new TaskCategoryPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(0);
        preset.setSection(section);
        return taskCategoryPresetRepository.save(preset);
    }
}
