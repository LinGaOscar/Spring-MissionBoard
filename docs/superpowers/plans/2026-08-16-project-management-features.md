# 專案管理功能（建立/封存/成員管理/Excel匯出） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 補齊四項功能的前端介面（建立專案、封存/解封存、成員管理、Excel 匯出），這四項後端 API 多數已存在但從未串接前端；順帶修補兩個既有缺口（`canArchive` 未暴露給前端、移除現任負責人無防護）。

**Architecture:** 後端三處小改動（暴露 `canArchive`/`archived`/`ownerId`、`removeMember` 加 owner 防護、新增 `TaskExportService` + 匯出端點）；前端把 `project/list.html` 從純 vanilla JS 改掛 Vue 3（新檔 `project-list.js`，比照 `project-detail.js` 寫法），加建立專案 Modal 與未封存/已封存頁籤；`project-detail.js` 根元件新增專案層級工具列（封存/解封存、成員管理面板），WBS 分頁工具列加匯出按鈕。

**Tech Stack:** Java 21 + Spring Boot（`ProjectController`/`ProjectService`/`TaskController`）、Apache POI `poi-ooxml:5.3.0`（`pom.xml` 已有依賴）、Vue 3（無 build 工具，vendored UMD，與看板/WBS 前端相同）。

## Global Constraints

- 依 [`docs/superpowers/specs/2026-08-16-project-management-features-design.md`](../specs/2026-08-16-project-management-features-design.md) 執行。
- 建立專案不需選 `section`/`owner`：後端 `ProjectService.createProject` 已自動代入建立者部門與建立者本人，前端只送 `name`/`description`。
- 封存/解封存的權限旗標是 `canArchive`（`ProjectService.canArchive`），**不是** `canWrite`——`canWrite` 在專案封存後對所有角色恆為 `false`，無法用來判斷「是否可解封存」。
- 成員管理面板的「新增成員」下拉列出**全部使用者**（`GET /api/users` 不帶 `departmentId`），不限科別（依討論結果的明確決定，不要改回科別篩選）。
- 移除現任專案負責人（owner）必須被擋下：後端 `IllegalArgumentException`（400）是最終防線，前端在 owner 那一列停用「移除」按鈕是使用者體驗層。
- Excel 匯出只在 **WBS 檢視分頁**工具列提供入口，權限用 `canRead`（唯讀角色與封存專案都能匯出）。欄位順序固定：大類／子類／任務標題／指派人／狀態／優先度／起始日／到期日；列的排序比照 WBS 樹狀顯示順序（未歸類 → 各大類 → 各大類底下的子類，同節點內依到期日升冪、無到期日排最後、同值依 id 升冪）。
- 前端沿用既有 `api()` 骨架（CSRF header、`res.json()` 失敗視為網路錯誤）、`toastMixin`、`.btn`/`.modal-overlay`/`.modal`/`.form-group` 既有 CSS class，不重新發明樣式系統。
- 前端沒有自動化測試框架，驗證方式是實際啟動應用程式、瀏覽器操作一輪並截圖存證（依專案 CLAUDE.md「有畫面就有截圖」規則）。
- 後端每個 Task 完成程式碼變動後跑 `mvn test` 確認全綠才 commit。

---

## Task 1: 後端 - 暴露 `canArchive`／`archived`／`ownerId` 給前端

**Files:**
- Modify: `src/main/java/com/missionboard/project/ProjectController.java:28-38`
- Modify: `src/main/resources/templates/project/detail.html:14`
- Test: `src/test/java/com/missionboard/project/ProjectDetailPageTest.java`

**Interfaces:**
- Consumes: 既有 `ProjectService.canArchive(Long, User)`（已存在且已測試，`ProjectService.java:58-66`）
- Produces: `GET /projects/{id}` 的 Thymeleaf model 新增 `canArchive`(boolean)、`archived`(boolean)、`ownerId`(Long)；`#detail-app` 新增 `data-can-archive`/`data-archived`/`data-owner-id` 屬性。Task 6 消費 `canArchive`/`archived`，Task 7 消費 `ownerId`。

- [ ] **Step 1: 在 `ProjectDetailPageTest.java` 新增失敗測試**

在 `detailPageRedirectsForUserWithoutReadAccess` 測試方法之後插入：

```java
    @Test
    void detailPageExposesArchiveRelatedModelAttributes() throws Exception {
        Cookie session = loginAs("leaderX");

        mockMvc.perform(get("/projects/" + project.getId()).cookie(session))
            .andExpect(status().isOk())
            .andExpect(model().attribute("canArchive", true))
            .andExpect(model().attribute("archived", false))
            .andExpect(model().attribute("ownerId", project.getOwner().getId()));
    }
```

在檔案頂部 import 區塊新增：

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectDetailPageTest#detailPageExposesArchiveRelatedModelAttributes`
Expected: FAIL（`canArchive`/`archived`/`ownerId` 目前不在 model 裡）

- [ ] **Step 3: 修改 `ProjectController.detail()`**

現況（`ProjectController.java:28-38`）：

```java
    @GetMapping("/projects/{id}")
    public String detail(@PathVariable Long id, org.springframework.ui.Model model, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            return "redirect:/projects";
        }
        model.addAttribute("projectId", id);
        model.addAttribute("canWrite", projectService.canWrite(id, user));
        model.addAttribute("sectionId", projectService.getById(id).getSection().getId());
        return "project/detail";
    }
```

改成：

```java
    @GetMapping("/projects/{id}")
    public String detail(@PathVariable Long id, org.springframework.ui.Model model, Principal principal) {
        User user = currentUser(principal);
        if (!projectService.canRead(id, user)) {
            return "redirect:/projects";
        }
        Project project = projectService.getById(id);
        model.addAttribute("projectId", id);
        model.addAttribute("canWrite", projectService.canWrite(id, user));
        model.addAttribute("canArchive", projectService.canArchive(id, user));
        model.addAttribute("archived", project.isArchived());
        model.addAttribute("ownerId", project.getOwner().getId());
        model.addAttribute("sectionId", project.getSection().getId());
        return "project/detail";
    }
```

- [ ] **Step 4: 修改 `detail.html` 的 `#detail-app` 屬性**

現況（`detail.html:14`）：

```html
    <div id="detail-app" th:data-project-id="${projectId}" th:data-can-write="${canWrite}" th:data-section-id="${sectionId}">
```

改成：

```html
    <div id="detail-app" th:data-project-id="${projectId}" th:data-can-write="${canWrite}"
         th:data-can-archive="${canArchive}" th:data-archived="${archived}" th:data-owner-id="${ownerId}"
         th:data-section-id="${sectionId}">
```

- [ ] **Step 5: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectDetailPageTest`
Expected: PASS（全部 4 個測試，含新增的）

- [ ] **Step 6: 全量測試 + Commit**

```bash
mvn test
```
Expected: BUILD SUCCESS

```bash
git add src/main/java/com/missionboard/project/ProjectController.java \
        src/main/resources/templates/project/detail.html \
        src/test/java/com/missionboard/project/ProjectDetailPageTest.java
