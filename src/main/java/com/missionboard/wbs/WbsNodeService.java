package com.missionboard.wbs;

import com.missionboard.project.Project;
import com.missionboard.project.ProjectService;
import com.missionboard.user.User;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WbsNodeService {

    private final WbsNodeRepository wbsNodeRepository;
    private final WbsPresetRepository wbsPresetRepository;
    private final ProjectService projectService;
    private final com.missionboard.user.UserRepository userRepository;

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
            node.setStatus(WbsNode.Status.NOT_STARTED);
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

    // 依角色範圍已在 controller 層檢查過 canRead，這裡只負責彙總計算
    @Transactional(readOnly = true)
    public List<WbsNodeDto.Response> getTree(Long projectId) {
        List<WbsNode> allNodes = wbsNodeRepository.findByProjectId(projectId);
        Map<Long, List<WbsNode>> childrenByParent = allNodes.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));

        Map<Long, Aggregate> aggregateByNodeId = new HashMap<>();
        // 由下往上：先算所有 L2（依其 L3 子節點的儲存值），再算所有 L1（依其 L2 子節點「已彙總」的結果）
        for (WbsNode node : allNodes) {
            if (node.getLevel() == 2) {
                aggregateByNodeId.put(node.getId(), aggregateFromL3Children(node, childrenByParent));
            }
        }
        for (WbsNode node : allNodes) {
            if (node.getLevel() == 1) {
                aggregateByNodeId.put(node.getId(), aggregateFromL2Children(node, childrenByParent, aggregateByNodeId));
            }
        }

        return allNodes.stream().map(node -> toResponse(node, aggregateByNodeId)).toList();
    }

    private record Aggregate(WbsNode.Status status, LocalDate startDate, LocalDate endDate) {
    }

    private Aggregate aggregateFromL3Children(WbsNode l2Node, Map<Long, List<WbsNode>> childrenByParent) {
        List<WbsNode> children = childrenByParent.getOrDefault(l2Node.getId(), List.of());
        return aggregate(
            children.stream().map(WbsNode::getStatus).toList(),
            children.stream().map(WbsNode::getStartDate).filter(Objects::nonNull).toList(),
            children.stream().map(WbsNode::getEndDate).filter(Objects::nonNull).toList()
        );
    }

    private Aggregate aggregateFromL2Children(WbsNode l1Node, Map<Long, List<WbsNode>> childrenByParent,
            Map<Long, Aggregate> aggregateByNodeId) {
        List<WbsNode> children = childrenByParent.getOrDefault(l1Node.getId(), List.of());
        return aggregate(
            children.stream().map(c -> aggregateByNodeId.get(c.getId()).status()).toList(),
            children.stream().map(c -> aggregateByNodeId.get(c.getId()).startDate())
                .filter(Objects::nonNull).toList(),
            children.stream().map(c -> aggregateByNodeId.get(c.getId()).endDate())
                .filter(Objects::nonNull).toList()
        );
    }

    // 全部 DONE→DONE；全部 NOT_STARTED（含無子節點的空節點）→NOT_STARTED；其餘→IN_PROGRESS
    private Aggregate aggregate(List<WbsNode.Status> statuses, List<LocalDate> starts, List<LocalDate> ends) {
        WbsNode.Status status;
        if (statuses.isEmpty() || statuses.stream().allMatch(s -> s == WbsNode.Status.NOT_STARTED)) {
            status = WbsNode.Status.NOT_STARTED;
        } else if (statuses.stream().allMatch(s -> s == WbsNode.Status.DONE)) {
            status = WbsNode.Status.DONE;
        } else {
            status = WbsNode.Status.IN_PROGRESS;
        }
        LocalDate start = starts.stream().min(LocalDate::compareTo).orElse(null);
        LocalDate end = ends.stream().max(LocalDate::compareTo).orElse(null);
        return new Aggregate(status, start, end);
    }

    private WbsNodeDto.Response toResponse(WbsNode node, Map<Long, Aggregate> aggregateByNodeId) {
        Long parentId = node.getParent() != null ? node.getParent().getId() : null;
        if (node.getLevel() == 3) {
            return new WbsNodeDto.Response(
                node.getId(), parentId, node.getLevel(), node.getTitle(),
                node.getAssignee() != null ? node.getAssignee().getId() : null,
                node.getAssignee() != null ? node.getAssignee().getDisplayName() : null,
                node.getStatus() != null ? node.getStatus().name() : null,
                node.getPriority() != null ? node.getPriority().name() : null,
                node.getStartDate(), node.getEndDate(),
                node.getNotes(), node.getSortOrder()
            );
        }
        Aggregate agg = aggregateByNodeId.get(node.getId());
        return new WbsNodeDto.Response(
            node.getId(), parentId, node.getLevel(), node.getTitle(),
            null, null,
            agg.status().name(), null,
            agg.startDate(), agg.endDate(),
            node.getNotes(), node.getSortOrder()
        );
    }

    // 支援跨層級搬移：套用前先驗證整批操作都不會讓任何子孫超過三層上限，超限則整批拒絕、不部分套用
    @Transactional
    public void reorder(Long projectId, List<WbsNodeDto.ReorderItem> items) {
        List<WbsNode> allNodes = wbsNodeRepository.findByProjectId(projectId);
        Map<Long, WbsNode> nodeById = allNodes.stream()
            .collect(Collectors.toMap(WbsNode::getId, n -> n));
        Map<Long, List<WbsNode>> childrenByParent = allNodes.stream()
            .filter(n -> n.getParent() != null)
            .collect(Collectors.groupingBy(n -> n.getParent().getId()));

        for (WbsNodeDto.ReorderItem item : items) {
            if (!nodeById.containsKey(item.nodeId())) {
                throw new SecurityException("節點不屬於此專案: " + item.nodeId());
            }
            if (item.parentId() != null && !nodeById.containsKey(item.parentId())) {
                throw new SecurityException("父節點不屬於此專案: " + item.parentId());
            }
            if (item.parentId() != null && item.parentId().equals(item.nodeId())) {
                throw new IllegalArgumentException("節點不能是自己的父節點");
            }
        }

        Map<Long, Integer> deltaByNodeId = new HashMap<>();
        for (WbsNodeDto.ReorderItem item : items) {
            WbsNode node = nodeById.get(item.nodeId());
            int newLevel = item.parentId() == null ? 1 : nodeById.get(item.parentId()).getLevel() + 1;
            int subtreeDepth = maxDepth(node.getId(), childrenByParent);
            if (newLevel + subtreeDepth - 1 > 3) {
                throw new IllegalArgumentException("搬移後子樹層級將超過三層上限");
            }
            deltaByNodeId.put(item.nodeId(), newLevel - node.getLevel());
        }

        for (WbsNodeDto.ReorderItem item : items) {
            WbsNode node = nodeById.get(item.nodeId());
            WbsNode newParent = item.parentId() == null ? null : nodeById.get(item.parentId());
            node.setParent(newParent);
            node.setSortOrder(item.sortOrder());
            int delta = deltaByNodeId.get(item.nodeId());
            applyLevelShift(node, delta);
            wbsNodeRepository.save(node);
            for (WbsNode child : childrenByParent.getOrDefault(node.getId(), List.of())) {
                shiftDescendant(child, delta, childrenByParent);
            }
        }
    }

    // 回傳以 nodeId 為根的子樹最大深度（葉節點本身深度為 1）
    private int maxDepth(Long nodeId, Map<Long, List<WbsNode>> childrenByParent) {
        List<WbsNode> children = childrenByParent.getOrDefault(nodeId, List.of());
        if (children.isEmpty()) return 1;
        int max = 0;
        for (WbsNode child : children) {
            max = Math.max(max, maxDepth(child.getId(), childrenByParent));
        }
        return 1 + max;
    }

    // 套用 level 位移；若節點因此不再是 L3，清空 L3 專屬欄位以符合 DB CHECK 約束
    private void applyLevelShift(WbsNode node, int delta) {
        if (delta == 0) return;
        node.setLevel((short) (node.getLevel() + delta));
        if (node.getLevel() != 3) {
            node.setAssignee(null);
            node.setStatus(null);
            node.setPriority(null);
            node.setStartDate(null);
            node.setEndDate(null);
        }
    }

    private void shiftDescendant(WbsNode node, int delta, Map<Long, List<WbsNode>> childrenByParent) {
        applyLevelShift(node, delta);
        wbsNodeRepository.save(node);
        for (WbsNode child : childrenByParent.getOrDefault(node.getId(), List.of())) {
            shiftDescendant(child, delta, childrenByParent);
        }
    }

    // 僅 L3 可設定狀態；非法字串由 WbsNode.Status.valueOf 自動拋 IllegalArgumentException（GlobalExceptionHandler 已處理）
    @Transactional
    public WbsNode updateStatus(Long projectId, Long nodeId, String status) {
        WbsNode node = getNodeInProject(projectId, nodeId);
        if (node.getLevel() != 3) {
            throw new IllegalArgumentException("僅細項（L3）可設定狀態");
        }
        node.setStatus(WbsNode.Status.valueOf(status));
        return wbsNodeRepository.save(node);
    }

    // 僅 L3 可指派；assigneeId 為 null 表示取消指派；新指派對象必須是專案成員
    @Transactional
    public WbsNode updateAssignee(Long projectId, Long nodeId, Long assigneeId) {
        WbsNode node = getNodeInProject(projectId, nodeId);
        if (node.getLevel() != 3) {
            throw new IllegalArgumentException("僅細項（L3）可指派");
        }
        if (assigneeId == null) {
            node.setAssignee(null);
        } else {
            if (!projectService.isMember(projectId, assigneeId)) {
                throw new IllegalArgumentException("指派對象必須是專案成員");
            }
            User assignee = userRepository.findById(assigneeId)
                .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
            node.setAssignee(assignee);
        }
        return wbsNodeRepository.save(node);
    }

    // 建專案 L1 骨架：不傳 stagePresetIds 則預設該專案科別可見的全部已啟用 STAGE；僅空專案可初始化
    @Transactional
    public void initStages(Long projectId, List<Long> stagePresetIds) {
        Project project = projectService.getById(projectId);
        if (wbsNodeRepository.existsByProjectId(projectId)) {
            throw new IllegalArgumentException("專案已有節點，無法重複初始化");
        }

        List<WbsPreset> presets;
        if (stagePresetIds == null || stagePresetIds.isEmpty()) {
            presets = wbsPresetRepository.findVisiblePresets(WbsPreset.Type.STAGE, project.getSection().getId());
        } else {
            presets = wbsPresetRepository.findAllById(stagePresetIds);
            for (WbsPreset preset : presets) {
                if (preset.getType() != WbsPreset.Type.STAGE) {
                    throw new IllegalArgumentException("選單項目型別不符: " + preset.getId());
                }
                if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
                    throw new IllegalArgumentException("選單項目不屬於此專案科別: " + preset.getId());
                }
            }
        }

        int sortOrder = 0;
        for (WbsPreset preset : presets) {
            WbsNode node = new WbsNode();
            node.setProject(project);
            node.setLevel((short) 1);
            node.setTitle(preset.getName());
            node.setSortOrder(sortOrder++);
            wbsNodeRepository.save(node);
        }
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
