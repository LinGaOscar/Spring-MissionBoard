package com.wbsflow.project;

import com.wbsflow.user.User;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    // 統一拋 EntityNotFoundException，controller 層交給 GlobalExceptionHandler 轉 404
    public Project getById(Long id) {
        return projectRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("專案不存在"));
    }

    public boolean isMember(Long projectId, Long userId) {
        if (projectId == null || userId == null) return false;
        return projectMemberRepository.existsByIdProjectIdAndIdUserId(projectId, userId);
    }

    // 讀取權限：DIRECTOR 跨科唯讀、SECTION_CHIEF 科內全權、PROJECT_LEADER/PROJECT_MEMBER 僅參與專案
    public boolean canRead(Long projectId, User user) {
        if (projectId == null || user == null || user.getRole() == null) return false;
        Project project = getById(projectId);
        return switch (user.getRole()) {
            case DIRECTOR -> true;
            case SECTION_CHIEF -> sameSection(project, user);
            case PROJECT_LEADER, PROJECT_MEMBER -> isMember(projectId, user.getId());
        };
    }

    // 寫入權限：封存專案全員唯讀；DIRECTOR 一律唯讀；其餘同讀取權限範圍
    public boolean canWrite(Long projectId, User user) {
        if (projectId == null || user == null || user.getRole() == null) return false;
        Project project = getById(projectId);
        if (project.isArchived()) return false;
        return switch (user.getRole()) {
            case DIRECTOR -> false;
            case SECTION_CHIEF -> sameSection(project, user);
            case PROJECT_LEADER, PROJECT_MEMBER -> isMember(projectId, user.getId());
        };
    }

    private boolean sameSection(Project project, User user) {
        if (project.getSection() == null || user.getDepartment() == null) return false;
        return project.getSection().getId().equals(user.getDepartment().getId());
    }
}
