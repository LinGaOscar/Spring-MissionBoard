package com.missionboard.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskCategoryRepository extends JpaRepository<TaskCategory, Long> {
    List<TaskCategory> findByProjectId(Long projectId);
    List<TaskCategory> findByParentCategoryId(Long parentCategoryId);
}
