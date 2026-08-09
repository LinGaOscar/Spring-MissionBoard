package com.missionboard.task;

public class TaskCategoryPresetDto {

    public record Response(Long id, TaskCategoryPreset.Type type, String name, int sortOrder,
                            boolean enabled, Long sectionId) {
        public static Response from(TaskCategoryPreset preset) {
            return new Response(
                preset.getId(), preset.getType(), preset.getName(), preset.getSortOrder(),
                preset.isEnabled(), preset.getSection() != null ? preset.getSection().getId() : null
            );
        }
    }

    public record CreateRequest(TaskCategoryPreset.Type type, String name, int sortOrder) {
    }

    public record UpdateRequest(String name, Integer sortOrder, Boolean enabled) {
    }
}
