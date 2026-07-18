package com.wbsflow.user;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserControllerTest {

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
        user.setUsername("leader");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setDisplayName("負責人");
        user.setRole(User.Role.PROJECT_LEADER);
        user.setDepartment(section);
        userRepository.save(user);
    }

    @Test
    void returnsAuthenticatedUserProfile() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin("/auth/login").user("leader").password("password123"))
            .andExpect(authenticated())
            .andReturn();
        Cookie sessionCookie = loginResult.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();

        mockMvc.perform(get("/api/users/me").cookie(sessionCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.username").value("leader"))
            .andExpect(jsonPath("$.data.displayName").value("負責人"))
            .andExpect(jsonPath("$.data.role").value("PROJECT_LEADER"));
    }

    @Test
    void unauthenticatedRequestRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().is3xxRedirection());
    }
}
