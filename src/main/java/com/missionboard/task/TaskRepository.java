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

    // 首頁儀表板 PERSONAL 視角：跨所有專案抓「指派給我、尚未完成、專案未封存」的任務，
    // 不含已封存專案（那些已凍結不需要再關注）
    @Query("SELECT t FROM Task t WHERE t.assignee.id = :assigneeId AND t.status <> :excludedStatus "
        + "AND t.project.archived = false")
    List<Task> findActiveByAssigneeId(@Param("assigneeId") Long assigneeId, @Param("excludedStatus") Task.Status excludedStatus);
}
