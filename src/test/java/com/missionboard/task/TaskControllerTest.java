package com.missionboard.task;

import com.jayway.jsonpath.JsonPath;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskControllerTest {

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
    private TaskRepository taskRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Project project;

    @BeforeEach
    void setUp() {
        Department sectionA = new Department();
        sectionA.setName("系統科A");
        departmentRepository.save(sectionA);
        Department sectionB = new Department();
        sectionB.setName("系統科B");
        departmentRepository.save(sectionB);

        User leaderA = newUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
        newUser("chiefB", User.Role.SECTION_CHIEF, sectionB);

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
    void createTaskWithoutCategory() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/tasks", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"categoryId\":null,\"title\":\"整理需求訪談紀錄\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
            .andExpect(jsonPath("$.data.categoryId").doesNotExist());
    }

    @Test
    void listDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");
        mockMvc.perform(get("/api/projects/{id}/tasks", project.getId()).cookie(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void moveUpdatesStatusAndSortOrder() throws Exception {
        Cookie session = loginAs("leaderA");
        String createRes = mockMvc.perform(post("/api/projects/{id}/tasks", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"categoryId\":null,\"title\":\"任務\"}"))
            .andReturn().getResponse().getContentAsString();
        Long taskId = ((Number) JsonPath.read(createRes, "$.data.id")).longValue();

        mockMvc.perform(patch("/api/projects/{pid}/tasks/{tid}/move", project.getId(), taskId).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"status\":\"IN_PROGRESS\",\"sortOrder\":0}"))
            .andExpect(status().isOk());

        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(Task.Status.IN_PROGRESS);
    }

    @Test
    void updateAssigneeRejectsNonMember() throws Exception {
        Cookie session = loginAs("leaderA");
        String createRes = mockMvc.perform(post("/api/projects/{id}/tasks", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"categoryId\":null,\"title\":\"任務\"}"))
            .andReturn().getResponse().getContentAsString();
        Long taskId = ((Number) JsonPath.read(createRes, "$.data.id")).longValue();
        User outsider = newUser("outsider", User.Role.PROJECT_MEMBER, project.getSection());

        mockMvc.perform(patch("/api/projects/{pid}/tasks/{tid}/assignee", project.getId(), taskId).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"assigneeId\":" + outsider.getId() + "}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void exportXlsxSucceedsForArchivedProject() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/tasks", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"categoryId\":null,\"title\":\"任務\"}"))
            .andExpect(status().isOk());

        project.setArchived(true);
        projectRepository.save(project);

        mockMvc.perform(get("/api/projects/{id}/export.xlsx", project.getId()).cookie(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("filename*=UTF-8''"));
    }

    @Test
    void exportXlsxDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(get("/api/projects/{id}/export.xlsx", project.getId()).cookie(session))
            .andExpect(status().isForbidden());
    }
}
