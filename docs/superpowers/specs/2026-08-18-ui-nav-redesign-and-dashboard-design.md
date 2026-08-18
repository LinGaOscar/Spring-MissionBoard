# 導覽重構、白色簡約視覺、WBS 新增大項、首頁儀表板 設計文件

## 背景與範圍

使用者反映目前 UI/UX 操作不順：看板／人員派工／WBS 檢視三個分頁是主內容區頂端的一排按鈕，跟頁面內容搶視覺焦點，建議改成側邊欄第二層展開；同時要求整體視覺改為白色、簡約、單一目的明確的風格。另外提出兩項功能缺口：WBS 檢視少了「新增大項」（新增大類）的功能；首頁目前只是「歡迎，{displayName}」的佔位頁，應該列出目前帳號進行中的專案與指派給自己的任務。

本次涵蓋四件事，前兩件是既有畫面的重構（導覽 IA＋視覺），後兩件是新功能：
1. 側邊欄改為含專案子導覽的樹狀結構（看板/人員派工/WBS 檢視移入側邊欄第二層）
2. 全站視覺改為白色簡約風格（新色彩/字體 token，套用到既有畫面，不改變既有互動邏輯）
3. WBS 檢視新增「+ 新增大項」功能（範圍精準對應使用者訴求：只做新增大類，不含新增子類別/改名/刪除/排序）
4. 首頁改為儀表板：依角色分層呈現（操作型角色看個人任務清單；管理型角色看管轄範圍的專案總覽）

設計方案已與使用者以中文逐段確認（色彩/字體 token、側邊欄 ASCII 版面、WBS/首頁範圍），本文件是正式化。

**首頁儀表板的角色分層是設計過程中的修正**：原始版本不分角色，一律顯示「我是成員的專案」＋「指派給我的任務」。但從主管／長官的視角覆核後發現：`SECTION_CHIEF`（科長）與 `DIRECTOR`（主任）在種子資料裡都**不是任何專案的成員**——科長是靠 `canWrite` 的科內範圍權限管專案，主任是純跨科唯讀——照原始設計，這兩個管理層級登入後首頁會是空的，對最需要看到全局狀況的角色反而最沒用。第 4 節已改寫為依角色分三層呈現，詳見下方。

## 不做的事（明確排除）

- 不重做看板/人員派工/WBS 檢視既有的拖曳、任務 modal、分類管理面板等互動邏輯，只調整視覺樣式與 WBS 分頁多一個「新增大項」入口
- WBS 檢視不做「新增子類別」「改名」「刪除」「排序」——這些維持只在看板「分類管理」面板操作
- 首頁儀表板不做「已完成任務」清單、不做跨專案任務的拖曳/編輯，純瀏覽用途，點擊導向對應專案詳情頁
- 不引入外部字型（Google Fonts 等），維持專案既有「無 build 工具、全 vendored」慣例，字體用系統字體堆疊
- 不做深色模式、不做側邊欄可收合（v1 範圍外，之後有需要再加）

## 1. 視覺 Token（套用到 `app.css`，全站生效）

### 色彩

| Token | Hex | 用途 |
|---|---|---|
| `--paper` | `#FFFFFF` | 頁面背景 |
| `--surface` | `#F7F7F8` | 側邊欄底色、hover 狀態底色 |
| `--ink` | `#1A1A1A` | 主要文字 |
| `--ink-muted` | `#6B6B70` | 次要文字、中繼資料（日期、成員數等） |
| `--line` | `#E4E4E4` | 所有邊框/分隔線 |
| `--signal` | `#1D4ED8` | 唯一強調色：目前導覽項目、主要操作按鈕、focus 狀態 |
| `--danger` | `#B91C1C` | 破壞性操作（維持既有 `.btn-danger` 語意，只換色值） |

不使用 `box-shadow` 做卡片浮起效果，一律用 `border: 1px solid var(--line)` 表達邊界。

### 字體

```css
--font-ui: -apple-system, "PingFang TC", "Segoe UI", "Microsoft JhengHei", sans-serif;
--font-mono: ui-monospace, "SF Mono", "Cascadia Code", monospace;
```

