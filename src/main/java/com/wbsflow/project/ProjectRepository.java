package com.wbsflow.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findBySectionIdAndArchived(Long sectionId, boolean archived);

    List<Project> findByArchived(boolean archived);

    @Query("SELECT p FROM Project p JOIN ProjectMember pm ON pm.id.projectId = p.id "
        + "WHERE pm.id.userId = :userId AND p.archived = :archived")
    List<Project> findByMemberUserIdAndArchived(@Param("userId") Long userId, @Param("archived") boolean archived);
}
