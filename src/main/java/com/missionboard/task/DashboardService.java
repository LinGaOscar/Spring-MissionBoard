package com.missionboard.task;

import com.missionboard.department.Department;
import com.missionboard.project.Project;
import com.missionboard.project.ProjectDto;
import com.missionboard.project.ProjectRepository;
import com.missionboard.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

    // 唯讀查詢比照 ProjectService/TaskService 既有慣例標 readOnly；ProjectDto.Response.from() 會觸發
    // section/owner 的 lazy load，沒有交易邊界時只靠 spring.jpa.open-in-view 撐住、不應依賴這個全域設定
    @Transactional(readOnly = true)
    public DashboardDto.Response getDashboard(User user) {
        return switch (user.getRole()) {
            case PROJECT_LEADER, PROJECT_MEMBER -> buildPersonalView(user);
            case SECTION_CHIEF -> buildSectionView(user);
            case DIRECTOR -> buildOrgView();
        };
    }

    // 操作視角：我是成員的進行中專案＋指派給我、尚未完成、專案未封存的任務
    private DashboardDto.Response buildPersonalView(User user) {
        List<Project> projects = projectRepository.findByMemberUserIdAndArchived(user.getId(), false);
        List<Task> tasks = taskRepository.findActiveByAssigneeId(user.getId(), Task.Status.DONE);
        List<DashboardDto.TaskItem> taskItems = tasks.stream()
            .sorted(Comparator.comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getId))
            .map(t -> new DashboardDto.TaskItem(
                t.getId(), t.getProject().getId(), t.getProject().getName(),
                t.getTitle(), t.getStatus().name(), t.getDueDate()))
            .toList();
        return DashboardDto.Response.personal(
            projects.stream().map(ProjectDto.Response::from).toList(), taskItems);
    }

    // 管理視角：本科所有未封存專案，每個專案帶任務數／逾期數／完成度（在記憶體中聚合，
    // 科內專案數量通常是個位數到十幾，不值得為此寫聚合 SQL）
    private DashboardDto.Response buildSectionView(User user) {
        List<Project> projects = projectRepository.findBySectionIdAndArchived(user.getDepartment().getId(), false);
        List<DashboardDto.ProjectSummary> summaries = projects.stream()
            .map(p -> summarize(p.getId(), p.getName(), taskRepository.findByProjectId(p.getId())))
            .toList();
        return DashboardDto.Response.section(user.getDepartment().getName(), summaries);
    }

    // 總覽視角：全公司所有未封存專案，依科別分組聚合
    private DashboardDto.Response buildOrgView() {
        List<Project> projects = projectRepository.findByArchived(false);
        Map<Department, List<Project>> bySection = projects.stream()
            .collect(Collectors.groupingBy(Project::getSection));
        List<DashboardDto.SectionSummary> summaries = bySection.entrySet().stream()
            .map(entry -> {
                List<Task> sectionTasks = entry.getValue().stream()
                    .flatMap(p -> taskRepository.findByProjectId(p.getId()).stream())
                    .toList();
                DashboardDto.ProjectSummary agg = summarize(null, null, sectionTasks);
                return new DashboardDto.SectionSummary(
                    entry.getKey().getId(), entry.getKey().getName(),
                    entry.getValue().size(), agg.overdueCount(), agg.completionLabel());
            })
            // Department 沒有覆寫 equals/hashCode，lazy proxy 每次請求都是新實例，
            // groupingBy 的 HashMap 迭代順序因此不穩定；固定依 sectionId 排序避免主任重整頁面時科別清單跳動
            .sorted(Comparator.comparing(DashboardDto.SectionSummary::sectionId))
            .toList();
        return DashboardDto.Response.org(summaries);
    }

    private DashboardDto.ProjectSummary summarize(Long projectId, String projectName, List<Task> tasks) {
        int total = tasks.size();
        int done = (int) tasks.stream().filter(t -> t.getStatus() == Task.Status.DONE).count();
        int overdue = (int) tasks.stream().filter(this::isOverdue).count();
        String completionLabel = total == 0 ? "--" : Math.round(done * 100.0 / total) + "%";
        return new DashboardDto.ProjectSummary(projectId, projectName, total, overdue, completionLabel);
    }

    private boolean isOverdue(Task t) {
        return t.getDueDate() != null && t.getStatus() != Task.Status.DONE
            && t.getDueDate().isBefore(LocalDate.now());
    }
}
