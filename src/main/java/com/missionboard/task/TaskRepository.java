package com.missionboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByProjectId(Long projectId);

    List<Task> findByProjectIdAndStatusOrderBySortOrder(Long projectId, Task.Status status);

    // 移除專案成員時連動清除其指派；clearAutomatically 避免呼叫端讀到 stale 的一級快取
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Task t SET t.assignee = null WHERE t.project.id = :projectId AND t.assignee.id = :userId")
    void clearAssigneeForUserInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
