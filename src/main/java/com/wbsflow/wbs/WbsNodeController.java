package com.wbsflow.wbs;

import com.wbsflow.common.ApiResponse;
import com.wbsflow.project.ProjectService;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class WbsNodeController {

    private final WbsNodeService wbsNodeService;
    private final ProjectService projectService;
    private final UserRepository userRepository;

    @GetMapping("/api/projects/{projectId}/nodes")
    public ApiResponse<List<WbsNodeDto.Response>> list(@PathVariable Long projectId, Principal principal) {
        checkRead(projectId, principal);
        return ApiResponse.ok(wbsNodeService.getTree(projectId));
    }

    @PostMapping("/api/projects/{projectId}/nodes")
    public ApiResponse<WbsNodeDto.Response> create(@PathVariable Long projectId,
            @RequestBody WbsNodeDto.CreateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        WbsNode node = wbsNodeService.createNode(projectId, req);
        return ApiResponse.ok(toNewNodeResponse(node));
    }

    @PostMapping("/api/projects/{projectId}/nodes/init")
    public ApiResponse<Void> init(@PathVariable Long projectId,
            @RequestBody(required = false) WbsNodeDto.InitRequest req, Principal principal) {
        checkWrite(projectId, principal);
        List<Long> stagePresetIds = req != null ? req.stagePresetIds() : null;
        wbsNodeService.initStages(projectId, stagePresetIds);
        return ApiResponse.ok(null);
    }

    @PutMapping("/api/projects/{projectId}/nodes/{nodeId}")
    public ApiResponse<Void> update(@PathVariable Long projectId, @PathVariable Long nodeId,
            @RequestBody WbsNodeDto.UpdateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.updateNode(projectId, nodeId, req);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/api/projects/{projectId}/nodes/{nodeId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long nodeId, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.deleteNode(projectId, nodeId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/nodes/reorder")
    public ApiResponse<Void> reorder(@PathVariable Long projectId,
            @RequestBody List<WbsNodeDto.ReorderItem> items, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.reorder(projectId, items);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/nodes/{nodeId}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long projectId, @PathVariable Long nodeId,
            @RequestBody WbsNodeDto.StatusRequest req, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.updateStatus(projectId, nodeId, req.status());
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/nodes/{nodeId}/assignee")
    public ApiResponse<Void> updateAssignee(@PathVariable Long projectId, @PathVariable Long nodeId,
            @RequestBody WbsNodeDto.AssigneeRequest req, Principal principal) {
        checkWrite(projectId, principal);
        wbsNodeService.updateAssignee(projectId, nodeId, req.assigneeId());
        return ApiResponse.ok(null);
    }

    // 新建節點必無子節點，L1/L2 的彙總狀態可直接視為 NOT_STARTED、日期為 null，不需查詢子節點
    private WbsNodeDto.Response toNewNodeResponse(WbsNode node) {
        Long parentId = node.getParent() != null ? node.getParent().getId() : null;
        String status = node.getLevel() == 3
            ? (node.getStatus() != null ? node.getStatus().name() : null)
            : WbsNode.Status.NOT_STARTED.name();
        return new WbsNodeDto.Response(
            node.getId(), parentId, node.getLevel(), node.getTitle(),
            null, null, status, null, null, null,
            node.getNotes(), node.getSortOrder()
        );
    }

    private void checkRead(Long projectId, Principal principal) {
        if (!projectService.canRead(projectId, currentUser(principal))) {
            throw new SecurityException("無存取權限");
        }
    }

    private void checkWrite(Long projectId, Principal principal) {
        if (!projectService.canWrite(projectId, currentUser(principal))) {
            throw new SecurityException("無編輯權限");
        }
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
