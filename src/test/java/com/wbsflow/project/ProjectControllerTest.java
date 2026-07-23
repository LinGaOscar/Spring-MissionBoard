package com.wbsflow.project;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectControllerTest {

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
    private PasswordEncoder passwordEncoder;

    private Department sectionA;
    private Department sectionB;
    private User leaderA;
    private Project projectA;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        leaderA = saveUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
        saveUser("chiefA", User.Role.SECTION_CHIEF, sectionA);
        saveUser("chiefB", User.Role.SECTION_CHIEF, sectionB);

        Project p = new Project();
        p.setName("既有專案");
        p.setSection(sectionA);
        p.setOwner(leaderA);
        p.setCreatedBy(leaderA);
        projectA = projectRepository.save(p);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(projectA.getId(), leaderA.getId()));
        pm.setAssignedBy(leaderA);
        projectMemberRepository.save(pm);
    }

    private Department newDept(String name) {
        Department d = new Department();
        d.setName(name);
        return d;
    }

    private User saveUser(String username, User.Role role, Department dept) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setDisplayName(username);
        u.setRole(role);
        u.setDepartment(dept);
        return userRepository.save(u);
    }

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void createProjectAutoAssignsSectionAndOwner() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/projects").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新專案\",\"description\":\"說明\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("新專案"))
            .andExpect(jsonPath("$.data.sectionId").value(sectionA.getId()))
            .andExpect(jsonPath("$.data.ownerUsername").value("leaderA"));
    }

    @Test
    void createProjectWithBlankNameReturnsValidationError() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/projects").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"description\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void listReturnsOnlySameSectionProjectsForSectionChief() throws Exception {
        Cookie session = loginAs("chiefA");

        mockMvc.perform(get("/api/projects").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("既有專案"));
    }

    @Test
    void listReturnsEmptyForChiefFromOtherSection() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(get("/api/projects").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getProjectDeniedForNonMemberOutsideSection() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(get("/api/projects/" + projectA.getId()).cookie(session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void archiveThenUnarchiveBySectionChiefIsIdempotent() throws Exception {
        Cookie session = loginAs("chiefA");

        mockMvc.perform(patch("/api/projects/" + projectA.getId() + "/archive").cookie(session).with(csrf()))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/projects/" + projectA.getId() + "/archive").cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(projectRepository.findById(projectA.getId()).orElseThrow().isArchived()).isTrue();

        mockMvc.perform(patch("/api/projects/" + projectA.getId() + "/unarchive").cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(projectRepository.findById(projectA.getId()).orElseThrow().isArchived()).isFalse();
    }

    @Test
    void directorCannotArchive() throws Exception {
        saveUser("director1", User.Role.DIRECTOR, sectionA);
        Cookie session = loginAs("director1");

        mockMvc.perform(patch("/api/projects/" + projectA.getId() + "/archive").cookie(session).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