git commit -m "feat: 專案詳情頁暴露 canArchive/archived/ownerId 給前端"
```

---

## Task 2: 後端 - 防止移除現任專案負責人

**Files:**
- Modify: `src/main/java/com/missionboard/project/ProjectService.java:131-144`
- Test: `src/test/java/com/missionboard/project/ProjectServiceTest.java`
- Test: `src/test/java/com/missionboard/project/ProjectControllerTest.java`

**Interfaces:**
- Consumes: 無新依賴
- Produces: `ProjectService.removeMember(Long projectId, Long userId)` 在 `userId` 等於專案 owner 時拋 `IllegalArgumentException("無法移除專案負責人，請先轉移負責人")`（→ `GlobalExceptionHandler` 轉 400）。Task 7 的前端「成員管理」面板依此在 owner 列停用移除按鈕。

- [ ] **Step 1: 在 `ProjectServiceTest.java` 新增失敗測試**

在 `removeMemberThrowsNotFoundForNonMemberUser` 之後插入：

```java
    @Test
    void removeMemberThrowsWhenRemovingCurrentOwner() {
        assertThatThrownBy(() -> projectService.removeMember(projectA.getId(), leaderA.getId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("請先轉移負責人");

        assertThat(projectMemberRepository.existsByIdProjectIdAndIdUserId(projectA.getId(), leaderA.getId())).isTrue();
    }
```

（`leaderA` 在 `setUp()` 中已是 `projectA` 的 owner 且是成員，見 `ProjectServiceTest.java:66-70`）

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=ProjectServiceTest#removeMemberThrowsWhenRemovingCurrentOwner`
Expected: FAIL（目前會直接刪除成功，不拋例外）

- [ ] **Step 3: 修改 `ProjectService.removeMember()`**

現況（`ProjectService.java:131-144`）：

```java
    // 移除成員時連動清除其在該專案下的任務指派（CLAUDE.md 核心規則）
    @Transactional
    public void removeMember(Long projectId, Long userId) {
        // 檢查成員是否存在，不存在則拋 404（符合 spec 要求）
        if (!projectMemberRepository.existsByIdProjectIdAndIdUserId(projectId, userId)) {
            throw new EntityNotFoundException("該使用者不是此專案成員");
        }
        projectMemberRepository.deleteById(new ProjectMemberId(projectId, userId));
        // 顯式 flush：deleteById 找到的實體常已存在於一級快取（先前查詢留下），
        // 刪除動作會延後到 flush 才真正送出 DELETE；同交易內若緊接著查詢
        // project_members（本方法呼叫端或測試斷言）不保證觸發 auto-flush，
        // 會讀到「看似還沒刪除」的結果，故此處立即 flush 確保刪除立即可見
        projectMemberRepository.flush();
        taskRepository.clearAssigneeForUserInProject(projectId, userId);
    }
```

改成：

```java
    // 移除成員時連動清除其在該專案下的任務指派（CLAUDE.md 核心規則）
    @Transactional
    public void removeMember(Long projectId, Long userId) {
        // 檢查成員是否存在，不存在則拋 404（符合 spec 要求）
        if (!projectMemberRepository.existsByIdProjectIdAndIdUserId(projectId, userId)) {
            throw new EntityNotFoundException("該使用者不是此專案成員");
        }
        // 現任負責人不可被移除，否則 project.owner 會指向非成員的髒資料；
        // 前端「成員管理」面板已停用 owner 列的移除按鈕，這裡是繞過前端直接呼叫 API 的最終防線
        Project project = getById(projectId);
        if (project.getOwner().getId().equals(userId)) {
            throw new IllegalArgumentException("無法移除專案負責人，請先轉移負責人");
        }
        projectMemberRepository.deleteById(new ProjectMemberId(projectId, userId));
        // 顯式 flush：deleteById 找到的實體常已存在於一級快取（先前查詢留下），
        // 刪除動作會延後到 flush 才真正送出 DELETE；同交易內若緊接著查詢
        // project_members（本方法呼叫端或測試斷言）不保證觸發 auto-flush，
        // 會讀到「看似還沒刪除」的結果，故此處立即 flush 確保刪除立即可見
        projectMemberRepository.flush();
        taskRepository.clearAssigneeForUserInProject(projectId, userId);
    }
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectServiceTest`
Expected: PASS（全部通過）

- [ ] **Step 5: 在 `ProjectControllerTest.java` 新增端點層測試**

在 `memberManagementDeniedForOutsideSectionChief` 之後插入：

```java
    @Test
    void removeMemberDeniedWhenTargetIsCurrentOwner() throws Exception {
        Cookie session = loginAs("chiefA");

        mockMvc.perform(delete("/api/projects/" + projectA.getId() + "/members/" + leaderA.getId())
                .cookie(session).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));

        assertThat(projectMemberRepository.existsByIdProjectIdAndIdUserId(projectA.getId(), leaderA.getId())).isTrue();
    }
```

- [ ] **Step 6: 執行測試確認通過**

Run: `mvn test -Dtest=ProjectControllerTest`
Expected: PASS

- [ ] **Step 7: 全量測試 + Commit**

```bash
mvn test
```
Expected: BUILD SUCCESS

```bash
git add src/main/java/com/missionboard/project/ProjectService.java \
        src/test/java/com/missionboard/project/ProjectServiceTest.java \
        src/test/java/com/missionboard/project/ProjectControllerTest.java
git commit -m "fix: 移除專案成員時擋下現任負責人，避免 owner 指向非成員"
```

---

## Task 3: 後端 - Excel 匯出 Service 與端點

**Files:**
- Create: `src/main/java/com/missionboard/task/TaskExportService.java`
- Create: `src/test/java/com/missionboard/task/TaskExportServiceTest.java`
- Modify: `src/main/java/com/missionboard/task/TaskController.java`
- Modify: `src/test/java/com/missionboard/task/TaskControllerTest.java`

**Interfaces:**
- Consumes: `TaskService.list(Long projectId)`（既有，回傳 `List<Task>`）、`TaskCategoryService.list(Long projectId)`（既有，回傳 `List<TaskCategory>`）、`ProjectService.canRead`（既有）
- Produces: `TaskExportService.toXlsx(List<Task> tasks, List<TaskCategory> categories)` 回傳 `byte[]`；`GET /api/projects/{projectId}/export.xlsx` 端點，回傳 `ResponseEntity<byte[]>`（`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`）。Task 8 的前端直接用 `window.location.href` 導向此 URL 觸發下載。

### Step 1-4: `TaskExportService` 本體（純 POJO 單元測試，不需 Spring context）

- [ ] **Step 1: 建立 `TaskExportServiceTest.java` 並寫失敗測試**

```java
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
```

- [ ] **Step 2: 執行測試確認失敗（編譯失敗，`TaskExportService` 不存在）**

Run: `mvn test -Dtest=TaskExportServiceTest`
Expected: FAIL（編譯錯誤：找不到 `TaskExportService`）

- [ ] **Step 3: 建立 `TaskExportService.java`**

```java
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
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=TaskExportServiceTest`
Expected: PASS（2 個測試）

### Step 5-8: 匯出端點與權限測試

- [ ] **Step 5: 在 `TaskControllerTest.java` 新增失敗測試**

在檔案最後一個測試方法 `updateAssigneeRejectsNonMember` 之後插入：

```java
    @Test
    void exportXlsxSucceedsForArchivedProject() throws Exception {
        Cookie session = loginAs("leaderA");
        mockMvc.perform(post("/api/projects/{id}/tasks", project.getId()).cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"categoryId\":null,\"title\":\"任務\"}"))
            .andExpect(status().isOk());

        project.setArchived(true);
        projectRepository.save(project);

        mockMvc.perform(get("/api/projects/{id}/export.xlsx", project.getId()).cookie(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("filename*=UTF-8''"));
    }

    @Test
    void exportXlsxDeniedForOutsideSectionUser() throws Exception {
        Cookie session = loginAs("chiefB");

        mockMvc.perform(get("/api/projects/{id}/export.xlsx", project.getId()).cookie(session))
            .andExpect(status().isForbidden());
    }
```

- [ ] **Step 6: 執行測試確認失敗（404，端點不存在）**

Run: `mvn test -Dtest=TaskControllerTest#exportXlsxSucceedsForArchivedProject`
Expected: FAIL

- [ ] **Step 7: 修改 `TaskController.java`**

在檔案頂部 import 區塊新增（`ProjectService` 已存在於既有 import，不重複加）：

```java
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
```

在 `TaskController` 建構子注入新依賴。現況：

```java
    private final TaskService taskService;
    private final ProjectService projectService;
    private final UserRepository userRepository;
```

改成：

```java
    private final TaskService taskService;
    private final TaskCategoryService taskCategoryService;
    private final TaskExportService taskExportService;
    private final ProjectService projectService;
    private final UserRepository userRepository;
```

在 `move` 端點方法之後（`TaskController.java:65-71` 之後）新增：

```java
    // 匯出是唯讀操作：凡可檢視此專案者（含封存、跨科唯讀角色）皆可使用，權限比照 list() 用 canRead
    @GetMapping("/api/projects/{projectId}/export.xlsx")
    public ResponseEntity<byte[]> export(@PathVariable Long projectId, Principal principal) {
        checkRead(projectId, principal);
        byte[] content = taskExportService.toXlsx(
            taskService.list(projectId), taskCategoryService.list(projectId));
        String filename = projectService.getById(projectId).getName() + "_任務清單.xlsx";
        return download(content, filename,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    // 檔名含中文，用 RFC 5987 filename* 編碼，避免部分瀏覽器/下載工具把中文檔名截斷或亂碼
    private ResponseEntity<byte[]> download(byte[] body, String filename, String contentType) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
            .contentType(MediaType.parseMediaType(contentType))
            .body(body);
    }
```

- [ ] **Step 8: 執行測試確認通過**

Run: `mvn test -Dtest=TaskControllerTest`
Expected: PASS（全部通過）

- [ ] **Step 9: 全量測試 + Commit**

```bash
mvn test
```
Expected: BUILD SUCCESS

```bash
git add src/main/java/com/missionboard/task/TaskExportService.java \
        src/main/java/com/missionboard/task/TaskController.java \
        src/test/java/com/missionboard/task/TaskExportServiceTest.java \
        src/test/java/com/missionboard/task/TaskControllerTest.java
git commit -m "feat: 新增任務清單 Excel 匯出端點（WBS 樹狀順序）"
```

---

## Task 4: 前端 - 專案列表頁改掛 Vue 3 + 未封存/已封存頁籤

**Files:**
- Create: `src/main/resources/static/js/project-list.js`
- Modify: `src/main/resources/templates/project/list.html`

**Interfaces:**
- Consumes: 既有 `GET /api/projects?archived={bool}`（既有 `archived` 參數，`ProjectController.java:42-48`）
- Produces: 掛載於 `#list-app` 的 Vue app；Task 5 會在同一個 app 上加建立專案 Modal。

**這個 Task 只做「行為對等的技術轉換 + 加頁籤」，不做建立專案（Task 5 再加），降低單一 Task 的驗收範圍。**

- [ ] **Step 1: 改寫 `list.html`**

現況（完整檔案）：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <title>專案列表 - MissionBoard 任務管理系統</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<div th:replace="~{fragments/header :: header}"></div>
<div class="layout">
  <div th:replace="~{fragments/sidebar :: sidebar}"></div>
  <main class="main-content">
    <div class="page-header">
      <h1>專案列表</h1>
    </div>
    <div id="project-grid" class="project-grid"></div>
  </main>
</div>
<div th:replace="~{fragments/footer :: footer}"></div>
<script>
  // 純伺服器渲染頁的最小互動：抓取後用 DOM API 逐一組卡片，避免 innerHTML 拼接使用者輸入內容導致 XSS
  fetch('/api/projects')
    .then(function (res) { return res.json(); })
    .then(function (body) {
      var grid = document.getElementById('project-grid');
      if (!body.success || body.data.length === 0) {
        grid.textContent = '目前沒有專案';
        return;
      }
      body.data.forEach(function (project) {
        // 卡片本身就是連結，維持與檔案其他部分一致的 DOM API 組裝方式（不用 innerHTML，避免專案名稱夾帶 XSS）
        var card = document.createElement('a');
        card.className = 'project-card';
        card.href = '/projects/' + project.id;

        var name = document.createElement('div');
        name.className = 'project-card-name';
        name.textContent = project.name;

        var meta = document.createElement('div');
        meta.className = 'project-card-meta';
        meta.textContent = project.sectionName + ' · 負責人：' + project.ownerDisplayName;

        card.appendChild(name);
        card.appendChild(meta);
        grid.appendChild(card);
      });
    });
</script>
</body>
</html>
```

改成：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <title>專案列表 - MissionBoard 任務管理系統</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
  <script th:src="@{/js/vue.global.prod.min.js}"></script>
</head>
<body>
<div th:replace="~{fragments/header :: header}"></div>
<div class="layout">
  <div th:replace="~{fragments/sidebar :: sidebar}"></div>
  <main class="main-content">
    <div id="list-app">
      <p>載入中...</p>
    </div>
  </main>
</div>
<div th:replace="~{fragments/footer :: footer}"></div>
<script th:src="@{/js/project-list.js}"></script>
</body>
</html>
```

（Vue 版本用 Vue 的 `{{ }}` 插值輸出文字內容，不是 `innerHTML`，同樣不會有 XSS 風險，等同於原本手刻 DOM API 的安全性）

- [ ] **Step 2: 建立 `project-list.js`**

```javascript
(function () {
  const { createApp } = Vue;

  async function api(url, options = {}) {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
    try {
      const headers = { [csrfHeader]: csrfToken };
      if (options.body) headers['Content-Type'] = 'application/json';
      const res = await fetch(url, { ...options, headers: { ...headers, ...(options.headers || {}) } });
      return await res.json();
    } catch (e) {
      return { success: false, message: '網路錯誤，請稍後再試' };
    }
  }

  const app = createApp({
    data() {
      return { projects: [], loading: true, archived: false, errorMessage: '' };
    },
    methods: {
      async loadProjects() {
        this.loading = true;
        const result = await api(`/api/projects?archived=${this.archived}`);
        if (result.success) {
          this.projects = result.data;
          this.errorMessage = '';
        } else {
          this.projects = [];
          this.errorMessage = result.message || '載入失敗，請重新整理';
        }
        this.loading = false;
      },
      switchTab(archived) {
        if (this.archived === archived) return;
        this.archived = archived;
        this.loadProjects();
      },
    },
    mounted() {
      this.loadProjects();
    },
    template: `
      <div>
        <div class="page-header">
          <h1>專案列表</h1>
        </div>
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': !archived }" @click="switchTab(false)">未封存</button>
          <button class="btn" :class="{ 'btn-primary': archived }" @click="switchTab(true)">已封存</button>
        </div>
        <p v-if="loading">載入中...</p>
        <p v-else-if="errorMessage" class="alert alert-error">{{ errorMessage }}</p>
        <p v-else-if="!projects.length" style="color:#636e72">目前沒有{{ archived ? '已封存' : '' }}專案</p>
        <div v-else class="project-grid">
          <a v-for="p in projects" :key="p.id" class="project-card" :href="'/projects/' + p.id">
            <div class="project-card-name">{{ p.name }}</div>
            <div class="project-card-meta">{{ p.sectionName }} · 負責人：{{ p.ownerDisplayName }}</div>
          </a>
        </div>
      </div>
    `,
  });

  app.mount('#list-app');
})();
```

（頁籤重用既有 `.detail-tabs` CSS class，與 `project-detail.js` 的三分頁按鈕同一份樣式，不需新增 CSS）

- [ ] **Step 3: 啟動應用程式，手動驗證**

```bash
docker compose up -d
mvn spring-boot:run
```

以 `leader`/`password123` 登入，開啟 `/projects`：

1. 頁面正常顯示「未封存」頁籤下的「MissionBoard 範例專案」卡片，內容（名稱、科別、負責人）與改動前一致
2. 點卡片仍可正常進入 `/projects/{id}` 詳情頁
3. 點「已封存」頁籤，因目前無已封存專案，顯示「目前沒有已封存專案」
4. console 無錯誤
5. 依專案 CLAUDE.md「有畫面就有截圖」規則截圖存證（未封存頁籤畫面一張）

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/project/list.html src/main/resources/static/js/project-list.js
git commit -m "refactor: 專案列表頁改掛 Vue 3，新增未封存/已封存頁籤"
```

---

## Task 5: 前端 - 建立專案 Modal

**Files:**
- Modify: `src/main/resources/static/js/project-list.js`

**Interfaces:**
- Consumes: 既有 `POST /api/projects`（`ProjectController.java:50-57`，body: `{name, description}`）
- Produces: 無（葉節點功能，本 Task 是列表頁最後一項前端改動）

- [ ] **Step 1: 在 `project-list.js` 的 `data()` 加入 Modal 狀態**

現況：

```javascript
    data() {
      return { projects: [], loading: true, archived: false, errorMessage: '' };
    },
```

改成：

```javascript
    data() {
      return {
        projects: [], loading: true, archived: false, errorMessage: '',
        createModal: { open: false, name: '', description: '', error: '' },
      };
    },
```

- [ ] **Step 2: 在 `methods` 加入 Modal 操作方法**

在 `switchTab` 方法之後插入：

```javascript
      openCreateModal() {
        this.createModal = { open: true, name: '', description: '', error: '' };
      },
      closeCreateModal() {
        this.createModal.open = false;
      },
      async submitCreateProject() {
        const name = this.createModal.name.trim();
        if (!name) {
          this.createModal.error = '名稱不可為空';
          return;
        }
        const result = await api('/api/projects', {
          method: 'POST',
          body: JSON.stringify({ name, description: this.createModal.description || null }),
        });
        if (result.success) {
          window.location.href = '/projects/' + result.data.id;
        } else {
          this.createModal.error = result.message || '建立失敗';
        }
      },
```

- [ ] **Step 3: 修改 template：頁首加按鈕、加 Modal**

現況：

```javascript
    template: `
      <div>
        <div class="page-header">
          <h1>專案列表</h1>
        </div>
        <div class="detail-tabs">
```

改成：

```javascript
    template: `
      <div>
        <div class="page-header">
          <h1>專案列表</h1>
          <button class="btn btn-primary" @click="openCreateModal">新增專案</button>
        </div>
        <div class="detail-tabs">
```

在 template 字串結尾（`</div>` 收尾之前，即最外層 `<div>` 結束標籤之前）加入 Modal 區塊：

現況（template 結尾）：

```javascript
          <a v-for="p in projects" :key="p.id" class="project-card" :href="'/projects/' + p.id">
            <div class="project-card-name">{{ p.name }}</div>
            <div class="project-card-meta">{{ p.sectionName }} · 負責人：{{ p.ownerDisplayName }}</div>
          </a>
        </div>
      </div>
    `,
```

改成：

```javascript
          <a v-for="p in projects" :key="p.id" class="project-card" :href="'/projects/' + p.id">
            <div class="project-card-name">{{ p.name }}</div>
            <div class="project-card-meta">{{ p.sectionName }} · 負責人：{{ p.ownerDisplayName }}</div>
          </a>
        </div>
        <div v-if="createModal.open" class="modal-overlay" @click.self="closeCreateModal">
          <div class="modal">
            <h3>新增專案</h3>
            <p v-if="createModal.error" class="alert alert-error">{{ createModal.error }}</p>
            <div class="form-group"><label>名稱</label><input v-model="createModal.name" /></div>
            <div class="form-group"><label>描述</label><textarea v-model="createModal.description" rows="4"></textarea></div>
            <div class="modal-actions">
              <button class="btn btn-primary" @click="submitCreateProject">建立</button>
              <button class="btn" @click="closeCreateModal">取消</button>
            </div>
          </div>
        </div>
      </div>
    `,
```

（`.modal-overlay`/`.modal`/`.form-group`/`.modal-actions`/`.alert-error` 皆為既有 CSS class，與 `TaskModal` 同款樣式，不需新增 CSS）

- [ ] **Step 4: 啟動應用程式，手動驗證**

```bash
docker compose up -d
mvn spring-boot:run
```

以 `leader`/`password123` 登入，開啟 `/projects`：

1. 點「新增專案」開啟 Modal
2. 名稱留空直接點「建立」，顯示錯誤「名稱不可為空」，Modal 不關閉
3. 填入名稱「測試專案」、描述「驗證用」，點「建立」，成功導向新專案的 `/projects/{id}` 詳情頁
4. 回到 `/projects`，「未封存」頁籤可看到新建立的「測試專案」卡片，負責人為 `leader`
5. console 無錯誤
6. 截圖存證（Modal 開啟畫面一張）

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/project-list.js
git commit -m "feat: 專案列表頁新增「建立專案」Modal"
```

---

## Task 6: 前端 - 專案詳情頁封存/解封存按鈕

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: 既有 `PATCH /api/projects/{id}/archive`、`PATCH /api/projects/{id}/unarchive`（`ProjectController.java:69-81`）；Task 1 產出的 `data-can-archive`/`data-archived`
- Produces: 根元件 `data()` 新增 `canArchive`(boolean, 唯讀)、`archived`(boolean, 響應式)；Task 7 會在同一個工具列旁加「成員管理」按鈕

- [ ] **Step 1: 讀取新的 dataset 屬性**

現況（`project-detail.js:4-7`）：

```javascript
  const el = document.getElementById('detail-app');
  const projectId = Number(el.dataset.projectId);
  const canWrite = el.dataset.canWrite === 'true';
  const sectionId = el.dataset.sectionId ? Number(el.dataset.sectionId) : null;
```

改成：

```javascript
  const el = document.getElementById('detail-app');
  const projectId = Number(el.dataset.projectId);
  const canWrite = el.dataset.canWrite === 'true';
  const canArchive = el.dataset.canArchive === 'true';
  const archived = el.dataset.archived === 'true';
  const sectionId = el.dataset.sectionId ? Number(el.dataset.sectionId) : null;
```

- [ ] **Step 2: 修改根元件 `app`（`createApp` 呼叫）**

現況（`project-detail.js:839-863`）：

```javascript
  const app = createApp({
    data() {
      return { projectId, canWrite, sectionId, activeTab: 'kanban' };
    },
    template: `
      <div>
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'wbs' }" @click="activeTab = 'wbs'">WBS 檢視</button>
        </div>
        <kanban-view v-if="activeTab === 'kanban'" :project-id="projectId" :can-write="canWrite" :section-id="sectionId" />
        <assignment-view v-else-if="activeTab === 'assignment'" :project-id="projectId" :can-write="canWrite" />
        <wbs-view v-else :project-id="projectId" :can-write="canWrite" />
      </div>
    `,
  });

  app.component('kanban-view', KanbanView);
  app.component('assignment-view', AssignmentView);
  app.component('task-modal', TaskModal);
  app.component('wbs-task-row', WbsTaskRow);
  app.component('wbs-view', WbsView);
  app.mount('#detail-app');
})();
```

改成：

```javascript
  const app = createApp({
    mixins: [toastMixin],
    data() {
      return { projectId, canWrite, canArchive, archived, sectionId, activeTab: 'kanban' };
    },
    methods: {
      async archiveProject() {
        const result = await api(`/api/projects/${this.projectId}/archive`, { method: 'PATCH' });
        if (result.success) {
          this.archived = true;
          // 封存後任何角色的 canWrite 恆為 false（ProjectService.canWrite 的鏡射邏輯），不需要重新查詢後端
          this.canWrite = false;
        } else {
          this.showToast(result.message || '封存失敗');
        }
      },
      async unarchiveProject() {
        const result = await api(`/api/projects/${this.projectId}/unarchive`, { method: 'PATCH' });
        if (result.success) {
          this.archived = false;
          // 未封存時 canWrite 與 canArchive 的角色判斷邏輯完全相同（見 ProjectService.canWrite/canArchive），可直接沿用
          this.canWrite = this.canArchive;
        } else {
          this.showToast(result.message || '解封存失敗');
        }
      },
    },
    template: `
      <div>
        <div class="project-toolbar">
          <span v-if="archived" class="archived-badge">已封存</span>
          <button v-if="canArchive && !archived" class="btn" @click="archiveProject">封存</button>
          <button v-if="canArchive && archived" class="btn" @click="unarchiveProject">解封存</button>
        </div>
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'wbs' }" @click="activeTab = 'wbs'">WBS 檢視</button>
        </div>
        <kanban-view v-if="activeTab === 'kanban'" :project-id="projectId" :can-write="canWrite" :section-id="sectionId" />
        <assignment-view v-else-if="activeTab === 'assignment'" :project-id="projectId" :can-write="canWrite" />
        <wbs-view v-else :project-id="projectId" :can-write="canWrite" />
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
  });

  app.component('kanban-view', KanbanView);
  app.component('assignment-view', AssignmentView);
  app.component('task-modal', TaskModal);
  app.component('wbs-task-row', WbsTaskRow);
  app.component('wbs-view', WbsView);
  app.mount('#detail-app');
})();
```

- [ ] **Step 3: `app.css` 加工具列樣式**

在檔尾（既有 `.wbs-task-due.overdue` 規則之後）加：

```css
.project-toolbar { display: flex; gap: 0.5rem; align-items: center; margin-bottom: 1rem; }
.archived-badge { background: #ffe0e0; color: #c0392b; padding: 0.25rem 0.6rem; border-radius: 4px; font-size: 0.8rem; font-weight: 600; }
```

- [ ] **Step 4: 啟動應用程式，手動驗證**

```bash
docker compose up -d
mvn spring-boot:run
```

1. 以 `chief`/`password123` 登入「MissionBoard 範例專案」，工具列出現「封存」按鈕（無「已封存」標籤）
2. 點「封存」，按鈕變成「解封存」、出現紅色「已封存」標籤；看板分頁「新增任務」按鈕消失（`canWrite` 已變 `false`）
3. 點「解封存」，恢復未封存狀態、「新增任務」按鈕重新出現
4. 以 `director`/`password123` 登入同一專案，工具列不顯示任何封存/解封存按鈕（`canArchive` 為 `false`）
5. 以 `member`/`password123` 登入自己是成員的專案，工具列顯示「封存」按鈕（`PROJECT_MEMBER` 的 `canArchive` 與 `PROJECT_LEADER` 相同）
6. console 無錯誤
7. 截圖存證（封存前/封存後各一張）

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 專案詳情頁新增封存/解封存按鈕"
```

---

## Task 7: 前端 - 成員管理面板

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: 既有 `GET /api/projects/{id}/members`、`POST .../members`、`DELETE .../members/{userId}`、`PUT .../owner`（`ProjectController.java:83-128`）、`GET /api/users`（`UserController.java`，全部使用者）、Task 1 產出的 `data-owner-id`、Task 2 的 owner 移除後端防護
- Produces: 根元件新增 `dataVersion`（number，每次成員異動遞增）；`KanbanView`/`AssignmentView`/`WbsView` 新增 `dataVersion` prop + `watch`，異動後重新載入資料，避免當前分頁殘留舊指派人顯示直到手動重新整理

**設計依據的落地方式**：spec 原文提到「移除成員時清空本地 `tasks` 快照中的 `assigneeId`」，但成員管理面板現在位於根元件，`tasks` 陣列實際存在於各分頁子元件（`KanbanView`/`WbsView` 的 `taskBoardMixin`、`AssignmentView` 自己的 `loadAll`），根元件拿不到子元件內部陣列的參照。改用更簡單且效果相同的機制：根元件維護一個 `dataVersion` 計數器，成員異動成功後遞增；三個分頁元件都 `watch` 這個 prop，變動時呼叫自己既有的 `loadAll()` 整批重新抓取。因為分頁切換本來就是 `v-if`（非當前分頁不會掛載），這個機制只會讓「當前顯示中」的分頁重新整理，符合「不用整頁重新整理」的原始要求。

- [ ] **Step 1: 讀取 `ownerId` dataset**

現況（Task 6 完成後的 `project-detail.js` 開頭）：

```javascript
  const el = document.getElementById('detail-app');
  const projectId = Number(el.dataset.projectId);
  const canWrite = el.dataset.canWrite === 'true';
  const canArchive = el.dataset.canArchive === 'true';
  const archived = el.dataset.archived === 'true';
  const sectionId = el.dataset.sectionId ? Number(el.dataset.sectionId) : null;
```

改成：

```javascript
  const el = document.getElementById('detail-app');
  const projectId = Number(el.dataset.projectId);
  const canWrite = el.dataset.canWrite === 'true';
  const canArchive = el.dataset.canArchive === 'true';
  const archived = el.dataset.archived === 'true';
  const ownerId = el.dataset.ownerId ? Number(el.dataset.ownerId) : null;
  const sectionId = el.dataset.sectionId ? Number(el.dataset.sectionId) : null;
```

- [ ] **Step 2: 在 `KanbanView`、`AssignmentView`、`WbsView` 加 `dataVersion` prop 與 `watch`**

`KanbanView` 現況 props（`project-detail.js:251-255`）：

```javascript
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
      sectionId: { type: Number, default: null },
    },
```

改成：

```javascript
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
      sectionId: { type: Number, default: null },
      dataVersion: { type: Number, default: 0 },
    },
    watch: {
      dataVersion() {
        this.loadAll();
      },
    },
```

`AssignmentView` 現況 props（`project-detail.js:563-566`）：

```javascript
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
    },
```

改成：

```javascript
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
      dataVersion: { type: Number, default: 0 },
    },
    watch: {
      dataVersion() {
        this.loadAll();
      },
    },
```

`WbsView` 現況 props（`project-detail.js:707-710`）：

```javascript
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
    },
```

改成：

```javascript
    props: {
      projectId: { type: Number, required: true },
      canWrite: { type: Boolean, default: false },
      dataVersion: { type: Number, default: 0 },
    },
    watch: {
      dataVersion() {
        this.loadAll();
      },
    },
```

- [ ] **Step 3: 根元件 `data()` 加成員管理相關 state**

Task 6 完成後的現況：

```javascript
    data() {
      return { projectId, canWrite, canArchive, archived, sectionId, activeTab: 'kanban' };
    },
```

改成：

```javascript
    data() {
      return {
        projectId, canWrite, canArchive, archived, sectionId, activeTab: 'kanban',
        ownerId, dataVersion: 0,
        memberPanelOpen: false, membersLoaded: false, membersLoading: false,
        members: [], allUsers: [], addingUserId: null, changingOwnerId: null,
      };
    },
    computed: {
      availableUsers() {
        const memberIds = new Set(this.members.map(m => m.userId));
        return this.allUsers.filter(u => !memberIds.has(u.id));
      },
    },
```

- [ ] **Step 4: 根元件 `methods` 加成員管理方法**

在 `unarchiveProject` 方法之後插入（`methods` 區塊內）：

```javascript
      async toggleMemberPanel() {
        this.memberPanelOpen = !this.memberPanelOpen;
        if (this.memberPanelOpen && !this.membersLoaded) {
          this.membersLoaded = true;
          await this.loadMembers();
        }
      },
      async loadMembers() {
        this.membersLoading = true;
        const [membersRes, usersRes] = await Promise.all([
          api(`/api/projects/${this.projectId}/members`),
          api('/api/users'),
        ]);
        this.members = membersRes.success ? membersRes.data : [];
        this.allUsers = usersRes.success ? usersRes.data : [];
        if (!membersRes.success || !usersRes.success) {
          this.showToast(membersRes.message || usersRes.message || '成員載入失敗');
        }
        this.membersLoading = false;
      },
      async addMember() {
        if (!this.addingUserId) return;
        const result = await api(`/api/projects/${this.projectId}/members`, {
          method: 'POST', body: JSON.stringify({ userId: this.addingUserId }),
        });
        this.addingUserId = null;
        if (result.success) {
          await this.loadMembers();
          this.dataVersion++;
        } else {
          this.showToast(result.message || '新增成員失敗');
        }
      },
      async removeMember(m) {
        if (m.userId === this.ownerId) return;
        if (!confirm(`確定移除成員「${m.displayName}」？`)) return;
        const result = await api(`/api/projects/${this.projectId}/members/${m.userId}`, { method: 'DELETE' });
        if (result.success) {
          await this.loadMembers();
          this.dataVersion++;
        } else {
          this.showToast(result.message || '移除成員失敗');
        }
      },
      async changeOwner() {
        if (!this.changingOwnerId || this.changingOwnerId === this.ownerId) return;
        const result = await api(`/api/projects/${this.projectId}/owner`, {
          method: 'PUT', body: JSON.stringify({ userId: this.changingOwnerId }),
        });
        if (result.success) {
          this.ownerId = this.changingOwnerId;
          this.changingOwnerId = null;
          this.dataVersion++;
        } else {
          this.showToast(result.message || '換負責人失敗');
        }
      },
```

- [ ] **Step 5: template 加成員管理按鈕、面板、把 `dataVersion` 傳給三個分頁**

現況（Task 6 完成後）：

```javascript
    template: `
      <div>
        <div class="project-toolbar">
          <span v-if="archived" class="archived-badge">已封存</span>
          <button v-if="canArchive && !archived" class="btn" @click="archiveProject">封存</button>
          <button v-if="canArchive && archived" class="btn" @click="unarchiveProject">解封存</button>
        </div>
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'wbs' }" @click="activeTab = 'wbs'">WBS 檢視</button>
        </div>
        <kanban-view v-if="activeTab === 'kanban'" :project-id="projectId" :can-write="canWrite" :section-id="sectionId" />
        <assignment-view v-else-if="activeTab === 'assignment'" :project-id="projectId" :can-write="canWrite" />
        <wbs-view v-else :project-id="projectId" :can-write="canWrite" />
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
```

改成：

```javascript
    template: `
      <div>
        <div class="project-toolbar">
          <span v-if="archived" class="archived-badge">已封存</span>
          <button v-if="canArchive && !archived" class="btn" @click="archiveProject">封存</button>
          <button v-if="canArchive && archived" class="btn" @click="unarchiveProject">解封存</button>
          <button class="btn" @click="toggleMemberPanel">成員管理 {{ memberPanelOpen ? '▴' : '▾' }}</button>
        </div>
        <div v-if="memberPanelOpen" class="member-panel">
          <p v-if="membersLoading">載入中...</p>
          <template v-else>
            <ul class="member-list">
              <li v-for="m in members" :key="m.userId" class="member-row">
                <span>{{ m.displayName }}<span v-if="m.userId === ownerId" class="member-owner-badge">負責人</span></span>
                <button v-if="canWrite" class="btn btn-sm btn-danger" :disabled="m.userId === ownerId"
                        :title="m.userId === ownerId ? '請先轉移負責人' : ''"
                        @click="removeMember(m)">移除</button>
              </li>
              <li v-if="!members.length" style="color:#636e72;font-size:0.85rem">尚無成員</li>
            </ul>
            <div v-if="canWrite" class="member-panel-actions">
              <div class="member-add-row">
                <select v-model="addingUserId">
                  <option :value="null">— 新增成員 —</option>
                  <option v-for="u in availableUsers" :key="u.id" :value="u.id">{{ u.displayName }}</option>
                </select>
                <button class="btn btn-sm btn-primary" :disabled="!addingUserId" @click="addMember">新增</button>
              </div>
              <div class="member-owner-row">
                <label>換負責人</label>
                <select v-model="changingOwnerId">
                  <option :value="null">— 選擇成員 —</option>
                  <option v-for="m in members" :key="m.userId" :value="m.userId">{{ m.displayName }}</option>
                </select>
                <button class="btn btn-sm" :disabled="!changingOwnerId || changingOwnerId === ownerId" @click="changeOwner">確認</button>
              </div>
            </div>
          </template>
        </div>
        <div class="detail-tabs">
          <button class="btn" :class="{ 'btn-primary': activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</button>
          <button class="btn" :class="{ 'btn-primary': activeTab === 'wbs' }" @click="activeTab = 'wbs'">WBS 檢視</button>
        </div>
        <kanban-view v-if="activeTab === 'kanban'" :project-id="projectId" :can-write="canWrite" :section-id="sectionId" :data-version="dataVersion" />
        <assignment-view v-else-if="activeTab === 'assignment'" :project-id="projectId" :can-write="canWrite" :data-version="dataVersion" />
        <wbs-view v-else :project-id="projectId" :can-write="canWrite" :data-version="dataVersion" />
        <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
      </div>
    `,
