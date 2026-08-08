package com.missionboard.wbs;

public class WbsPresetDto {

    public record Response(Long id, WbsPreset.Type type, String name, int sortOrder,
                            boolean enabled, Long sectionId) {
        public static Response from(WbsPreset preset) {
            return new Response(
                preset.getId(), preset.getType(), preset.getName(), preset.getSortOrder(),
                preset.isEnabled(), preset.getSection() != null ? preset.getSection().getId() : null
            );
        }
    }

    public record CreateRequest(WbsPreset.Type type, String name, int sortOrder) {
    }

    public record UpdateRequest(String name, Integer sortOrder, Boolean enabled) {
    }
}
