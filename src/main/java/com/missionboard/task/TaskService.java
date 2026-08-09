package com.missionboard.task;

import com.missionboard.project.Project;
import com.missionboard.project.ProjectService;
import com.missionboard.user.User;
import com.missionboard.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskCategoryService taskCategoryService;
    private final ProjectService projectService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<Task> list(Long projectId) {
        return taskRepository.findByProjectId(projectId);
    }

    // 建立任務：天生可不歸類（category_id 可為 null），新任務一律進 NOT_STARTED 欄的最底部
    @Transactional
    public Task createTask(Long projectId, TaskDto.CreateRequest req) {
        Project project = projectService.getById(projectId);
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("任務需要標題");
        }
        TaskCategory category = req.categoryId() != null
            ? taskCategoryService.getCategoryInProject(projectId, req.categoryId())
            : null;

        Task task = new Task();
        task.setProject(project);
        task.setCategory(category);
        task.setTitle(req.title());
        task.setDescription(req.description());
        task.setStatus(Task.Status.NOT_STARTED);
        task.setSortOrder(req.sortOrder() != null ? req.sortOrder()
            : nextSortOrderForStatus(projectId, Task.Status.NOT_STARTED));
        return taskRepository.save(task);
    }

    // modal 一次送整份表單：categoryId 為 null 明確代表「改成未歸類」，非局部更新語意
    @Transactional
    public Task updateTask(Long projectId, Long taskId, TaskDto.UpdateRequest req) {
        Task task = getTaskInProject(projectId, taskId);
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("任務需要標題");
        }
        TaskCategory category = req.categoryId() != null
            ? taskCategoryService.getCategoryInProject(projectId, req.categoryId())
            : null;

        task.setTitle(req.title());
        task.setDescription(req.description());
        task.setCategory(category);
        task.setPriority(req.priority() != null ? Task.Priority.valueOf(req.priority()) : null);
        task.setStartDate(req.startDate());
        task.setDueDate(req.dueDate());
        return taskRepository.save(task);
    }

    @Transactional
    public void deleteTask(Long projectId, Long taskId) {
        Task task = getTaskInProject(projectId, taskId);
        taskRepository.delete(task);
    }

    // 單純改狀態（非看板拖曳）：一律接到目標欄最底部
    @Transactional
    public Task updateStatus(Long projectId, Long taskId, String statusStr) {
        Task task = getTaskInProject(projectId, taskId);
        Task.Status newStatus = Task.Status.valueOf(statusStr);
        if (task.getStatus() != newStatus) {
            task.setSortOrder(nextSortOrderForStatus(projectId, newStatus));
        }
        task.setStatus(newStatus);
        return taskRepository.save(task);
    }

    @Transactional
    public Task updateAssignee(Long projectId, Long taskId, Long assigneeId) {
        Task task = getTaskInProject(projectId, taskId);
        if (assigneeId == null) {
            task.setAssignee(null);
        } else {
            if (!projectService.isMember(projectId, assigneeId)) {
                throw new IllegalArgumentException("指派對象必須是專案成員");
            }
            User assignee = userRepository.findById(assigneeId)
                .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
            task.setAssignee(assignee);
        }
        return taskRepository.save(task);
    }

    // 看板拖曳專用：targetIndex 為目標狀態欄內的插入位置（0-based）。
    // 插入後對目標欄整批重新編號 0..n-1；若跨欄移動，來源欄也整批重新編號——
    // 比照舊 WbsNodeService.reorder／前端 moveNode 的「整批重編號」模式：只交換兩者 sortOrder
    // 在既有資料重複值（如皆為 0）時會產生相同 payload，Hibernate 髒檢查判斷無變化而不送出 UPDATE，
    // 導致移動操作無聲失效；全量重編號同時能自我修復既有的重複值。
    @Transactional
    public Task moveTask(Long projectId, Long taskId, String statusStr, int targetIndex) {
        Task task = getTaskInProject(projectId, taskId);
        Task.Status oldStatus = task.getStatus();
        Task.Status newStatus = Task.Status.valueOf(statusStr);

        List<Task> targetColumn = taskRepository
            .findByProjectIdAndStatusOrderBySortOrder(projectId, newStatus).stream()
            .filter(t -> !t.getId().equals(taskId))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        int insertAt = Math.max(0, Math.min(targetIndex, targetColumn.size()));
        targetColumn.add(insertAt, task);

        task.setStatus(newStatus);
        for (int i = 0; i < targetColumn.size(); i++) {
            targetColumn.get(i).setSortOrder(i);
        }
        taskRepository.saveAll(targetColumn);

        if (oldStatus != newStatus) {
            List<Task> oldColumn = taskRepository
                .findByProjectIdAndStatusOrderBySortOrder(projectId, oldStatus).stream()
                .filter(t -> !t.getId().equals(taskId))
                .toList();
            for (int i = 0; i < oldColumn.size(); i++) {
                oldColumn.get(i).setSortOrder(i);
            }
            taskRepository.saveAll(oldColumn);
        }
        return task;
    }

    private int nextSortOrderForStatus(Long projectId, Task.Status status) {
        return taskRepository.findByProjectIdAndStatusOrderBySortOrder(projectId, status).size();
    }

    // 統一的 IDOR 防護：確認任務存在且屬於路徑上的專案
    private Task getTaskInProject(Long projectId, Long taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new EntityNotFoundException("任務不存在"));
        if (!task.getProject().getId().equals(projectId)) {
            throw new SecurityException("任務不屬於此專案");
        }
        return task;
    }
}
