package com.missionboard.wbs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WbsPresetRepository extends JpaRepository<WbsPreset, Long> {

    // 全域（section IS NULL）＋指定科別自訂合併，僅回傳啟用中的項目，依 sortOrder 排序
    @Query("SELECT p FROM WbsPreset p WHERE p.type = :type AND p.enabled = true "
        + "AND (p.section IS NULL OR p.section.id = :sectionId) ORDER BY p.sortOrder")
    List<WbsPreset> findVisiblePresets(@Param("type") WbsPreset.Type type, @Param("sectionId") Long sectionId);
}
