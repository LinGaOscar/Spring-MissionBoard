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

import static org.hamcrest.Matchers.containsInAnyOrder;
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

    private Department sectionA;
    private Department sectionB;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        saveUser("leader", "負責人", User.Role.PROJECT_LEADER, sectionA);
        saveUser("chief", "科長", User.Role.SECTION_CHIEF, sectionA);
        saveUser("memberB", "另科成員", User.Role.PROJECT_MEMBER, sectionB);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private void saveUser(String username, String displayName, User.Role role, Department dept) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setDepartment(dept);
        userRepository.save(user);
    }

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void returnsAuthenticatedUserProfile() throws Exception {
        Cookie session = loginAs("leader");

        mockMvc.perform(get("/api/users/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.username").value("leader"))
            .andExpect(jsonPath("$.data.displayName").value("負責人"))
            .andExpect(jsonPath("$.data.role").value("PROJECT_LEADER"));
    }

    @Test
    void unauthenticatedRequestReturnsUnauthorizedJson() throws Exception {
        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void listReturnsAllUsersWithoutFilter() throws Exception {
        Cookie session = loginAs("chief");

        mockMvc.perform(get("/api/users").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void listFiltersByDepartmentId() throws Exception {
        Cookie session = loginAs("chief");

        mockMvc.perform(get("/api/users").param("departmentId", String.valueOf(sectionA.getId())).cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[*].username", containsInAnyOrder("leader", "chief")));
    }
}
