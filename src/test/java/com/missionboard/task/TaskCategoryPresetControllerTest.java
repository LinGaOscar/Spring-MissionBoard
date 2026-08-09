package com.missionboard.task;

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
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskCategoryPresetControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Department sectionA = new Department();
        sectionA.setName("系統科A");
        departmentRepository.save(sectionA);

        User chiefA = new User();
        chiefA.setUsername("chiefA");
        chiefA.setPassword(passwordEncoder.encode("password123"));
        chiefA.setDisplayName("chiefA");
        chiefA.setRole(User.Role.SECTION_CHIEF);
        chiefA.setDepartment(sectionA);
        userRepository.save(chiefA);

        User leaderA = new User();
        leaderA.setUsername("leaderA");
        leaderA.setPassword(passwordEncoder.encode("password123"));
        leaderA.setDisplayName("leaderA");
        leaderA.setRole(User.Role.PROJECT_LEADER);
        leaderA.setDepartment(sectionA);
        userRepository.save(leaderA);

        TaskCategoryPreset global = new TaskCategoryPreset();
        global.setType(TaskCategoryPreset.Type.STAGE);
        global.setName("SIT");
        global.setSortOrder(1);
        taskCategoryPresetRepository.save(global);
    }

    private Cookie loginAs(String username) throws Exception {
        return mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andReturn().getResponse().getCookie("SESSION");
    }

    @Test
    void listReturnsVisiblePresetsForType() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(get("/api/task-category-presets").param("type", "STAGE").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].name").value("SIT"));
    }

    @Test
    void sectionChiefCreatesPreset() throws Exception {
        Cookie session = loginAs("chiefA");
        mockMvc.perform(post("/api/task-category-presets").cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"type\":\"CATEGORY\",\"name\":\"新類別\",\"sortOrder\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("新類別"));
    }

    @Test
    void nonChiefCannotCreatePreset() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/task-category-presets").cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"type\":\"CATEGORY\",\"name\":\"新類別\",\"sortOrder\":1}"))
            .andExpect(status().isForbidden());
    }
}