`--font-mono` 只用在三處：任務卡片的日期、WBS 節點的階段代碼（SIT/UAT/PROD 等，若該階段名稱本身是這類代碼）、任務數/完成度數字。其餘一律 `--font-ui`。

### 套用範圍與作法

- 修改 `app.css` 檔頭新增 `:root { ... }` token 定義區塊，其餘既有規則裡的色碼／字體逐一替換成對應 token（例如現有 `#0984e3` 換成 `var(--signal)`，`#2d3436` 換成 `var(--ink)`，`background: #f5f6fa` 換成 `var(--paper)` 或 `var(--surface)` 視情境）
- `.navbar` 從深色（現況 `#2d3436` 底）改為白底＋`border-bottom: 1px solid var(--line)`，文字用 `var(--ink)`，與「白色簡約」的訴求一致
- `.btn-primary` 保留但底色改 `var(--signal)`；一般 `.btn` 移除現有邊框顏色改用 `var(--line)`，hover 時背景 `var(--surface)`
- 現有 `.task-card` 的 `priority-HIGH/MEDIUM/LOW` 左側色條、`.wbs-task-row` 同款左側色條維持既有紅/橘/綠語意色，不受本次白色簡約改動影響（優先度需要一眼辨識，語意色比 token 化更重要）
- 這是一次全面的視覺替換，但不改變任何既有 class 名稱與 DOM 結構（除側邊欄重構那部分，見下一節），純粹替換色值/字體/邊框寫法，風險可控

## 2. 側邊欄導覽重構

### 現況

- `fragments/sidebar.html`：靜態 Thymeleaf fragment，固定兩個連結（首頁/專案列表），不感知目前所在專案
- `project-detail.js` 的 Vue root（掛載於 `#detail-app`，只存在於 `project/detail.html`）自己在主內容區頂端渲染看板/人員派工/WBS 檢視三顆分頁按鈕，側邊欄與這個 Vue app 互不相通

### 目標版面

```
┌─────────────────────────────────────────┐
│ MissionBoard 任務管理系統         leader 登出│
├───────────────┬───────────────────────────┤
│ 首頁            │  （頁面內容）              │
│ 專案列表         │                            │
│                │                            │
│ ▾ 系統科示範專案  │                            │
│    ▸ 看板       │← 目前分頁：--signal 文字 +  │
│      人員派工     │   左側強調線               │
│      WBS 檢視    │                            │
└───────────────┴───────────────────────────┘
```

只有在專案詳情頁時，側邊欄才會出現「專案節點」，預設展開（不需要手動點開），底下三個子項對應現有的三個分頁；不在專案詳情頁時（首頁、專案列表頁）側邊欄只顯示首頁/專案列表兩個既有連結，不留空白節點。

### 技術方案：Vue 3 `<Teleport>`

側邊欄是獨立的 Thymeleaf fragment，`project-detail.js` 的 Vue app 掛載在主內容區——兩者是不同的 DOM 子樹。不重構整個頁面版面（不把側邊欄拉進 Vue 掛載範圍），改用 Vue 3 內建的 `<Teleport>`（已確認 vendored 的 `vue.global.prod.min.js` 有包含此功能）：

1. `fragments/sidebar.html` 在既有兩個連結之後，固定加一個空的傳送目標：
   ```html
   <nav th:fragment="sidebar" class="sidebar">
     <ul>
       <li><a th:href="@{/home}">首頁</a></li>
       <li><a th:href="@{/projects}">專案列表</a></li>
     </ul>
     <div id="project-nav-slot"></div>
   </nav>
   ```
   在非專案詳情頁（首頁、專案列表頁），這個 `div` 永遠是空的（該頁沒有掛載 `#detail-app` 的 Vue app，沒有人會傳送內容進來），視覺上不會多出任何東西。

