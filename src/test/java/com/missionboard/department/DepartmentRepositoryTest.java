package com.missionboard.department;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DepartmentRepositoryTest {

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    void savesAndFindsChildDepartmentByParent() {
        Department parent = new Department();
        parent.setName("資訊部");
        parent = departmentRepository.save(parent);

        Department child = new Department();
        child.setName("系統科");
        child.setParent(parent);
        departmentRepository.save(child);

        List<Department> children = departmentRepository.findByParentId(parent.getId());

        assertThat(children).hasSize(1);
        assertThat(children.get(0).getName()).isEqualTo("系統科");
    }

    @Test
    void topLevelDepartmentHasNullParent() {
        Department dept = new Department();
        dept.setName("資訊部");
        Department saved = departmentRepository.save(dept);

        assertThat(saved.getParent()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}
