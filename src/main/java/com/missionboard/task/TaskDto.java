package com.missionboard.task;

import java.time.LocalDate;

public class TaskDto {

    public record CreateRequest(Long categoryId, String title, String description, Integer sortOrder) {
    }

    // 看板 modal 一次送整份表單：categoryId 為 null 明確代表「改成未歸類」，不是「不變」
    public record UpdateRequest(String title, String description, Long categoryId, String priority,
                                 LocalDate startDate, LocalDate dueDate) {
    }

    public record Response(Long id, Long categoryId, String title, String description,
                            Long assigneeId, String assigneeDisplayName,
                            String status, String priority,
                            LocalDate startDate, LocalDate dueDate, int sortOrder) {
    }

    public record StatusRequest(String status) {
    }

    public record AssigneeRequest(Long assigneeId) {
    }

    // sortOrder：目標狀態欄內的插入索引（0-based）
    public record MoveRequest(String status, int sortOrder) {
    }
}
