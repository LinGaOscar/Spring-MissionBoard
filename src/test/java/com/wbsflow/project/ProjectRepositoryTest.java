package com.wbsflow.project;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void savesProjectWithSectionAndOwnerDefaultsNotArchived() {
        Department section = new Department();
        section.setName("系統科");
        section = departmentRepository.save(section);

        User owner = new User();
        owner.setUsername("leader");
        owner.setPassword("hash");
        owner.setDisplayName("負責人");
        owner.setRole(User.Role.PROJECT_LEADER);
        owner.setDepartment(section);
        owner = userRepository.save(owner);

        Project p = new Project();
        p.setName("測試專案");
        p.setDescription("描述文字");
        p.setSection(section);
        p.setOwner(owner);
        p.setCreatedBy(owner);
        Project saved = projectRepository.save(p);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isArchived()).isFalse();
        assertThat(saved.getSection().getName()).isEqualTo("系統科");
        assertThat(saved.getOwner().getUsername()).isEqualTo("leader");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }
}
