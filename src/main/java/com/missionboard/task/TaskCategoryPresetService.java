package com.missionboard.task;

import com.missionboard.user.User;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskCategoryPresetService {

    private final TaskCategoryPresetRepository taskCategoryPresetRepository;

    public List<TaskCategoryPreset> list(TaskCategoryPreset.Type type, Long sectionId) {
        return taskCategoryPresetRepository.findVisiblePresets(type, sectionId);
    }

    // 只有科長可管理選單，且新建項目一律歸屬建立者自己的科別
    @Transactional
    public TaskCategoryPreset create(TaskCategoryPreset.Type type, String name, int sortOrder, User actor) {
        requireSectionChief(actor);
        TaskCategoryPreset preset = new TaskCategoryPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(sortOrder);
        preset.setSection(actor.getDepartment());
        return taskCategoryPresetRepository.save(preset);
    }

    @Transactional
    public TaskCategoryPreset update(Long presetId, String name, Integer sortOrder, Boolean enabled, User actor) {
        TaskCategoryPreset preset = getOwnedPreset(presetId, actor);
        if (name != null) preset.setName(name);
        if (sortOrder != null) preset.setSortOrder(sortOrder);
        if (enabled != null) preset.setEnabled(enabled);
        return taskCategoryPresetRepository.save(preset);
    }

    @Transactional
    public void delete(Long presetId, User actor) {
        TaskCategoryPreset preset = getOwnedPreset(presetId, actor);
        taskCategoryPresetRepository.delete(preset);
    }

    // 全域預設（section 為 null）任何角色皆不可修改；自訂項目僅同科科長可管理
    private TaskCategoryPreset getOwnedPreset(Long presetId, User actor) {
        requireSectionChief(actor);
        TaskCategoryPreset preset = taskCategoryPresetRepository.findById(presetId)
            .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
        if (preset.getSection() == null || !preset.getSection().getId().equals(actor.getDepartment().getId())) {
            throw new SecurityException("只能管理自己科別的選單項目");
        }
        return preset;
    }

    private void requireSectionChief(User actor) {
        if (actor.getRole() != User.Role.SECTION_CHIEF) {
            throw new SecurityException("只有科長可管理選單");
        }
    }
}
