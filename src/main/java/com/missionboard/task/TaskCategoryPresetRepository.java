package com.missionboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskCategoryPresetRepository extends JpaRepository<TaskCategoryPreset, Long> {

    // 全域（section IS NULL）＋指定科別自訂合併，僅回傳啟用中的項目，依 sortOrder 排序
    @Query("SELECT p FROM TaskCategoryPreset p WHERE p.type = :type AND p.enabled = true "
        + "AND (p.section IS NULL OR p.section.id = :sectionId) ORDER BY p.sortOrder")
    List<TaskCategoryPreset> findVisiblePresets(@Param("type") TaskCategoryPreset.Type type, @Param("sectionId") Long sectionId);
}
