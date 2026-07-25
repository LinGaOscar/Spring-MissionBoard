package com.wbsflow.wbs;

import java.time.LocalDate;

public class WbsNodeDto {

    public record CreateRequest(Long parentId, Long presetId, String title, Integer sortOrder) {
    }

    public record UpdateRequest(String title, String notes, String priority,
                                 LocalDate startDate, LocalDate endDate) {
    }

    public record Response(Long id, Long parentId, short level, String title,
                            Long assigneeId, String assigneeDisplayName,
                            String status, String priority,
                            LocalDate startDate, LocalDate endDate,
                            String notes, int sortOrder) {
    }

    public record ReorderItem(Long nodeId, Long parentId, int sortOrder) {
    }
}
