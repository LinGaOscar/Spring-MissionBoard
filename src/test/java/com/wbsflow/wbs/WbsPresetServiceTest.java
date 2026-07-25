package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
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
class WbsPresetServiceTest {

    @Autowired
    private WbsPresetService wbsPresetService;

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

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
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));
        chiefA = userRepository.save(newUser("chiefA", User.Role.SECTION_CHIEF, sectionA));
        chiefB = userRepository.save(newUser("chiefB", User.Role.SECTION_CHIEF, sectionB));
        leaderA = userRepository.save(newUser("leaderA", User.Role.PROJECT_LEADER, sectionA));
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
    void sectionChiefCreatesPresetScopedToOwnSection() {
        WbsPreset created = wbsPresetService.create(WbsPreset.Type.STAGE, "自訂階段", 1, chiefA);

        assertThat(created.getSection().getId()).isEqualTo(sectionA.getId());
        assertThat(created.getType()).isEqualTo(WbsPreset.Type.STAGE);
    }

    @Test
    void nonChiefCannotCreatePreset() {
        assertThatThrownBy(() -> wbsPresetService.create(WbsPreset.Type.STAGE, "自訂階段", 1, leaderA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCanUpdateOwnSectionPreset() {
        WbsPreset preset = wbsPresetService.create(WbsPreset.Type.STAGE, "自訂階段", 1, chiefA);

        WbsPreset updated = wbsPresetService.update(preset.getId(), "改過的名稱", null, null, chiefA);

        assertThat(updated.getName()).isEqualTo("改過的名稱");
    }

    @Test
    void sectionChiefCannotUpdateOtherSectionPreset() {
        WbsPreset presetB = wbsPresetService.create(WbsPreset.Type.STAGE, "B科自訂", 1, chiefB);

        assertThatThrownBy(() -> wbsPresetService.update(presetB.getId(), "想改別科", null, null, chiefA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCannotUpdateGlobalPreset() {
        WbsPreset global = new WbsPreset();
        global.setType(WbsPreset.Type.STAGE);
        global.setName("全域預設");
        global.setSortOrder(1);
        global = wbsPresetRepository.save(global);
        Long globalId = global.getId();

        assertThatThrownBy(() -> wbsPresetService.update(globalId, "想改全域", null, null, chiefA))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void sectionChiefCanDeleteOwnSectionPreset() {
        WbsPreset preset = wbsPresetService.create(WbsPreset.Type.CATEGORY, "自訂類別", 1, chiefA);

        wbsPresetService.delete(preset.getId(), chiefA);

        assertThat(wbsPresetRepository.findById(preset.getId())).isEmpty();
    }
}
