package com.missionboard.task;

import com.missionboard.common.ApiResponse;
import com.missionboard.project.ProjectService;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class TaskCategoryController {

    private final TaskCategoryService taskCategoryService;
    private final ProjectService projectService;
    private final UserRepository userRepository;

    @GetMapping("/api/projects/{projectId}/task-categories")
    public ApiResponse<List<TaskCategoryDto.Response>> list(@PathVariable Long projectId, Principal principal) {
        checkRead(projectId, principal);
        return ApiResponse.ok(taskCategoryService.list(projectId).stream().map(TaskCategoryDto.Response::from).toList());
    }

    @PostMapping("/api/projects/{projectId}/task-categories")
    public ApiResponse<TaskCategoryDto.Response> create(@PathVariable Long projectId,
            @RequestBody TaskCategoryDto.CreateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        return ApiResponse.ok(TaskCategoryDto.Response.from(taskCategoryService.create(projectId, req)));
    }

    @PutMapping("/api/projects/{projectId}/task-categories/{categoryId}")
    public ApiResponse<TaskCategoryDto.Response> update(@PathVariable Long projectId, @PathVariable Long categoryId,
            @RequestBody TaskCategoryDto.UpdateRequest req, Principal principal) {
        checkWrite(projectId, principal);
        return ApiResponse.ok(TaskCategoryDto.Response.from(taskCategoryService.update(projectId, categoryId, req)));
    }

    @DeleteMapping("/api/projects/{projectId}/task-categories/{categoryId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long categoryId, Principal principal) {
        checkWrite(projectId, principal);
        taskCategoryService.delete(projectId, categoryId);
        return ApiResponse.ok(null);
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
