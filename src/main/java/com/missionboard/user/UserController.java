package com.missionboard.user;

import com.missionboard.common.ApiResponse;
import com.missionboard.task.DashboardDto;
import com.missionboard.task.DashboardService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final DashboardService dashboardService;

    @GetMapping("/api/users/me")
    public ApiResponse<UserSummary> me(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        return ApiResponse.ok(UserSummary.from(user));
    }

    @GetMapping("/api/users/me/dashboard")
    public ApiResponse<DashboardDto.Response> dashboard(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        return ApiResponse.ok(dashboardService.getDashboard(user));
    }

    // 供成員新增等下拉選單使用；帶 departmentId 只回同科人員，不帶則回全部
    @GetMapping("/api/users")
    public ApiResponse<List<UserSummary>> list(@RequestParam(required = false) Long departmentId) {
        List<User> users = departmentId != null
            ? userRepository.findByDepartmentId(departmentId)
            : userRepository.findAll();
        return ApiResponse.ok(users.stream().map(UserSummary::from).toList());
    }

    public record UserSummary(Long id, String username, String displayName, String role) {
        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name());
        }
    }
}
