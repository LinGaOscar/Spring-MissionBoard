package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
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
    private ProjectRepository projectRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;

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
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        assertThat(category.getName()).isEqualTo("SIT");
        assertThat(category.getParentCategory()).isNull();
    }

    @Test
    void createsSubCategoryUnderStageLevelParent() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null));
        assertThat(sub.getParentCategory().getId()).isEqualTo(stage.getId());
    }

    @Test
    void rejectsThirdLevelCategory() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null));

        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(sub.getId(), categoryPreset.getId(), null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPresetFromOtherSectionNotVisibleToThisProject() {
        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(null, otherSectionStagePreset.getId(), null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsParentCategoryFromAnotherProject() {
        TaskCategory foreignStage = taskCategoryService.create(otherProject.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));

        assertThatThrownBy(() -> taskCategoryService.create(project.getId(),
                new TaskCategoryDto.CreateRequest(foreignStage.getId(), categoryPreset.getId(), null)))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void updatesNameAndSortOrder() {
        TaskCategory category = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        TaskCategory updated = taskCategoryService.update(project.getId(), category.getId(),
            new TaskCategoryDto.UpdateRequest("改名後階段", 5));
        assertThat(updated.getName()).isEqualTo("改名後階段");
        assertThat(updated.getSortOrder()).isEqualTo(5);
    }

    @Test
    void deleteCascadesToChildCategory() {
        TaskCategory stage = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stage.getId(), categoryPreset.getId(), null));

        taskCategoryService.delete(project.getId(), stage.getId());

        assertThat(taskCategoryService.list(project.getId())).isEmpty();
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
