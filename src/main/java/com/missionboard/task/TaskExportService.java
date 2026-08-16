package com.missionboard.task;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

// WBS 檢視分頁的「匯出 Excel」：欄位與列的排序對齊 WbsView 畫面的樹狀顯示順序
// （未歸類 → 各大類 → 各大類底下的子類），不是單純的扁平任務清單
@Service
public class TaskExportService {

    private static final String[] HEADERS =
        { "大類", "子類", "任務標題", "指派人", "狀態", "優先度", "起始日", "到期日" };

    private static final Map<Task.Status, String> STATUS_LABELS = Map.of(
        Task.Status.NOT_STARTED, "未開始",
        Task.Status.IN_PROGRESS, "進行中",
        Task.Status.DONE, "已完成"
    );

    private static final Map<Task.Priority, String> PRIORITY_LABELS = Map.of(
        Task.Priority.HIGH, "高",
        Task.Priority.MEDIUM, "中",
        Task.Priority.LOW, "低"
    );

    private record ExportRow(String stageName, String categoryName, Task task) {
    }

    public byte[] toXlsx(List<Task> tasks, List<TaskCategory> categories) {
        List<ExportRow> rows = buildRows(tasks, categories);
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("任務清單");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            int rowIdx = 1;
            for (ExportRow r : rows) {
                Task t = r.task();
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(r.stageName());
                row.createCell(1).setCellValue(r.categoryName());
                row.createCell(2).setCellValue(t.getTitle());
                row.createCell(3).setCellValue(t.getAssignee() != null ? t.getAssignee().getDisplayName() : "");
                row.createCell(4).setCellValue(STATUS_LABELS.get(t.getStatus()));
                row.createCell(5).setCellValue(t.getPriority() != null ? PRIORITY_LABELS.get(t.getPriority()) : "");
                row.createCell(6).setCellValue(t.getStartDate() != null ? t.getStartDate().toString() : "");
                row.createCell(7).setCellValue(t.getDueDate() != null ? t.getDueDate().toString() : "");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<ExportRow> buildRows(List<Task> tasks, List<TaskCategory> categories) {
        List<TaskCategory> stages = categories.stream()
            .filter(c -> c.getParentCategory() == null)
            .sorted(Comparator.comparingInt(TaskCategory::getSortOrder))
            .toList();

        List<ExportRow> rows = new ArrayList<>();

        sortedByDueDate(tasksInCategory(tasks, null))
            .forEach(t -> rows.add(new ExportRow("未歸類", "", t)));

        for (TaskCategory stage : stages) {
            List<TaskCategory> children = categories.stream()
                .filter(c -> c.getParentCategory() != null && stage.getId().equals(c.getParentCategory().getId()))
                .sorted(Comparator.comparingInt(TaskCategory::getSortOrder))
                .toList();

            sortedByDueDate(tasksInCategory(tasks, stage.getId()))
                .forEach(t -> rows.add(new ExportRow(stage.getName(), "", t)));

            for (TaskCategory child : children) {
                sortedByDueDate(tasksInCategory(tasks, child.getId()))
                    .forEach(t -> rows.add(new ExportRow(stage.getName(), child.getName(), t)));
            }
        }
        return rows;
    }

    private List<Task> tasksInCategory(List<Task> tasks, Long categoryId) {
        return tasks.stream()
            .filter(t -> categoryId == null
                ? t.getCategory() == null
                : t.getCategory() != null && categoryId.equals(t.getCategory().getId()))
            .toList();
    }

    // 依到期日升冪排序，無到期日排最後，同值依 id 升冪；與前端 WbsView 的 sortByDueDate 規則一致
    private List<Task> sortedByDueDate(List<Task> tasks) {
        return tasks.stream()
            .sorted(Comparator
                .comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getId))
            .toList();
    }
}
