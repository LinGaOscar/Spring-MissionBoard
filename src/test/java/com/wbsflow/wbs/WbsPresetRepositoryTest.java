package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
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
class WbsPresetRepositoryTest {

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesGlobalPresetWithNullSection() {
        WbsPreset stage = new WbsPreset();
        stage.setType(WbsPreset.Type.STAGE);
        stage.setName("SIT");
        stage.setSortOrder(1);
        WbsPreset saved = wbsPresetRepository.save(stage);

        assertThat(saved.getSection()).isNull();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getType()).isEqualTo(WbsPreset.Type.STAGE);
    }

    @Test
    void findsVisiblePresetsMergesGlobalAndSectionSpecific() {
        Department sectionA = new Department();
        sectionA.setName("系統科");
        sectionA = departmentRepository.save(sectionA);

        Department sectionB = new Department();
        sectionB.setName("網路科");
        sectionB = departmentRepository.save(sectionB);

        WbsPreset globalStage = new WbsPreset();
        globalStage.setType(WbsPreset.Type.STAGE);
        globalStage.setName("SIT");
        globalStage.setSortOrder(1);
        wbsPresetRepository.save(globalStage);

        WbsPreset sectionAStage = new WbsPreset();
        sectionAStage.setType(WbsPreset.Type.STAGE);
        sectionAStage.setName("系統科專用階段");
        sectionAStage.setSortOrder(2);
        sectionAStage.setSection(sectionA);
        wbsPresetRepository.save(sectionAStage);

        WbsPreset sectionBStage = new WbsPreset();
        sectionBStage.setType(WbsPreset.Type.STAGE);
        sectionBStage.setName("網路科專用階段");
        sectionBStage.setSortOrder(3);
        sectionBStage.setSection(sectionB);
        wbsPresetRepository.save(sectionBStage);

        WbsPreset disabledGlobal = new WbsPreset();
        disabledGlobal.setType(WbsPreset.Type.STAGE);
        disabledGlobal.setName("已停用");
        disabledGlobal.setSortOrder(0);
        disabledGlobal.setEnabled(false);
        wbsPresetRepository.save(disabledGlobal);

        List<WbsPreset> visible = wbsPresetRepository.findVisiblePresets(WbsPreset.Type.STAGE, sectionA.getId());

        assertThat(visible).extracting(WbsPreset::getName)
            .containsExactly("SIT", "系統科專用階段");
    }
}
