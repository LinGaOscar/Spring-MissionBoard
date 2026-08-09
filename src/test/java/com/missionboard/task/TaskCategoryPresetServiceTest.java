package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
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
class TaskCategoryPresetServiceTest {

    @Autowired
    private TaskCategoryPresetService taskCategoryPresetService;
    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private UserRepository userRepository;

    private Department sectionA;
    private Department sectionB;
    private User chiefA;
    private User chiefB;
    private User leaderA;

    @BeforeEach
    void setUp() {
        sectionA = newDept("系統科A");
        sectionB = newDept("系統科B");
        chiefA = newUser("chiefA", User.Role.SECTION_CHIEF, sectionA);
        chiefB = newUser("chiefB", User.Role.SECTION_CHIEF, sectionB);
        leaderA = newUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
    }

    @Test
    void sectionChiefCreatesPresetScopedToOwnSection() {
        TaskCategoryPreset preset = taskCategoryPresetService.create(TaskCategoryPreset.Type.CATEGORY, "測試類別", 1, chiefA);
        assertThat(preset.getSection().getId()).isEqualTo(sectionA.getId());
    }

    @Test
    void nonChiefCannotCreatePreset() {
        assertThatThrownBy(() -> taskCategoryPresetService.create(TaskCategoryPreset.Type.CATEGORY, "測試類別", 1, leaderA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCannotUpdateOtherSectionPreset() {
        TaskCategoryPreset preset = taskCategoryPresetService.create(TaskCategoryPreset.Type.CATEGORY, "A科類別", 1, chiefA);
        assertThatThrownBy(() -> taskCategoryPresetService.update(preset.getId(), "改名", null, null, chiefB))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCannotUpdateGlobalPreset() {
        TaskCategoryPreset global = new TaskCategoryPreset();
        global.setType(TaskCategoryPreset.Type.STAGE);
        global.setName("全域階段");
        global.setSortOrder(1);
        taskCategoryPresetRepository.save(global);
        assertThatThrownBy(() -> taskCategoryPresetService.update(global.getId(), "改名", null, null, chiefA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCanDeleteOwnSectionPreset() {
        TaskCategoryPreset preset = taskCategoryPresetService.create(TaskCategoryPreset.Type.CATEGORY, "待刪類別", 1, chiefA);
        taskCategoryPresetService.delete(preset.getId(), chiefA);
        assertThat(taskCategoryPresetRepository.findById(preset.getId())).isEmpty();
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return departmentRepository.save(d);
    }

    private User newUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("x");
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.save(u);
    }
}
