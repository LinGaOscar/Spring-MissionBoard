package com.missionboard.task;

import com.missionboard.project.ProjectDto;

import java.time.LocalDate;
import java.util.List;

public class DashboardDto {

    public record Response(String viewType,
                            List<ProjectDto.Response> activeProjects, List<TaskItem> myTasks,
                            String sectionName, List<ProjectSummary> projectSummaries,
                            List<SectionSummary> sectionSummaries) {
        public static Response personal(List<ProjectDto.Response> projects, List<TaskItem> tasks) {
            return new Response("PERSONAL", projects, tasks, null, null, null);
        }

        public static Response section(String sectionName, List<ProjectSummary> summaries) {
            return new Response("SECTION", null, null, sectionName, summaries, null);
        }

        public static Response org(List<SectionSummary> summaries) {
            return new Response("ORG", null, null, null, null, summaries);
        }
    }

    public record TaskItem(Long id, Long projectId, String projectName, String title,
                            String status, LocalDate dueDate) {
    }

    public record ProjectSummary(Long projectId, String projectName,
                                  int taskCount, int overdueCount, String completionLabel) {
    }

    public record SectionSummary(Long sectionId, String sectionName,
                                  int projectCount, int overdueCount, String completionLabel) {
    }
}
