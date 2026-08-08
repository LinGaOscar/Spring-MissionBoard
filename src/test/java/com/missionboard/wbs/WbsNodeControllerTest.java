package com.missionboard.wbs;

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
class WbsNodeControllerTest {

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
    private WbsNodeRepository wbsNodeRepository;

    @Autowired
    private WbsPresetRepository wbsPresetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Department sectionA;
    private Department sectionB;
    private Project project;
    private WbsPreset stagePreset;
    private WbsPreset categoryPreset;

    @BeforeEach
    void setUp() {
        sectionA = departmentRepository.save(newDept("系統科"));
        sectionB = departmentRepository.save(newDept("網路科"));

        User leader = saveUser("leaderA", User.Role.PROJECT_LEADER, sectionA);
        saveUser("chiefB", User.Role.SECTION_CHIEF, sectionB);

        Project p = new Project();
        p.setName("測試專案");
        p.setSection(sectionA);
        p.setOwner(leader);
        p.setCreatedBy(leader);
        project = projectRepository.save(p);

        ProjectMember pm = new ProjectMember();
        pm.setId(new ProjectMemberId(project.getId(), leader.getId()));
        pm.setAssignedBy(leader);
        projectMemberRepository.save(pm);

        stagePreset = newPreset(WbsPreset.Type.STAGE, "SIT", null);
        categoryPreset = newPreset(WbsPreset.Type.CATEGORY, "程式開發", null);
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

    private WbsPreset newPreset(WbsPreset.Type type, String name, Department section) {
        WbsPreset preset = new WbsPreset();
        preset.setType(type);
        preset.setName(name);
        preset.setSortOrder(1);
        preset.setSection(section);
        return wbsPresetRepository.save(preset);
    }

    private WbsNode newL1(String title) {
        WbsNode node = new WbsNode();
        node.setProject(project);
        node.setLevel((short) 1);
        node.setTitle(title);
        return wbsNodeRepository.save(node);
    }

    private Cookie loginAs(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/auth/login").user(username).password("password123"))
            .andExpect(authenticated())
            .andReturn();
        return result.getResponse().getCookie("SESSION");
    }

    @Test
    void createL1NodeFromStagePreset() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/projects/" + project.getId() + "/nodes").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"presetId\":" + stagePreset.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("SIT"))
            .andExpect(jsonPath("$.data.level").value(1));
    }

    @Test
    void listReturnsFullTreeWithAggregation() throws Exception {
        WbsNode l1 = newL1("SIT");
        Cookie session = loginAs("leaderA");

        mockMvc.perform(get("/api/projects/" + project.getId() + "/nodes").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].status").value("NOT_STARTED"));
    }

    @Test
    void listDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(get("/api/projects/" + project.getId() + "/nodes").cookie(session))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateNodeTitle() throws Exception {
        WbsNode l1 = newL1("SIT");
        Cookie session = loginAs("leaderA");

        mockMvc.perform(put("/api/projects/" + project.getId() + "/nodes/" + l1.getId())
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"改過的標題\"}"))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(l1.getId()).orElseThrow().getTitle()).isEqualTo("改過的標題");
    }

    @Test
    void deleteNodeRemovesIt() throws Exception {
        WbsNode l1 = newL1("SIT");
        Cookie session = loginAs("leaderA");

        mockMvc.perform(delete("/api/projects/" + project.getId() + "/nodes/" + l1.getId())
                .cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(l1.getId())).isEmpty();
    }

    @Test
    void reorderUpdatesSortOrder() throws Exception {
        WbsNode l1a = newL1("SIT");
        WbsNode l1b = newL1("UAT");
        Cookie session = loginAs("leaderA");

        mockMvc.perform(patch("/api/projects/" + project.getId() + "/nodes/reorder")
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"nodeId\":" + l1a.getId() + ",\"parentId\":null,\"sortOrder\":1},"
                    + "{\"nodeId\":" + l1b.getId() + ",\"parentId\":null,\"sortOrder\":0}]"))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(l1a.getId()).orElseThrow().getSortOrder()).isEqualTo(1);
    }

    @Test
    void updateStatusOnL3Node() throws Exception {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = new WbsNode();
        l2.setProject(project);
        l2.setParent(l1);
        l2.setLevel((short) 2);
        l2.setTitle("程式開發");
        l2 = wbsNodeRepository.save(l2);
        WbsNode l3 = new WbsNode();
        l3.setProject(project);
        l3.setParent(l2);
        l3.setLevel((short) 3);
        l3.setTitle("登入功能開發");
        l3 = wbsNodeRepository.save(l3);

        Cookie session = loginAs("leaderA");
        mockMvc.perform(patch("/api/projects/" + project.getId() + "/nodes/" + l3.getId() + "/status")
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findById(l3.getId()).orElseThrow().getStatus())
            .isEqualTo(WbsNode.Status.IN_PROGRESS);
    }

    @Test
    void updateAssigneeRejectsNonMember() throws Exception {
        WbsNode l1 = newL1("SIT");
        WbsNode l2 = new WbsNode();
        l2.setProject(project);
        l2.setParent(l1);
        l2.setLevel((short) 2);
        l2.setTitle("程式開發");
        l2 = wbsNodeRepository.save(l2);
        WbsNode l3 = new WbsNode();
        l3.setProject(project);
        l3.setParent(l2);
        l3.setLevel((short) 3);
        l3.setTitle("登入功能開發");
        l3 = wbsNodeRepository.save(l3);
        User outsider = saveUser("outsiderX", User.Role.PROJECT_MEMBER, sectionA);

        Cookie session = loginAs("leaderA");
        mockMvc.perform(patch("/api/projects/" + project.getId() + "/nodes/" + l3.getId() + "/assignee")
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assigneeId\":" + outsider.getId() + "}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void initEndpointCreatesL1SkeletonFromDefaultStages() throws Exception {
        Cookie session = loginAs("leaderA");

        mockMvc.perform(post("/api/projects/" + project.getId() + "/nodes/init")
                .cookie(session).with(csrf()))
            .andExpect(status().isOk());

        assertThat(wbsNodeRepository.findByProjectId(project.getId())).hasSize(1);
    }

    @Test
    void writeOperationsDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(post("/api/projects/" + project.getId() + "/nodes").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"presetId\":" + stagePreset.getId() + "}"))
            .andExpect(status().isForbidden());
    }
}
