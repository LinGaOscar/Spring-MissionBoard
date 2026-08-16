package com.missionboard.task;

import com.missionboard.user.User;
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

    private User user(Long id, String displayName) {
        User u = new User();
        u.setId(id);
        u.setDisplayName(displayName);
        return u;
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
            assertThat(cellText(sheet.getRow(2), 4)).isEqualTo("進行中");
            assertThat(cellText(sheet.getRow(2), 5)).isEqualTo("高");
            assertThat(cellText(sheet.getRow(3), 0)).isEqualTo("SIT");
            assertThat(cellText(sheet.getRow(3), 1)).isEqualTo("程式開發");
            assertThat(cellText(sheet.getRow(3), 2)).isEqualTo("子類任務");
            assertThat(cellText(sheet.getRow(3), 4)).isEqualTo("已完成");
            assertThat(cellText(sheet.getRow(3), 5)).isEqualTo("低");
        }
    }

    // 同一分組（此測試用未歸類群組，避免夾雜階段/子類分組邏輯干擾）內的多筆任務，
    // 驗證 sortedByDueDate() 的完整排序規則：到期日升冪 → 無到期日排最後 → 同值（含皆為 null）依 id 升冪
    @Test
    void sortsTasksWithinSameGroupByDueDateAscendingThenId() throws Exception {
        Task laterDate = task(100L, null, "T1_較晚到期", Task.Status.NOT_STARTED, null, LocalDate.of(2026, 8, 20));
        Task earlierDateSmallerId = task(101L, null, "T2_較早到期_id較小", Task.Status.NOT_STARTED, null, LocalDate.of(2026, 8, 10));
        Task noDateSmallerId = task(102L, null, "T4_無到期_id較小", Task.Status.NOT_STARTED, null, null);
        Task noDateLargerId = task(103L, null, "T3_無到期_id較大", Task.Status.NOT_STARTED, null, null);
        Task earlierDateLargerId = task(105L, null, "T5_較早到期_id較大", Task.Status.NOT_STARTED, null, LocalDate.of(2026, 8, 10));

        // 輸入順序刻意讓同組（同到期日／皆無到期日）內較大 id 排在較小 id 之前，
        // 若 sortedByDueDate() 的 id tiebreak 失效，Stream.sorted() 的穩定排序會直接照輸入順序（較大 id 在前）通過，
        // 蓋掉 tiebreak 沒作用的問題；刻意反轉輸入順序才能讓斷言真正驗證到 tiebreak 邏輯
        byte[] xlsx = service.toXlsx(
            List.of(laterDate, earlierDateLargerId, noDateLargerId, earlierDateSmallerId, noDateSmallerId),
            List.of());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            // 同到期日（08-10）依 id 升冪：101 在 105 之前
            assertThat(cellText(sheet.getRow(1), 2)).isEqualTo("T2_較早到期_id較小");
            assertThat(cellText(sheet.getRow(2), 2)).isEqualTo("T5_較早到期_id較大");
            // 較晚到期日（08-20）排在較早到期日之後
            assertThat(cellText(sheet.getRow(3), 2)).isEqualTo("T1_較晚到期");
            // 無到期日排在所有有到期日任務之後，且同為 null 依 id 升冪：102 在 103 之前
            assertThat(cellText(sheet.getRow(4), 2)).isEqualTo("T4_無到期_id較小");
            assertThat(cellText(sheet.getRow(5), 2)).isEqualTo("T3_無到期_id較大");
        }
    }

    @Test
    void populatesAssigneeAndStartDateWhenPresent() throws Exception {
        User assignee = user(1L, "王小明");
        Task assignedTask = task(300L, null, "已指派任務", Task.Status.IN_PROGRESS, Task.Priority.MEDIUM,
            LocalDate.of(2026, 8, 15));
        assignedTask.setAssignee(assignee);
        assignedTask.setStartDate(LocalDate.of(2026, 8, 1));

        byte[] xlsx = service.toXlsx(List.of(assignedTask), List.of());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Row row = wb.getSheetAt(0).getRow(1);
            assertThat(cellText(row, 3)).isEqualTo("王小明");
            assertThat(cellText(row, 6)).isEqualTo("2026-08-01");
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
