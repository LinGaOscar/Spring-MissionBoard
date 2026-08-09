package com.missionboard.task;

import com.missionboard.common.ApiResponse;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class TaskCategoryPresetController {

    private final TaskCategoryPresetService taskCategoryPresetService;
    private final UserRepository userRepository;

    // 不傳 sectionId 時預設用呼叫者自己的部門，方便前端下拉選單直接查詢
    @GetMapping("/api/task-category-presets")
    public ApiResponse<List<TaskCategoryPresetDto.Response>> list(
            @RequestParam TaskCategoryPreset.Type type,
            @RequestParam(required = false) Long sectionId,
            Principal principal) {
        User user = currentUser(principal);
        Long effectiveSectionId = sectionId != null ? sectionId
            : (user.getDepartment() != null ? user.getDepartment().getId() : null);
        List<TaskCategoryPresetDto.Response> result = taskCategoryPresetService.list(type, effectiveSectionId)
            .stream().map(TaskCategoryPresetDto.Response::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/task-category-presets")
    public ApiResponse<TaskCategoryPresetDto.Response> create(
            @RequestBody TaskCategoryPresetDto.CreateRequest req, Principal principal) {
        User user = currentUser(principal);
        TaskCategoryPreset preset = taskCategoryPresetService.create(req.type(), req.name(), req.sortOrder(), user);
        return ApiResponse.ok(TaskCategoryPresetDto.Response.from(preset));
    }

    @PutMapping("/api/task-category-presets/{id}")
    public ApiResponse<TaskCategoryPresetDto.Response> update(@PathVariable Long id,
            @RequestBody TaskCategoryPresetDto.UpdateRequest req, Principal principal) {
        User user = currentUser(principal);
        TaskCategoryPreset preset = taskCategoryPresetService.update(id, req.name(), req.sortOrder(), req.enabled(), user);
        return ApiResponse.ok(TaskCategoryPresetDto.Response.from(preset));
    }

    @DeleteMapping("/api/task-category-presets/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        taskCategoryPresetService.delete(id, user);
        return ApiResponse.ok(null);
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
