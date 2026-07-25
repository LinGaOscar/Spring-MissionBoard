package com.wbsflow.wbs;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WbsPresetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department sectionA;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        saveUser("chiefA", User.Role.SECTION_CHIEF, sectionA);
        saveUser("leaderA", User.Role.PROJECT_LEADER, sectionA);

        WbsPreset global = new WbsPreset();
        global.setType(WbsPreset.Type.STAGE);
        global.setName("SIT");
        global.setSortOrder(1);
        wbsPresetRepository.save(global);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private void saveUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        userRepository.save(u);
    }

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void listReturnsVisiblePresetsForType() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(get("/api/presets").param("type", "STAGE").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("SIT"));
    }

    @Test
    void sectionChiefCreatesPreset() throws Exception {
        Cookie session = loginAs("chiefA");

        mockMvc.perform(post("/api/presets").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CATEGORY\",\"name\":\"自訂類別\",\"sortOrder\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("自訂類別"));
    }

    @Test
    void nonChiefCannotCreatePreset() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/presets").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CATEGORY\",\"name\":\"自訂類別\",\"sortOrder\":1}"))
            .andExpect(status().isForbidden());
    }
}
