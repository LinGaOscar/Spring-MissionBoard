package com.missionboard.task;

public class TaskCategoryDto {

    public record CreateRequest(Long parentCategoryId, Long presetId, String name, Integer sortOrder) {
    }

    public record UpdateRequest(String name, Integer sortOrder, Long parentCategoryId) {
    }

    public record Response(Long id, Long parentCategoryId, String name, int sortOrder) {
        public static Response from(TaskCategory category) {
            Long parentId = category.getParentCategory() != null ? category.getParentCategory().getId() : null;
            return new Response(category.getId(), parentId, category.getName(), category.getSortOrder());
        }
    }
}
