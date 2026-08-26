package com.missionboard.task;

import com.missionboard.project.Project;
import com.missionboard.project.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskCategoryService {

    private final TaskCategoryRepository taskCategoryRepository;
    private final TaskCategoryPresetRepository taskCategoryPresetRepository;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public List<TaskCategory> list(Long projectId) {
        return taskCategoryRepository.findByProjectId(projectId);
    }

    // 建立類別：presetId 從選單快照名稱，或直接用 name 建立；深度上限兩層在此強制（service 層驗證，不用 DB CHECK）
    @Transactional
    public TaskCategory create(Long projectId, TaskCategoryDto.CreateRequest req) {
        if (req.presetId() == null && (req.name() == null || req.name().isBlank())) {
            throw new IllegalArgumentException("選單項目或名稱擇一必填");
        }
        Project project = projectService.getById(projectId);
        TaskCategory parent = null;
        if (req.parentCategoryId() != null) {
            parent = getCategoryInProject(projectId, req.parentCategoryId());
            if (parent.getParentCategory() != null) {
                throw new IllegalArgumentException("已達第二層，無法在類別下新增子類別");
            }
        }

        String name;
        if (req.presetId() != null) {
            TaskCategoryPreset preset = taskCategoryPresetRepository.findById(req.presetId())
                .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
            TaskCategoryPreset.Type expectedType = parent == null
                ? TaskCategoryPreset.Type.STAGE : TaskCategoryPreset.Type.CATEGORY;
            if (preset.getType() != expectedType) {
                throw new IllegalArgumentException("選單項目型別不符");
            }
            if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
                throw new IllegalArgumentException("選單項目不屬於此專案科別");
            }
            name = preset.getName();
        } else {
            name = req.name().trim();
        }

        TaskCategory category = new TaskCategory();
        category.setProject(project);
        category.setParentCategory(parent);
        category.setName(name);
        category.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        return taskCategoryRepository.save(category);
    }

    // 改父節點：只有子類別能改（大項的 parentCategory 永遠是 null，不接受這個操作），
    // 新的父節點必須是大項（不能把子類別掛到另一個子類別底下，維持兩層上限）
    @Transactional
    public TaskCategory update(Long projectId, Long categoryId, TaskCategoryDto.UpdateRequest req) {
        TaskCategory category = getCategoryInProject(projectId, categoryId);
        if (req.name() != null) category.setName(req.name());
        if (req.sortOrder() != null) category.setSortOrder(req.sortOrder());
        if (req.parentCategoryId() != null) {
            if (category.getParentCategory() == null) {
                throw new IllegalArgumentException("大項不能改變父節點");
            }
            TaskCategory newParent = getCategoryInProject(projectId, req.parentCategoryId());
            if (newParent.getParentCategory() != null) {
                throw new IllegalArgumentException("新的父節點必須是大項");
            }
            category.setParentCategory(newParent);
        }
        return taskCategoryRepository.save(category);
    }

    @Transactional
    public void delete(Long projectId, Long categoryId) {
        TaskCategory category = getCategoryInProject(projectId, categoryId);
        var children = taskCategoryRepository.findByParentCategoryId(categoryId);
        for (TaskCategory child : children) {
            taskCategoryRepository.delete(child);
        }
        taskCategoryRepository.delete(category);
    }

    // 統一的 IDOR 防護：確認類別存在且屬於路徑上的專案。Task 5 的 TaskService 建立/更新任務歸類時也呼叫此方法。
    public TaskCategory getCategoryInProject(Long projectId, Long categoryId) {
        TaskCategory category = taskCategoryRepository.findById(categoryId)
            .orElseThrow(() -> new EntityNotFoundException("類別不存在"));
        if (!category.getProject().getId().equals(projectId)) {
            throw new SecurityException("類別不屬於此專案");
        }
        return category;
    }
}
