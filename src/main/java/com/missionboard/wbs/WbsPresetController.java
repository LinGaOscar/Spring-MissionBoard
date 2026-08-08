package com.missionboard.wbs;

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
public class WbsPresetController {

    private final WbsPresetService wbsPresetService;
    private final UserRepository userRepository;

    // 不傳 sectionId 時預設用呼叫者自己的部門，方便前端下拉選單直接查詢
    @GetMapping("/api/presets")
    public ApiResponse<List<WbsPresetDto.Response>> list(
            @RequestParam WbsPreset.Type type,
            @RequestParam(required = false) Long sectionId,
            Principal principal) {
        User user = currentUser(principal);
        Long effectiveSectionId = sectionId != null ? sectionId
            : (user.getDepartment() != null ? user.getDepartment().getId() : null);
        List<WbsPresetDto.Response> result = wbsPresetService.list(type, effectiveSectionId)
            .stream().map(WbsPresetDto.Response::from).toList();
        return ApiResponse.ok(result);
    }

    @PostMapping("/api/presets")
    public ApiResponse<WbsPresetDto.Response> create(
            @RequestBody WbsPresetDto.CreateRequest req, Principal principal) {
        User user = currentUser(principal);
        WbsPreset preset = wbsPresetService.create(req.type(), req.name(), req.sortOrder(), user);
        return ApiResponse.ok(WbsPresetDto.Response.from(preset));
    }

    @PutMapping("/api/presets/{id}")
    public ApiResponse<WbsPresetDto.Response> update(@PathVariable Long id,
            @RequestBody WbsPresetDto.UpdateRequest req, Principal principal) {
        User user = currentUser(principal);
        WbsPreset preset = wbsPresetService.update(id, req.name(), req.sortOrder(), req.enabled(), user);
        return ApiResponse.ok(WbsPresetDto.Response.from(preset));
    }

    @DeleteMapping("/api/presets/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Principal principal) {
        User user = currentUser(principal);
        wbsPresetService.delete(id, user);
        return ApiResponse.ok(null);
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    }
}
