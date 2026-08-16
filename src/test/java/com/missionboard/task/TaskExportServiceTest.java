package com.missionboard.task;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExportServiceTest {

    private final TaskExportService service = new TaskExportService();

    private TaskCategory category(Long id, TaskCategory parent, String name, int sortOrder) {
        TaskCategory c = new TaskCategory();
        c.setId(id);
        c.setParentCategory(parent);
        c.setName(name);
        c.setSortOrder(sortOrder);
        return c;
    }

    private Task task(Long id, TaskCategory category, String title,
                       Task.Status status, Task.Priority priority, LocalDate dueDate) {
        Task t = new Task();
        t.setId(id);
        t.setCategory(category);
        t.setTitle(title);
        t.setStatus(status);
        t.setPriority(priority);
        t.setDueDate(dueDate);
        return t;
    }

    private String cellText(Row row, int col) {
        return row.getCell(col).getStringCellValue();
    }

    @Test
    void ordersRowsAsUnassignedThenStagesThenChildren() throws Exception {
        TaskCategory sit = category(1L, null, "SIT", 0);
        TaskCategory dev = category(2L, sit, "程式開發", 0);

        Task unassignedTask = task(100L, null, "未歸類任務", Task.Status.NOT_STARTED, null, null);
        Task stageTask = task(101L, sit, "階段直屬任務", Task.Status.IN_PROGRESS, Task.Priority.HIGH, LocalDate.of(2026, 8, 20));
        Task childTask = task(102L, dev, "子類任務", Task.Status.DONE, Task.Priority.LOW, LocalDate.of(2026, 8, 10));

        byte[] xlsx = service.toXlsx(List.of(childTask, stageTask, unassignedTask), List.of(sit, dev));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(cellText(sheet.getRow(0), 0)).isEqualTo("大類");
            assertThat(cellText(sheet.getRow(1), 0)).isEqualTo("未歸類");
            assertThat(cellText(sheet.getRow(1), 2)).isEqualTo("未歸類任務");
            assertThat(cellText(sheet.getRow(2), 0)).isEqualTo("SIT");
            assertThat(cellText(sheet.getRow(2), 1)).isEmpty();
            assertThat(cellText(sheet.getRow(2), 2)).isEqualTo("階段直屬任務");
            assertThat(cellText(sheet.getRow(3), 0)).isEqualTo("SIT");
            assertThat(cellText(sheet.getRow(3), 1)).isEqualTo("程式開發");
            assertThat(cellText(sheet.getRow(3), 2)).isEqualTo("子類任務");
        }
    }

    @Test
    void translatesStatusPriorityAndBlanksMissingValues() throws Exception {
        Task noPriorityNoDate = task(200L, null, "極簡任務", Task.Status.NOT_STARTED, null, null);

        byte[] xlsx = service.toXlsx(List.of(noPriorityNoDate), List.of());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row = wb.getSheetAt(0).getRow(1);
            assertThat(cellText(row, 3)).isEmpty();
            assertThat(cellText(row, 4)).isEqualTo("未開始");
            assertThat(cellText(row, 5)).isEmpty();
            assertThat(cellText(row, 6)).isEmpty();
            assertThat(cellText(row, 7)).isEmpty();
        }
    }
}