2. `project-detail.js` 的 root app 樣板最上層新增：
   ```html
   <Teleport to="#project-nav-slot">
     <div class="sidebar-project-nav">
       <!-- ▾ 是純裝飾用的固定符號，不是可點擊的展開/收合控制項——v1 側邊欄固定展開（見「範圍外」） -->
       <div class="sidebar-project-name">▾ {{ projectName }}</div>
       <ul>
         <li :class="{ active: activeTab === 'kanban' }" @click="activeTab = 'kanban'">看板</li>
         <li :class="{ active: activeTab === 'assignment' }" @click="activeTab = 'assignment'">人員派工</li>
         <li :class="{ active: activeTab === 'wbs' }" @click="activeTab = 'wbs'">WBS 檢視</li>
       </ul>
     </div>
   </Teleport>
   ```
   取代現有頂端的三顆分頁按鈕（`detail-tabs` 那段 markup 整段移除，邏輯不變，只是換了渲染位置與樣式）。`activeTab` 資料本身不變，繼續驅動 `v-if`/`v-else-if` 切換三個檢視元件。
3. 根元件 `data()` 新增 `projectName`：目前 Thymeleaf `detail.html` 沒有把專案名稱傳給前端（只有 `projectId`），需要在 `ProjectController.detail()` 多帶一個 `project.getName()` 到 model，`detail.html` 的 `#detail-app` 多一個 `th:data-project-name="${projectName}"` 屬性，`project-detail.js` 開頭讀取。

### 樣式

```css
.sidebar-project-nav { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid var(--line); }
.sidebar-project-name { font-size: 0.85rem; font-weight: 600; color: var(--ink-muted); padding: 0 1.5rem 0.5rem; }
.sidebar-project-nav ul { list-style: none; }
.sidebar-project-nav li { padding: 0.5rem 1.5rem 0.5rem 2.25rem; font-size: 0.9rem; cursor: pointer; color: var(--ink); }
.sidebar-project-nav li:hover { background: var(--surface); }
.sidebar-project-nav li.active { color: var(--signal); font-weight: 600; border-left: 3px solid var(--signal); padding-left: calc(2.25rem - 3px); background: var(--surface); }
```

### 工具列調整

「封存/解封存」「成員管理」維持在主內容區頂端（它們是動作，不是導覽目的地，不放進側邊欄），改用細線分隔的文字按鈕樣式取代現有的膠囊邊框按鈕，呼應「簡約」：

```css
.project-toolbar { border-bottom: 1px solid var(--line); padding-bottom: 0.75rem; }
```
（按鈕本身沿用已 token 化的 `.btn`，不需要新增 class）

## 3. WBS 檢視——新增大項

### 現況

`loadCategoryPresets`／`stagePresets`／`categoryPresets`／`presetPicker`／`openPresetPicker`／`closePresetPicker`／`createCategoryFromPreset` 目前只定義在 `KanbanView` 內部，`WbsView` 沒有這組邏輯與對應 UI。

### 目標

WBS 檢視樹狀結構最上方（未歸類節點之前，或所有大類節點之後——採用「所有大類節點之後」與看板「+ 新增階段」按鈕的既有位置慣例一致）加一顆「+ 新增大項」按鈕，點擊開啟跟看板同一套的階段選單挑選器（SIT/UAT/PROD 等 `task_category_presets` 的 STAGE 型項目），選定後呼叫既有 `POST /api/projects/{id}/task-categories`（`parentCategoryId: null`），不新增後端端點。

### 技術方案：抽出共用 mixin

比照專案既有「發現兩個以上檢視需要同一段邏輯就抽 mixin」的慣例（`taskModalMixin`／`taskBoardMixin` 皆是此模式），把 `KanbanView` 現有的整組 preset 邏輯（`stagePresets`／`categoryPresets`／`presetsLoaded`／`presetPicker`／`loadCategoryPresets`／`openPresetPicker`／`closePresetPicker`／`createCategoryFromPreset`）抽成新 mixin `categoryPresetMixin`，`KanbanView`／`WbsView` 都引入。抽出後行為不變（純重構），`WbsView` 的樣板只使用其中「新增大項」這一顆按鈕（呼叫 `openPresetPicker(null)`），不暴露「+子類別」／改名／刪除／排序的 UI——共用邏輯，但每個檢視自行決定要露出哪些操作入口，維持「完整分類編輯只在看板做」的既有分工。

