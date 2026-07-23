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
}
