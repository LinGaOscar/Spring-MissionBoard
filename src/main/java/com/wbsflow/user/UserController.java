package com.wbsflow.user;

import com.wbsflow.common.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/api/users/me")
    public ApiResponse<UserMeResponse> me(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        return ApiResponse.ok(new UserMeResponse(
            user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name()
        ));
    }

    public record UserMeResponse(Long id, String username, String displayName, String role) {
    }
}