### 樣式

```css
.wbs-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
```
（沿用既有 `.btn`／`.preset-picker-popover` 樣式，不需要新 class）

## 4. 首頁儀表板（依角色分三層）

### 現況

`home.html` 只有 `<h1>歡迎，{displayName}</h1>`，`AuthController` 只傳 `displayName` 給 model。

### 三層視角

| 角色 | 視角 | 內容 |
|---|---|---|
| `PROJECT_LEADER`／`PROJECT_MEMBER` | 操作視角（`PERSONAL`） | 我是成員的進行中專案＋指派給我的任務清單 |
| `SECTION_CHIEF` | 管理視角（`SECTION`） | 本科所有進行中專案的總覽（不限自己是不是成員），每個專案帶概況指標，不列任務細節 |
| `DIRECTOR` | 總覽視角（`ORG`） | 跨科總覽，依科別分組的專案數與逾期/完成度彙總數字 |

三層共用同一個 `GET /api/users/me/dashboard` 端點，後端依呼叫者角色回傳對應內容，前端依回應裡的 `viewType` 欄位切換渲染哪一種畫面。

### 目標畫面

**PERSONAL**（`leader`／`member`）：

```
┌─────────────────────────────────┐
│ 進行中的專案                      │
│ ┌───────────┐ ┌───────────┐    │
│ │系統科示範專案│ │另一個專案  │    │
│ │系統科・負責人│ │...        │    │
│ └───────────┘ └───────────┘    │
│                                  │
│ 指派給我的任務                    │
│ ┌────────────────────────────┐ │
│ │主機申請      系統科示範專案  8/30│ │
│ │防火牆開通    系統科示範專案  --  │ │
│ └────────────────────────────┘ │
└─────────────────────────────────┘
```

**SECTION**（`chief`）：

```
┌─────────────────────────────────┐
│ 系統科 進行中專案總覽              │
│ ┌─────────────────────────────┐ │
│ │系統科示範專案         3 個任務 │ │
│ │              1 逾期・33% 完成 │ │
│ ├─────────────────────────────┤ │
│ │另一個專案            5 個任務 │ │
│ │              0 逾期・80% 完成 │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```
每列點擊導向 `/projects/{id}`。

**ORG**（`director`）：

```
┌─────────────────────────────────┐
│ 全公司進行中專案總覽                │
│ ┌─────────────────────────────┐ │
│ │系統科                2 個專案 │ │
│ │              1 逾期・45% 完成 │ │
│ ├─────────────────────────────┤ │
│ │網路科                1 個專案 │ │
│ │              0 逾期・100% 完成│ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```
科別彙總列**不可點擊**（v1 沒有「依科別篩選專案列表」的頁面可以導過去，見「範圍外」，不為了這個彙總畫面額外做篩選功能）。

- 「逾期」定義沿用既有 `isOverdueDate` 語意的後端版本：`dueDate` 不為 null、狀態不是 `DONE`、`dueDate` 早於今天
- 「完成度」＝ `DONE` 任務數 / 總任務數，四捨五入取整數百分比；總數為 0 時顯示 `--`（沿用 `WbsView.completionLabel` 既有寫法的語意）
- 三層都可能是空的，各自顯示對應的空狀態文字（「目前沒有進行中的專案」／「目前沒有指派給你的任務」／「本科目前沒有進行中的專案」／「目前沒有進行中的專案」），不做插圖

### 後端：新端點

新增 `GET /api/users/me/dashboard`（掛在既有 `UserController`，與現有 `GET /api/users/me` 同資源路徑下，不新增 controller 類別）：

```java
@GetMapping("/api/users/me/dashboard")
public ApiResponse<DashboardDto.Response> dashboard(Principal principal) {
    User user = userRepository.findByUsername(principal.getName())
        .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
    return ApiResponse.ok(dashboardService.getDashboard(user));
}
```

