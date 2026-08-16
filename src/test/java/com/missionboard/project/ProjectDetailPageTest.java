package com.missionboard.project;

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
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectDetailPageTest {

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

    private Project project;

    @BeforeEach
    void setUp() {
        Department sectionA = departmentRepository.save(newDept("系統科"));
        Department sectionB = departmentRepository.save(newDept("網路科"));

        User leader = saveUser("leaderX", User.Role.PROJECT_LEADER, sectionA);
        saveUser("memberY", User.Role.PROJECT_MEMBER, sectionB);

        Project p = new Project();
        p.setName("樹編輯器測試專案");
        p.setSection(sectionA);
        p.setOwner(leader);
        p.setCreatedBy(leader);
        project = projectRepository.save(p);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), leader.getId()));
        pm.setAssignedBy(leader);
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
    void detailPageRendersForMemberWithReadAccess() throws Exception {
        Cookie session = loginAs("leaderX");

        mockMvc.perform(get("/projects/" + project.getId()).cookie(session))
            .andExpect(status().isOk())
            .andExpect(view().name("project/detail"));
    }

    @Test
    void detailPageRedirectsForUserWithoutReadAccess() throws Exception {
        Cookie session = loginAs("memberY");

        mockMvc.perform(get("/projects/" + project.getId()).cookie(session))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/projects"));
    }

    @Test
    void detailPageExposesArchiveRelatedModelAttributes() throws Exception {
        Cookie session = loginAs("leaderX");

        mockMvc.perform(get("/projects/" + project.getId()).cookie(session))
            .andExpect(status().isOk())
            .andExpect(model().attribute("canArchive", true))
            .andExpect(model().attribute("archived", false))
            .andExpect(model().attribute("ownerId", project.getOwner().getId()));
    }

    @Test
    void unauthenticatedAccessRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/projects/" + project.getId()))
            .andExpect(status().is3xxRedirection());
    }
}
