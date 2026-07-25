package com.wbsflow.wbs;

import com.wbsflow.project.Project;
import com.wbsflow.project.ProjectService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WbsNodeService {

    private final WbsNodeRepository wbsNodeRepository;
    private final WbsPresetRepository wbsPresetRepository;
    private final ProjectService projectService;

    // 建立節點：L1/L2 需選單項目（存文字快照），L3 為自由文字；層級由父節點推算，不接受前端指定
    @Transactional
    public WbsNode createNode(Long projectId, WbsNodeDto.CreateRequest req) {
        Project project = projectService.getById(projectId);
        WbsNode parent = null;
        short level = 1;
        if (req.parentId() != null) {
            parent = getNodeInProject(projectId, req.parentId());
            if (parent.getLevel() == 3) {
                throw new IllegalArgumentException("已達第三層，無法在細項下新增子節點");
            }
            level = (short) (parent.getLevel() + 1);
        }

        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setParent(parent);
        node.setLevel(level);

        if (level == 3) {
            if (req.title() == null || req.title().isBlank()) {
                throw new IllegalArgumentException("細項需要標題");
            }
            node.setTitle(req.title());
        } else {
            if (req.presetId() == null) {
                throw new IllegalArgumentException("階段/類別需要選擇選單項目");
            }
            WbsPreset preset = wbsPresetRepository.findById(req.presetId())
                .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
            WbsPreset.Type expectedType = level == 1 ? WbsPreset.Type.STAGE : WbsPreset.Type.CATEGORY;
            if (preset.getType() != expectedType) {
                throw new IllegalArgumentException("選單項目型別不符");
            }
            if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
                throw new IllegalArgumentException("選單項目不屬於此專案科別");
            }
            node.setTitle(preset.getName());
        }

        node.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        return wbsNodeRepository.save(node);
    }

    // title/notes 任何層級可改；priority/dates 僅 L3，非 L3 傳值直接拒絕（避免觸發 DB CHECK 約束產生不友善錯誤）
    @Transactional
    public WbsNode updateNode(Long projectId, Long nodeId, WbsNodeDto.UpdateRequest req) {
        WbsNode node = getNodeInProject(projectId, nodeId);

        if (req.title() != null) node.setTitle(req.title());
        if (req.notes() != null) node.setNotes(req.notes());

        boolean hasL3OnlyFields = req.priority() != null || req.startDate() != null || req.endDate() != null;
        if (hasL3OnlyFields && node.getLevel() != 3) {
            throw new IllegalArgumentException("僅細項（L3）可設定優先度或起迄日");
        }
        if (req.priority() != null) node.setPriority(WbsNode.Priority.valueOf(req.priority()));
        if (req.startDate() != null) node.setStartDate(req.startDate());
        if (req.endDate() != null) node.setEndDate(req.endDate());

        return wbsNodeRepository.save(node);
    }

    // 先遞迴刪子節點再刪自己：parent_id 雖有 DB 層 ON DELETE CASCADE（見 sql/01_ddl.sql）作為最後防線，
    // 但那是資料庫直接砍列、Hibernate 一級快取不會知情，同交易內若接著查詢子節點會讀到已刪除的舊快取；
    // 逐一經 JPA 刪除才能讓子節點同步從持久化context 移除，查詢立即反映真實狀態
    @Transactional
    public void deleteNode(Long projectId, Long nodeId) {
        WbsNode node = getNodeInProject(projectId, nodeId);
        deleteRecursively(node);
    }

    private void deleteRecursively(WbsNode node) {
        for (WbsNode child : wbsNodeRepository.findByParentId(node.getId())) {
            deleteRecursively(child);
        }
        wbsNodeRepository.delete(node);
    }

    // 統一的 IDOR 防護：確認節點存在且屬於路徑上的專案
    private WbsNode getNodeInProject(Long projectId, Long nodeId) {
        WbsNode node = wbsNodeRepository.findById(nodeId)
            .orElseThrow(() -> new EntityNotFoundException("節點不存在"));
        if (!node.getProject().getId().equals(projectId)) {
            throw new SecurityException("節點不屬於此專案");
        }
        return node;
    }
}
