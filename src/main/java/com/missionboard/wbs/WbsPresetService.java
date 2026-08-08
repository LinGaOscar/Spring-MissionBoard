package com.missionboard.wbs;

import com.missionboard.user.User;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WbsPresetService {

    private final WbsPresetRepository wbsPresetRepository;

    public List<WbsPreset> list(WbsPreset.Type type, Long sectionId) {
        return wbsPresetRepository.findVisiblePresets(type, sectionId);
    }

    // 只有科長可管理選單，且新建項目一律歸屬建立者自己的科別
    @Transactional
    public WbsPreset create(WbsPreset.Type type, String name, int sortOrder, User actor) {
        requireSectionChief(actor);
        WbsPreset preset = new WbsPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(sortOrder);
        preset.setSection(actor.getDepartment());
        return wbsPresetRepository.save(preset);
    }

    @Transactional
    public WbsPreset update(Long presetId, String name, Integer sortOrder, Boolean enabled, User actor) {
        WbsPreset preset = getOwnedPreset(presetId, actor);
        if (name != null) preset.setName(name);
        if (sortOrder != null) preset.setSortOrder(sortOrder);
        if (enabled != null) preset.setEnabled(enabled);
        return wbsPresetRepository.save(preset);
    }

    @Transactional
    public void delete(Long presetId, User actor) {
        WbsPreset preset = getOwnedPreset(presetId, actor);
        wbsPresetRepository.delete(preset);
    }

    // 全域預設（section 為 null）任何角色皆不可修改；自訂項目僅同科科長可管理
    private WbsPreset getOwnedPreset(Long presetId, User actor) {
        requireSectionChief(actor);
        WbsPreset preset = wbsPresetRepository.findById(presetId)
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
