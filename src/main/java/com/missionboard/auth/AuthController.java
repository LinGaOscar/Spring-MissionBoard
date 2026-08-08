package com.missionboard.auth;

import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    // 占位首頁：子專案 C 建好專案列表後，SecurityConfig 的 defaultSuccessUrl 會改指向 /projects
    @GetMapping("/home")
    public String home(Principal principal, Model model) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        model.addAttribute("displayName", user.getDisplayName());
        return "home";
    }
}
