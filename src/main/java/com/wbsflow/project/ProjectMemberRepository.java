package com.wbsflow.project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {
    boolean existsByIdProjectIdAndIdUserId(Long projectId, Long userId);
}
