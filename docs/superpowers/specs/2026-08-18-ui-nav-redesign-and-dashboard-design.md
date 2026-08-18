# 導覽重構、白色簡約視覺、WBS 新增大項、首頁儀表板 設計文件

## 背景與範圍

使用者反映目前 UI/UX 操作不順：看板／人員派工／WBS 檢視三個分頁是主內容區頂端的一排按鈕，跟頁面內容搶視覺焦點，建議改成側邊欄第二層展開；同時要求整體視覺改為白色、簡約、單一目的明確的風格。另外提出兩項功能缺口：WBS 檢視少了「新增大項」（新增大類）的功能；首頁目前只是「歡迎，{displayName}」的佔位頁，應該列出目前帳號進行中的專案與指派給自己的任務。

本次涵蓋四件事，前兩件是既有畫面的重構（導覽 IA＋視覺），後兩件是新功能：
1. 側邊欄改為含專案子導覽的樹狀結構（看板/人員派工/WBS 檢視移入側邊欄第二層）
2. 全站視覺改為白色簡約風格（新色彩/字體 token，套用到既有畫面，不改變既有互動邏輯）
3. WBS 檢視新增「+ 新增大項」功能（範圍精準對應使用者訴求：只做新增大類，不含新增子類別/改名/刪除/排序）
4. 首頁改為儀表板：列出「進行中的專案」與「指派給我的任務」

設計方案已與使用者以中文逐段確認（色彩/字體 token、側邊欄 ASCII 版面、WBS/首頁範圍），本文件是正式化。

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

## 4. 首頁儀表板

### 現況

`home.html` 只有 `<h1>歡迎，{displayName}</h1>`，`AuthController` 只傳 `displayName` 給 model。

### 目標畫面

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

- **進行中的專案**：目前使用者是成員、且未封存的專案，卡片樣式沿用既有 `.project-card`，點擊導向 `/projects/{id}`
- **指派給我的任務**：上述專案範圍內、指派給目前使用者、狀態不是 `DONE` 的任務，依到期日升冪排序（無到期日排最後，沿用既有 `sortByDueDate`／`TaskExportService.sortedByDueDate` 的排序規則），逾期以紅字標示（沿用既有 `isOverdueDate` 判斷），每列顯示任務標題／所屬專案名稱／到期日，點擊導向該任務所屬的 `/projects/{id}`（不深連結到特定任務，開專案詳情頁即可，看板會顯示該任務）
- 兩區都可能為空，各自顯示空狀態文字（「目前沒有進行中的專案」／「目前沒有指派給你的任務」），不做插圖

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

新增 `DashboardService`（新類別，`user` 或 `task` 套件皆可，建議放 `task` 套件——因為主要邏輯是查任務，且已依賴 `TaskRepository`）：

```java
@Service
@RequiredArgsConstructor
public class DashboardService {
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

    public DashboardDto.Response getDashboard(User user) {
        List<Project> projects = projectRepository.findByMemberUserIdAndArchived(user.getId(), false);
        List<Task> tasks = taskRepository.findActiveByAssigneeId(user.getId(), Task.Status.DONE);
        List<DashboardDto.TaskItem> taskItems = tasks.stream()
            .sorted(Comparator.comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getId))
            .map(t -> new DashboardDto.TaskItem(
                t.getId(), t.getProject().getId(), t.getProject().getName(),
                t.getTitle(), t.getStatus().name(), t.getDueDate()))
            .toList();
        return new DashboardDto.Response(
            projects.stream().map(ProjectDto.Response::from).toList(), taskItems);
    }
}
```

（排序在 Service 層用 Java `Comparator` 做，不寫進 JPQL 的 `ORDER BY`——沿用 `TaskExportService.sortedByDueDate` 已驗證過的「到期日升冪、無到期日排最後」寫法，避免 `NULLS LAST` 這類方言相依的 SQL 語法在 H2 測試環境與正式 PostgreSQL 之間出現行為差異）

新增 `TaskRepository` 查詢方法：