新增 `DashboardService`（新類別，放 `task` 套件——主要邏輯是聚合任務資料，且已依賴 `TaskRepository`）：

```java
@Service
@RequiredArgsConstructor
public class DashboardService {
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

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
```

（`buildSectionView`／`buildOrgView` 都是「載入專案 → 對每個專案查任務 → Java 端聚合」，不是單一大 SQL——內部小規模工具的資料量下（一個科通常幾個到十幾個進行中專案）沒有理由為了聚合先寫複雜 JPQL GROUP BY，之後真的量大再優化）

新增 `TaskRepository` 查詢方法（只新增這一個，`SECTION`／`ORG` 視角完全重用既有的 `findByProjectId`／`ProjectRepository.findBySectionIdAndArchived`／`findByArchived`，不需要新查詢）：

```java
// 首頁儀表板 PERSONAL 視角：跨所有專案抓「指派給我、尚未完成、專案未封存」的任務，
// 不含已封存專案（那些已凍結不需要再關注）
@Query("SELECT t FROM Task t WHERE t.assignee.id = :assigneeId AND t.status <> :excludedStatus "
    + "AND t.project.archived = false")
List<Task> findActiveByAssigneeId(@Param("assigneeId") Long assigneeId, @Param("excludedStatus") Task.Status excludedStatus);
```

新增 `DashboardDto`（新檔案，`task` 套件），用靜態工廠方法讓三種視角互斥的欄位維持 null，呼叫端不用手動記哪些欄位對哪個 `viewType` 有效：

```java
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
```

### 前端

`home.html` 改掛 Vue 3（跟 `project-list.js` 同樣的轉換模式，沿用同一份 `api()` 骨架寫法），新檔 `home.js`：

- `mounted()` 呼叫 `GET /api/users/me/dashboard`
- 樣板依 `viewType` 用 `v-if`/`v-else-if` 切三種畫面：`PERSONAL` 沿用 `.project-card` 卡片＋新的 `.dashboard-task-row` 任務列；`SECTION`／`ORG` 共用同一種「彙總列」樣式（`.dashboard-summary-row`），差在 `SECTION` 每列可點擊、`ORG` 不行

```css
.dashboard-section { margin-bottom: 2rem; }
.dashboard-section h2 { font-size: 1rem; font-weight: 600; margin-bottom: 0.75rem; color: var(--ink); }
.dashboard-task-row { display: flex; justify-content: space-between; align-items: center; padding: 0.75rem 1rem; border: 1px solid var(--line); border-radius: 4px; margin-bottom: 0.5rem; font-size: 0.9rem; }
.dashboard-task-meta { display: flex; gap: 1rem; align-items: center; font-family: var(--font-mono); font-size: 0.82rem; color: var(--ink-muted); }
.dashboard-task-meta.overdue { color: var(--danger); font-weight: 600; }
.dashboard-summary-row { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1.1rem; border: 1px solid var(--line); border-radius: 4px; margin-bottom: 0.5rem; }
.dashboard-summary-row.clickable { cursor: pointer; }
.dashboard-summary-row.clickable:hover { background: var(--surface); }
.dashboard-summary-name { font-size: 0.95rem; font-weight: 600; color: var(--ink); }
.dashboard-summary-stats { font-family: var(--font-mono); font-size: 0.82rem; color: var(--ink-muted); text-align: right; }
.dashboard-summary-stats.has-overdue { color: var(--danger); }
```

## 資料流總覽

| 功能 | 端點 | 是否新增 |
|---|---|---|
| 側邊欄專案子導覽 | 無（純前端 Teleport＋既有 `activeTab` 狀態） | 否，`ProjectController.detail()` 多帶 `projectName` 給 model |
| WBS 新增大項 | `POST /api/projects/{id}/task-categories`（既有） | 否 |
| 首頁儀表板 | `GET /api/users/me/dashboard` | 是（新端點＋新 Service，依角色回傳 PERSONAL／SECTION／ORG 三種內容＋新 Repository 查詢一支＋新 DTO） |

