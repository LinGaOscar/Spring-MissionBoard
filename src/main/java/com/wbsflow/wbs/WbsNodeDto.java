package com.wbsflow.wbs;

import java.time.LocalDate;

public class WbsNodeDto {

    public record CreateRequest(Long parentId, Long presetId, String title, Integer sortOrder) {
    }

    public record UpdateRequest(String title, String notes, String priority,
                                 LocalDate startDate, LocalDate endDate) {
    }
}
