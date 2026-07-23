package com.wbsflow.project;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public class ProjectDto {

    public record Response(Long id, String name, String description,
                            Long sectionId, String sectionName,
                            Long ownerId, String ownerUsername, String ownerDisplayName,
                            boolean archived, LocalDateTime createdAt) {
        public static Response from(Project p) {
            return new Response(
                p.getId(), p.getName(), p.getDescription(),
                p.getSection().getId(), p.getSection().getName(),
                p.getOwner().getId(), p.getOwner().getUsername(), p.getOwner().getDisplayName(),
                p.isArchived(), p.getCreatedAt()
            );
        }
    }

    public record CreateRequest(@NotBlank String name, String description) {
    }

    public record MemberResponse(Long userId, String username, String displayName,
                                  String role, LocalDateTime joinedAt) {
        public static MemberResponse from(ProjectMember pm) {
            return new MemberResponse(
                pm.getUser().getId(), pm.getUser().getUsername(), pm.getUser().getDisplayName(),
                pm.getUser().getRole().name(), pm.getJoinedAt()
            );
        }
    }

    public record MemberRequest(Long userId) {
    }
}
