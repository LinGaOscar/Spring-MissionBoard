package com.wbsflow.user;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesUserAndFindsByUsername() {
        Department section = new Department();
        section.setName("系統科");
        section = departmentRepository.save(section);

        User u = new User();
        u.setUsername("chief");
        u.setPassword("$2b$10$hash");
        u.setDisplayName("科長");
        u.setRole(User.Role.SECTION_CHIEF);
        u.setDepartment(section);
        userRepository.save(u);

        Optional<User> found = userRepository.findByUsername("chief");

        assertThat(found).isPresent();
        assertThat(found.get().getRole()).isEqualTo(User.Role.SECTION_CHIEF);
        assertThat(found.get().getDepartment().getName()).isEqualTo("系統科");
    }

    @Test
    void findByUsernameReturnsEmptyWhenNotFound() {
        assertThat(userRepository.findByUsername("nobody")).isEmpty();
    }
}