```

- [ ] **Step 6: `app.css` 加成員管理面板樣式**

在檔尾（Task 6 加入的 `.archived-badge` 規則之後）加：

```css
.member-panel { background: #fff; border: 1px solid #dfe6e9; border-radius: 8px; padding: 1rem 1.25rem; margin-bottom: 1.5rem; }
.member-list { list-style: none; margin-bottom: 0.75rem; }
.member-row { display: flex; justify-content: space-between; align-items: center; padding: 0.4rem 0.25rem; border-bottom: 1px solid #f0f0f0; font-size: 0.9rem; }
.member-owner-badge { background: #e8f4fd; color: #0984e3; border-radius: 10px; padding: 0.1rem 0.5rem; font-size: 0.75rem; margin-left: 0.5rem; }
.member-panel-actions { display: flex; flex-direction: column; gap: 0.6rem; padding-top: 0.6rem; border-top: 1px solid #f0f0f0; }
.member-add-row, .member-owner-row { display: flex; gap: 0.5rem; align-items: center; }
.member-add-row select, .member-owner-row select { flex: 1; padding: 0.4rem 0.6rem; border: 1px solid #b2bec3; border-radius: 4px; font-size: 0.85rem; }
.member-owner-row label { font-size: 0.85rem; color: #636e72; white-space: nowrap; }
```

- [ ] **Step 7: 啟動應用程式，手動驗證**

```bash
docker compose up -d
mvn spring-boot:run
```

以 `leader`/`password123`（「MissionBoard 範例專案」負責人）登入：

1. 點「成員管理」展開面板，看到現有成員（`leader`、`member`），`leader` 那列有「負責人」標籤且「移除」按鈕呈灰色停用狀態（滑鼠移上去顯示「請先轉移負責人」提示）
2. 從「新增成員」下拉選一位不在清單中的使用者（例如 `member2`，跨科使用者，驗證下拉不限科別），點「新增」，成員清單即時新增該筆
3. 到人員派工分頁確認新成員的欄位已出現（不需重新整理頁面，驗證 `dataVersion` 機制生效）
4. 回成員管理面板，把剛新增的成員移除，確認清單即時消失，人員派工分頁該欄同步消失
5. 換負責人：下拉選 `member`，點「確認」，`member` 那列變成「負責人」標籤且移除按鈕停用，`leader` 那列的移除按鈕恢復可點擊
6. 以 `chief`/`password123`（科長）登入同一專案，重複步驟 2-5 確認科長也能操作（`canWrite` 對科長為 `true`）
7. 以 `director`/`password123` 登入，點「成員管理」只看到唯讀清單，無任何新增/移除/換負責人的互動元件
8. console 無錯誤
9. 截圖存證（面板展開畫面一張）

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: 專案詳情頁新增成員管理面板"
```

---

## Task 8: 前端 - WBS 檢視分頁「匯出 Excel」按鈕

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js`
- Modify: `src/main/resources/static/css/app.css`

**Interfaces:**
- Consumes: Task 3 的 `GET /api/projects/{projectId}/export.xlsx`
- Produces: 無（葉節點功能）

- [ ] **Step 1: `WbsView` 加匯出方法**

在 `WbsView` 的 `methods` 區塊、`moveTaskToCategory` 方法之後插入：

```javascript
      exportXlsx() {
        // 純 GET 下載，Spring Security 預設不對 GET 做 CSRF 檢查，直接導航即可觸發瀏覽器下載，
        // 不需要走 api() 骨架（那是給會回傳 JSON 的端點用的）
        window.location.href = `/api/projects/${this.projectId}/export.xlsx`;
      },
```

- [ ] **Step 2: `WbsView` template 加工具列**

現況（`WbsView` template 開頭）：

```javascript
    template: `
      <div>
        <p v-if="loading">載入中...</p>
        <div v-else class="wbs-tree">
```

改成：

```javascript
    template: `
      <div>
        <div class="wbs-toolbar">
          <button class="btn" @click="exportXlsx">匯出 Excel</button>
        </div>
        <p v-if="loading">載入中...</p>
        <div v-else class="wbs-tree">
```

- [ ] **Step 3: `app.css` 加工具列樣式**

在檔尾（Task 7 加入的 `.member-owner-row label` 規則之後）加：

```css
.wbs-toolbar { margin-bottom: 1rem; }
```

- [ ] **Step 4: 啟動應用程式，手動驗證**

```bash
docker compose up -d
mvn spring-boot:run
```

以 `leader`/`password123` 登入「MissionBoard 範例專案」，切到「WBS 檢視」分頁：

1. 工具列出現「匯出 Excel」按鈕
2. 點擊後瀏覽器觸發下載，檔名為「MissionBoard 範例專案_任務清單.xlsx」
3. 用試算表軟體開啟下載的檔案，確認：欄位順序為「大類／子類／任務標題／指派人／狀態／優先度／起始日／到期日」；種子資料中未歸類的任務（「整理需求訪談紀錄」）該列「大類」欄顯示「未歸類」、「子類」欄空白，且排在最上方；「SIT」大類底下的「程式開發」子類任務正確落在對應欄位
4. 以 `member`（唯讀角色差異不大，但驗證非負責人也能匯出）登入，確認同樣能下載成功
5. 把專案封存後，以 `chief` 登入，確認「匯出 Excel」仍可正常下載（唯讀操作不受封存限制）
6. console 無錯誤
7. 截圖存證（WBS 分頁含「匯出 Excel」按鈕的畫面一張）

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/project-detail.js src/main/resources/static/css/app.css
git commit -m "feat: WBS 檢視分頁新增「匯出 Excel」按鈕"
```

---

## Task 9: 文件收尾

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/user-guide.md`

- [ ] **Step 1: 修正 `CLAUDE.md`「專案狀態」段落（第 9 行）**

現況：

```
`tasks`/`task_categories`/`task_category_presets` 已取代舊的 `wbs_nodes`/`wbs_presets`，`wbs` 套件與相關 DDL 已移除。專案詳情頁為三分頁：看板（`KanbanView`，預設分頁）可拖曳卡片跨欄、建立任務、歸類、指派；人員派工（`AssignmentView`）依成員分欄，可拖曳卡片跨欄改指派人；WBS 檢視（`WbsView`）依兩層分類（大類/子類）呈現樹狀結構＋固定的「未歸類」節點，可拖曳任務改變所屬大類/子類，皆走 REST＋樂觀更新。舊的樹編輯器／人員派工／甘特三個分頁曾隨重構移除，人員派工與 WBS 檢視已依新的扁平任務模型重做完成，僅剩甘特分頁待補；若後續要重做，需另行設計，不可沿用舊 `wbs_nodes` 邏輯。依任務路由表，新功能一律先走 `superpowers:brainstorming`。
```

改成：

```
`tasks`/`task_categories`/`task_category_presets` 已取代舊的 `wbs_nodes`/`wbs_presets`，`wbs` 套件與相關 DDL 已移除。專案詳情頁為三分頁：看板（`KanbanView`，預設分頁）可拖曳卡片跨欄、建立任務、歸類、指派；人員派工（`AssignmentView`）依成員分欄，可拖曳卡片跨欄改指派人；WBS 檢視（`WbsView`）依兩層分類（大類/子類）呈現樹狀結構＋固定的「未歸類」節點，可拖曳任務改變所屬大類/子類，皆走 REST＋樂觀更新。舊的樹編輯器／人員派工／甘特三個分頁曾隨重構移除，人員派工與 WBS 檢視已依新的扁平任務模型重做完成，僅剩甘特分頁待補；若後續要重做，需另行設計，不可沿用舊 `wbs_nodes` 邏輯。專案列表頁與詳情頁已補齊建立專案、封存/解封存、成員管理（新增/移除成員、換負責人）的操作介面，WBS 檢視分頁新增「匯出 Excel」按鈕，四項功能後端 API 原本就已存在，此次只是補上前端串接。依任務路由表，新功能一律先走 `superpowers:brainstorming`。
```

- [ ] **Step 2: 修正 `CLAUDE.md`「前端模式」段落，新增一段說明**

在「前端模式」章節（`CLAUDE.md:73` 與 `CLAUDE.md:75` 兩段之間）插入新段落：

```
專案列表頁（`project-list.js`）與看板/WBS 一樣改掛 Vue 3（原本是純 vanilla JS 手刻 DOM），新增未封存/已封存頁籤與「建立專案」Modal（`POST /api/projects`，`section`/`owner` 由後端自動代入建立者部門與本人，前端只送 `name`/`description`）。專案詳情頁根元件新增專案層級工具列：封存/解封存按鈕依 `canArchive` 顯示（**不是** `canWrite`——封存後 `canWrite` 對所有角色恆為 `false`，唯一能判斷「可否解封存」的旗標是 `canArchive`）；「成員管理」面板可新增/移除成員、換負責人，新增成員下拉列出全部使用者不限科別，移除現任負責人前端停用按鈕、後端 `ProjectService.removeMember` 也擋一次（400）。成員管理面板獨立於三個分頁之外，異動後透過根元件的 `dataVersion` 計數器（作為 prop 傳給當前掛載中的分頁並 `watch`）觸發該分頁重新 `loadAll()`，避免指派人顯示殘留舊資料又不用整頁重新整理。
```

- [ ] **Step 3: 修正 `CLAUDE.md`「前端模式」章節，加入匯出說明**

在「看板工具列的「分類管理」面板...」段落（`CLAUDE.md:75`）之後插入新段落：

```
WBS 檢視分頁工具列新增「匯出 Excel」按鈕，直接以 `window.location.href` 導向 `GET /api/projects/{id}/export.xlsx` 觸發瀏覽器下載（純 GET 不受 CSRF 保護，不需走 `api()` 骨架）；後端 `TaskExportService` 用 Apache POI 產生欄位固定為「大類／子類／任務標題／指派人／狀態／優先度／起始日／到期日」的工作表，列的排序對齊 WBS 樹狀顯示順序（未歸類 → 各大類 → 各大類底下的子類），權限比照 `canRead`（唯讀角色與封存專案皆可匯出）。
```

- [ ] **Step 4: 更新 `docs/user-guide.md`**

現況「目前尚未提供操作介面的功能」章節：

```markdown
## 目前尚未提供操作介面的功能

以下功能後端 API 已存在，但前端尚未提供對應按鈕／表單，僅供未來版本規劃參考：

- 建立新專案、封存／解封存專案、更換專案負責人
- 專案成員的新增／移除管理畫面
- 任務資料匯出（Excel）
```

改成（整章節移除，因四項功能已全部有前端介面）：

```markdown
```

（即刪除整個「目前尚未提供操作介面的功能」章節，包含標題）

在「## 主要功能」章節下的「### 專案列表」小節，現況：

```markdown
### 專案列表

- 進入「專案列表」頁會列出目前使用者可讀的（未封存）專案卡片，每張卡片顯示專案名稱、所屬科別與負責人。
- 點卡片進入該專案的詳情頁。
```

改成：

```markdown
### 專案列表

- 進入「專案列表」頁會列出目前使用者可讀的專案卡片，每張卡片顯示專案名稱、所屬科別與負責人；頁面上方可切換「未封存／已封存」頁籤。
- 點卡片進入該專案的詳情頁。
- 點「新增專案」按鈕開啟表單，填寫名稱（必填）與描述後建立，所屬科別與負責人自動代入建立者本人與部門，建立成功後導向新專案的詳情頁。
```

在「### 3. WBS 檢視」小節末尾（「分類本身的新增／改名／刪除／排序...」之後）新增一行：

```markdown
- 工具列有「匯出 Excel」按鈕，下載內容涵蓋整個專案的任務清單，欄位為大類／子類／任務標題／指派人／狀態／優先度／起始日／到期日，列的排序對齊畫面上的樹狀顯示順序；任何有讀取權限的角色（含封存專案）都能匯出。
```

在「## 主要功能」章節末尾（「### 任務欄位」小節之後）新增一個小節：

```markdown
### 專案層級操作（詳情頁工具列）

- **封存／解封存**：`canArchive` 為真的角色（科長、負責人、專案成員；主任恆不可）才會看到按鈕。封存後專案變成全員唯讀，工具列按鈕會變成「解封存」。
- **成員管理**：點「成員管理」展開面板，可查看目前成員（負責人特別標示）；有寫入權限者可新增成員（下拉列出全部使用者，不限科別）、移除成員（現任負責人無法被移除，需先換負責人）、更換負責人。
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs/user-guide.md
git commit -m "docs: CLAUDE.md 與使用說明反映四項專案管理功能已補齊前端"
```

---

## 收尾：驗證

- [ ] Task 1-3 的 `mvn test`、Task 4-8 的瀏覽器手動驗證步驟全數通過即整體完成
- [ ] 最後跑一次全量 `mvn test` 確認沒有任何 Task 之間互相干擾
- [ ] 依專案 CLAUDE.md 完成定義：`docker compose up -d` 成功、應用程式啟動記錄、核心功能逐項截圖（本計畫每個前端 Task 已各自截圖，不需額外重拍）、console 無錯誤
