package com.wbsflow.wbs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WbsPresetRepositoryTest {

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

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
}
