package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.department.DepartmentRepository;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectMember;
import com.missionboard.project.ProjectMemberId;
import com.missionboard.project.ProjectMemberRepository;
import com.missionboard.project.ProjectRepository;
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
class TaskCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private TaskCategoryPresetRepository taskCategoryPresetRepository;
    @Autowired
    private TaskCategoryService taskCategoryService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;
    private TaskCategoryPreset stagePreset;

    @BeforeEach
    void setUp() throws Exception {
        Department sectionA = new Department();
        sectionA.setName("系統科A");
        departmentRepository.save(sectionA);
        Department sectionB = new Department();
        sectionB.setName("系統科B");
        departmentRepository.save(sectionB);

        User leaderA = newUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
        User chiefB = newUser("chiefB", User.Role.SECTION_CHIEF, sectionB);

        project = new Project();
        project.setName("專案A");
        project.setSection(sectionA);
        project.setOwner(leaderA);
        project.setCreatedBy(leaderA);
        projectRepository.save(project);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), leaderA.getId()));
        pm.setAssignedBy(leaderA);
        projectMemberRepository.save(pm);

        stagePreset = new TaskCategoryPreset();
        stagePreset.setType(TaskCategoryPreset.Type.STAGE);
        stagePreset.setName("SIT");
        stagePreset.setSortOrder(1);
        taskCategoryPresetRepository.save(stagePreset);
    }

    private User newUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private Cookie loginAs(String username) throws Exception {
        return mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andReturn().getResponse().getCookie("SESSION");
    }

    @Test
    void createStageLevelCategoryFromPreset() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/task-categories", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":null,\"presetId\":" + stagePreset.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("SIT"));
    }

    @Test
    void createCategoryFromNameWhenPresetIdMissing() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/task-categories", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":null,\"name\":\"自訂名稱\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("自訂名稱"));
    }

    @Test
    void createFailsWhenBothPresetIdAndNameMissing() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/task-categories", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":null}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");
        mockMvc.perform(post("/api/projects/{id}/task-categories", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":null,\"presetId\":" + stagePreset.getId() + "}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void reparentSubCategoryViaPutEndpoint() throws Exception {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory stageB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageA.getId(), null, "子類別", null));

        Cookie session = loginAs("leaderA");
        mockMvc.perform(put("/api/projects/{id}/task-categories/{catId}", project.getId(), sub.getId())
                .cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":" + stageB.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.parentCategoryId").value(stageB.getId()));
    }
}
