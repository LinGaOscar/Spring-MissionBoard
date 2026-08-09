package com.missionboard.task;

import com.missionboard.project.Project;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_categories")
@Getter
@Setter
public class TaskCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // NULL = 階段層；有值 = 類別層，掛在某階段下。刪階段連帶刪其下類別，
    // 對應 sql/01_ddl.sql 的 ON DELETE CASCADE；標註後 H2 測試庫（entity 建表）行為與正式 Postgres DDL 一致
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_category_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TaskCategory parentCategory;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
