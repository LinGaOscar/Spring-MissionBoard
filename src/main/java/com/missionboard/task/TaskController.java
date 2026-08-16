package com.missionboard.task;

import com.missionboard.common.ApiResponse;
import com.missionboard.project.ProjectService;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskCategoryService taskCategoryService;
    private final TaskExportService taskExportService;
    private final ProjectService projectService;
    private final UserRepository userRepository;

    @GetMapping("/api/projects/{projectId}/tasks")
    public ApiResponse<List<TaskDto.Response>> list(@PathVariable Long projectId, Principal principal) {
        checkRead(projectId, principal);
        return ApiResponse.ok(taskService.list(projectId).stream().map(this::toResponse).toList());
    }

    @PostMapping("/api/projects/{projectId}/tasks")
    public ApiResponse<TaskDto.Response> create(@PathVariable Long projectId,
            @RequestBody TaskDto.CreateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        return ApiResponse.ok(toResponse(taskService.createTask(projectId, req)));
    }

    @PutMapping("/api/projects/{projectId}/tasks/{taskId}")
    public ApiResponse<TaskDto.Response> update(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody TaskDto.UpdateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        return ApiResponse.ok(toResponse(taskService.updateTask(projectId, taskId, req)));
    }

    @DeleteMapping("/api/projects/{projectId}/tasks/{taskId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long taskId, Principal principal) {
        checkWrite(projectId, principal);
        taskService.deleteTask(projectId, taskId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/tasks/{taskId}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody TaskDto.StatusRequest req, Principal principal) {
        checkWrite(projectId, principal);
        taskService.updateStatus(projectId, taskId, req.status());
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/tasks/{taskId}/assignee")
    public ApiResponse<Void> updateAssignee(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody TaskDto.AssigneeRequest req, Principal principal) {
        checkWrite(projectId, principal);
        taskService.updateAssignee(projectId, taskId, req.assigneeId());
        return ApiResponse.ok(null);
    }

    @PatchMapping("/api/projects/{projectId}/tasks/{taskId}/move")
    public ApiResponse<Void> move(@PathVariable Long projectId, @PathVariable Long taskId,
            @RequestBody TaskDto.MoveRequest req, Principal principal) {
        checkWrite(projectId, principal);
        taskService.moveTask(projectId, taskId, req.status(), req.sortOrder());
        return ApiResponse.ok(null);
    }

    // 匯出是唯讀操作：凡可檢視此專案者（含封存、跨科唯讀角色）皆可使用，權限比照 list() 用 canRead
    @GetMapping("/api/projects/{projectId}/export.xlsx")
    public ResponseEntity<byte[]> export(@PathVariable Long projectId, Principal principal) {
        checkRead(projectId, principal);
        byte[] content = taskExportService.toXlsx(
            taskService.list(projectId), taskCategoryService.list(projectId));
        String filename = projectService.getById(projectId).getName() + "_任務清單.xlsx";
        return download(content, filename,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    // 檔名含中文，用 RFC 5987 filename* 編碼，避免部分瀏覽器/下載工具把中文檔名截斷或亂碼
    private ResponseEntity<byte[]> download(byte[] body, String filename, String contentType) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
            .contentType(MediaType.parseMediaType(contentType))
            .body(body);
    }

    private TaskDto.Response toResponse(Task task) {
        return new TaskDto.Response(
            task.getId(),
            task.getCategory() != null ? task.getCategory().getId() : null,
            task.getTitle(), task.getDescription(),
            task.getAssignee() != null ? task.getAssignee().getId() : null,
            task.getAssignee() != null ? task.getAssignee().getDisplayName() : null,
            task.getStatus().name(),
            task.getPriority() != null ? task.getPriority().name() : null,
            task.getStartDate(), task.getDueDate(), task.getSortOrder()
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