```java
// 首頁儀表板：跨所有專案抓「指派給我、尚未完成、專案未封存」的任務，不含已封存專案（那些已凍結不需要再關注）
@Query("SELECT t FROM Task t WHERE t.assignee.id = :assigneeId AND t.status <> :excludedStatus "
    + "AND t.project.archived = false")
List<Task> findActiveByAssigneeId(@Param("assigneeId") Long assigneeId, @Param("excludedStatus") Task.Status excludedStatus);
```

新增 `DashboardDto`（新檔案，`task` 套件）：

```java
public class DashboardDto {
    public record Response(List<ProjectDto.Response> activeProjects, List<TaskItem> myTasks) {
    }

    public record TaskItem(Long id, Long projectId, String projectName, String title,
                            String status, LocalDate dueDate) {
    }
}
```

### 前端

`home.html` 改掛 Vue 3（跟 `project-list.js` 同樣的轉換模式，沿用同一份 `api()` 骨架寫法），新檔 `home.js`：

- `mounted()` 呼叫 `GET /api/users/me/dashboard`
- 渲染兩個區塊，專案卡片沿用 `.project-card` 既有樣式，任務列表用新的 `.dashboard-task-row` 樣式（左側標題、右側專案名稱＋到期日，到期日用 `--font-mono`）

```css
.dashboard-section { margin-bottom: 2rem; }
.dashboard-section h2 { font-size: 1rem; font-weight: 600; margin-bottom: 0.75rem; color: var(--ink); }
.dashboard-task-row { display: flex; justify-content: space-between; align-items: center; padding: 0.75rem 1rem; border: 1px solid var(--line); border-radius: 4px; margin-bottom: 0.5rem; font-size: 0.9rem; }
.dashboard-task-meta { display: flex; gap: 1rem; align-items: center; font-family: var(--font-mono); font-size: 0.82rem; color: var(--ink-muted); }
.dashboard-task-meta.overdue { color: var(--danger); font-weight: 600; }
```

## 資料流總覽

| 功能 | 端點 | 是否新增 |
|---|---|---|
| 側邊欄專案子導覽 | 無（純前端 Teleport＋既有 `activeTab` 狀態） | 否，`ProjectController.detail()` 多帶 `projectName` 給 model |
| WBS 新增大項 | `POST /api/projects/{id}/task-categories`（既有） | 否 |
| 首頁儀表板 | `GET /api/users/me/dashboard` | 是（新端點＋新 Service＋新 Repository 查詢＋新 DTO） |

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

## 測試重點

### 後端（新增測試）

- `DashboardServiceTest`：驗證回傳的 `activeProjects` 只含使用者是成員且未封存的專案；`myTasks` 只含指派給該使用者、狀態非 `DONE`、專案未封存的任務；排序正確（到期日升冪、無到期日排最後、同值 id 升冪）
- `TaskRepositoryTest` 或直接在 Service 測試涵蓋：封存專案的任務不應出現在結果中；別人的任務不應出現
- `UserControllerTest`（或新建 `DashboardControllerTest`）：`GET /api/users/me/dashboard` 需要登入（401）；回傳格式正確

### 前端（實際啟動應用程式手動驗證）

- 視覺 token：抽查看板/人員派工/WBS/專案列表/首頁五個畫面，確認白底、細線邊界、`--signal` 只出現在強調位置，無殘留舊色碼
- 側邊欄：進入專案詳情頁，確認側邊欄出現專案節點＋三個子項且預設展開；點擊子項確認主內容區跟著切換、子項高亮狀態正確；離開專案回首頁/專案列表，確認側邊欄專案節點消失
- WBS 新增大項：以有寫入權限角色進入 WBS 檢視，點「+ 新增大項」，確認選單彈出、選擇後樹狀結構新增節點，且看板分頁的分類管理面板同步看得到（同一份 `categories` 資料）
- 首頁儀表板：以 `leader`（有專案有任務）與一個沒有任何專案的帳號（如新建的 `member2`，若他沒被拉進任何專案）分別登入，確認前者看到專案卡片與任務列表、後者看到兩個空狀態文字；確認逾期任務標紅
- console 無錯誤、版面無異常，依專案 CLAUDE.md 規則截圖存證
