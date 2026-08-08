package com.missionboard.wbs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WbsNodeRepository extends JpaRepository<WbsNode, Long> {
    List<WbsNode> findByParentId(Long parentId);

    List<WbsNode> findByProjectId(Long projectId);

    boolean existsByProjectId(Long projectId);

    // 移除專案成員時連動清除其指派；clearAutomatically 避免呼叫端讀到 stale 的一級快取
    @Modifying(clearAutomatically = true)
    @Query("UPDATE WbsNode n SET n.assignee = null WHERE n.project.id = :projectId AND n.assignee.id = :userId")
    void clearAssigneeForUserInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
