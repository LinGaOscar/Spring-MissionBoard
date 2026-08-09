package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
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
class TaskCategoryPresetRepositoryTest {

    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesGlobalPresetWithNullSection() {
        TaskCategoryPreset preset = new TaskCategoryPreset();
        preset.setType(TaskCategoryPreset.Type.STAGE);
        preset.setName("SIT");
        preset.setSortOrder(1);
        TaskCategoryPreset saved = taskCategoryPresetRepository.save(preset);

        assertThat(saved.getSection()).isNull();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getType()).isEqualTo(TaskCategoryPreset.Type.STAGE);
    }

    @Test
    void findsVisiblePresetsMergesGlobalAndSectionSpecific() {
        Department sectionA = new Department();
        sectionA.setName("系統科A");
        departmentRepository.save(sectionA);

        TaskCategoryPreset global = new TaskCategoryPreset();
        global.setType(TaskCategoryPreset.Type.STAGE);
        global.setName("SIT");
        global.setSortOrder(1);
        taskCategoryPresetRepository.save(global);

        TaskCategoryPreset sectionSpecific = new TaskCategoryPreset();
        sectionSpecific.setType(TaskCategoryPreset.Type.STAGE);
        sectionSpecific.setName("系統科專用階段");
        sectionSpecific.setSortOrder(2);
        sectionSpecific.setSection(sectionA);
        taskCategoryPresetRepository.save(sectionSpecific);

        TaskCategoryPreset disabled = new TaskCategoryPreset();
        disabled.setType(TaskCategoryPreset.Type.STAGE);
        disabled.setName("已停用");
        disabled.setSortOrder(3);
        disabled.setEnabled(false);
        taskCategoryPresetRepository.save(disabled);

        List<TaskCategoryPreset> visible = taskCategoryPresetRepository
            .findVisiblePresets(TaskCategoryPreset.Type.STAGE, sectionA.getId());

        assertThat(visible).extracting(TaskCategoryPreset::getName)
            .containsExactly("SIT", "系統科專用階段");
    }
}
