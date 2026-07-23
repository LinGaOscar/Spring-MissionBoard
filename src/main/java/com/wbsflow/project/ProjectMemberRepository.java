package com.wbsflow.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {
    boolean existsByIdProjectIdAndIdUserId(Long projectId, Long userId);

    List<ProjectMember> findByIdProjectId(Long projectId);
}
