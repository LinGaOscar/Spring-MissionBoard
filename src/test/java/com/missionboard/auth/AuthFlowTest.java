package com.missionboard.auth;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Department section = new Department();
        section.setName("系統科");
        section = departmentRepository.save(section);

        User user = new User();
        user.setUsername("chief");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setDisplayName("科長");
        user.setRole(User.Role.SECTION_CHIEF);
        user.setDepartment(section);
        userRepository.save(user);
    }

    @Test
    void correctCredentialsLoginRedirectsToHome() throws Exception {
        mockMvc.perform(formLogin("/auth/login").user("chief").password("password123"))
            .andExpect(authenticated())
            .andExpect(redirectedUrl("/home"));
    }

    @Test
    void wrongPasswordRedirectsToLoginError() throws Exception {
        mockMvc.perform(formLogin("/auth/login").user("chief").password("wrong"))
            .andExpect(unauthenticated())
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void unauthenticatedAccessToHomeRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/home"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    // 注意：test profile 雖設定 spring.session.store-type: none，但 Spring Session 的
    // SessionRepositoryFilter 仍會啟用（cookie 名稱為 SESSION，非容器原生 JSESSIONID），
    // 因此 request.getSession() 拿不到登入時實際寫入的 session；以下兩個測試改用回應中的
    // SESSION cookie 在後續請求間傳遞，才能重現真實瀏覽器的登入狀態延續行為。
    @Test
    void logoutInvalidatesSessionAndRedirectsToLoginLogout() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin("/auth/login").user("chief").password("password123"))
            .andExpect(authenticated())
            .andReturn();
        Cookie sessionCookie = loginResult.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();

        mockMvc.perform(post("/auth/logout").cookie(sessionCookie).with(csrf()))
            .andExpect(unauthenticated())
            .andExpect(redirectedUrl("/login?logout"));

        // LogoutFilter 不論認證狀態一律重導，光憑上面兩個斷言無法證明「這個 session 真的失效」，
        // 必須再用同一顆 cookie 存取受保護頁面，確認它已無法通過驗證
        mockMvc.perform(get("/home").cookie(sessionCookie))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void homePageShowsDisplayNameAfterLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin("/auth/login").user("chief").password("password123"))
            .andExpect(authenticated())
            .andReturn();
        Cookie sessionCookie = loginResult.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();

        mockMvc.perform(get("/home").cookie(sessionCookie))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attribute("displayName", "科長"));
    }
}