## 錯誤處理

- 側邊欄 Teleport：目標元素 `#project-nav-slot` 不存在時 Vue 會警告但不會拋錯（只在 `detail.html` 才會掛載這個 Vue app，`sidebar.html` 又是所有頁面共用，順序上 `#project-nav-slot` 一定先於 Vue app 掛載存在，不會有時序問題）
- 首頁儀表板載入失敗：沿用既有 `api()` 骨架＋`showToast` 慣例，顯示「載入失敗，請重新整理」
- WBS 新增大項失敗（選單載入失敗／建立失敗）：沿用看板既有的 `showToast` 錯誤處理，不新增邏輯

## 範圍外（明確不做）

- 側邊欄收合/展開整個側邊欄本身（v1 側邊欄固定展開）
- 首頁儀表板不做篩選/排序控制項，固定排序規則
- 不做「指派給我的任務」在首頁直接編輯或拖曳
- 不做深色模式
- WBS 檢視不做「新增子類別」（使用者只要求「新增大項」，維持精準對應訴求，不擴大範圍）
- `SECTION`／`ORG` 視角的彙總列不做「點擊展開任務細節」或「依科別篩選專案列表」——如果之後科長/主任反映彙總數字不夠用，需要下鑽到任務層級，屬於下一輪設計的範圍，不在本次預先做

## 測試重點

### 後端（新增測試）

- `DashboardServiceTest`：三個角色分支各自驗證
  - `PROJECT_LEADER`／`PROJECT_MEMBER` → `viewType="PERSONAL"`，`activeProjects` 只含使用者是成員且未封存的專案；`myTasks` 只含指派給該使用者、狀態非 `DONE`、專案未封存的任務；排序正確（到期日升冪、無到期日排最後、同值 id 升冪）；封存專案的任務、別人的任務都不應出現
  - `SECTION_CHIEF` → `viewType="SECTION"`，`projectSummaries` 只含該科未封存專案（含自己不是成員的），每筆的 `taskCount`／`overdueCount`／`completionLabel` 數字正確；其他科的專案不應出現
  - `DIRECTOR` → `viewType="ORG"`，`sectionSummaries` 涵蓋所有科別、彙總數字正確；已封存專案不應被計入任何統計
  - 邊界案例：某角色底下完全沒有專案/任務時，對應清單回傳空陣列而非 null，`completionLabel` 在總任務數 0 時回傳 `"--"`
- `UserControllerTest`（或新建 `DashboardControllerTest`）：`GET /api/users/me/dashboard` 需要登入（401）；分別以 `leader`/`chief`/`director` 呼叫，確認 `viewType` 對應正確

### 前端（實際啟動應用程式手動驗證）

- 視覺 token：抽查看板/人員派工/WBS/專案列表/首頁五個畫面，確認白底、細線邊界、`--signal` 只出現在強調位置，無殘留舊色碼
- 側邊欄：進入專案詳情頁，確認側邊欄出現專案節點＋三個子項且預設展開；點擊子項確認主內容區跟著切換、子項高亮狀態正確；離開專案回首頁/專案列表，確認側邊欄專案節點消失
- WBS 新增大項：以有寫入權限角色進入 WBS 檢視，點「+ 新增大項」，確認選單彈出、選擇後樹狀結構新增節點，且看板分頁的分類管理面板同步看得到（同一份 `categories` 資料）
- 首頁儀表板：分別以 `leader`（PERSONAL，有專案有任務）、`chief`（SECTION，本科專案總覽）、`director`（ORG，跨科總覽）、`member2`（PERSONAL，沒有任何專案）登入，確認四種畫面內容與 `viewType` 對應正確、`member2` 看到兩個空狀態文字；確認逾期任務/專案數字標紅、完成度百分比正確；`chief`／`director` 的彙總列確認可讀性（不需要點進任務細節就看得懂哪裡有風險）
- console 無錯誤、版面無異常，依專案 CLAUDE.md 規則截圖存證
