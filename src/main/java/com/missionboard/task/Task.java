package com.missionboard.task;

import com.missionboard.project.Project;
import com.missionboard.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 看板前端 saveTask() 對同一任務並行送出 PUT（本體欄位）+ PATCH（指派人），
// 兩個獨立 @Transactional 方法各自 findById 讀到自己的快照；Hibernate 預設全欄位 UPDATE
// 會用「載入當下」的舊快照覆蓋對方剛提交的欄位（遺失更新）。@DynamicUpdate 讓 UPDATE 只帶
// 本次交易真正 set 過的欄位，兩個並行請求互不覆蓋彼此未觸碰的欄位。
@DynamicUpdate
@Entity
@Table(name = "tasks")
@Getter
@Setter
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // NULL = 未歸類。刪除類別時任務落回未歸類，對應 sql/01_ddl.sql 的 ON DELETE SET NULL；
    // 標註後 H2 測試庫（entity 建表）行為與正式 Postgres DDL 一致
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private TaskCategory category;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Priority priority;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum Status {
        NOT_STARTED, IN_PROGRESS, DONE
    }

    public enum Priority {
        HIGH, MEDIUM, LOW
    }
}
