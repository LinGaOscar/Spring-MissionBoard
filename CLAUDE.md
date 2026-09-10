# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 專案定位與現況

綜合性任務管理器：以**扁平任務模型**為核心的看板式任務派工系統。現行架構的真相來源是 `docs/superpowers/specs/2026-08-08-missionboard-task-oriented-rewrite-design.md`，其後每個功能各有一份 spec＋plan 放在 `docs/superpowers/specs/`、`docs/superpowers/plans/`（依日期命名），動手前先讀對應那份；本檔只摘錄關鍵決策與踩坑。

`tasks`／`task_categories`／`task_category_presets` 已取代舊的 `wbs_nodes`／`wbs_presets`，`wbs` 套件與相關 DDL 已移除。專案詳情頁三分頁（看板／人員派工／WBS 檢視）皆已依扁平任務模型完成；舊架構的甘特分頁未重做，若要補需另行設計，不可沿用舊 `wbs_nodes` 邏輯。

整合本機兩個舊專案的已驗證程式碼與模式（全新 repo，不以任一者為基底）——移植功能時先去對應舊專案讀既有實作，不要重新發明：

- `~/Documents/GitHub/Spring-TaskFlow`：看板（`board.js`）、任務指派、成員機制、科別隔離
- `~/Documents/GitHub/Spring-WbsScaff`：WBS 樹編輯（`wbs-editor.js`）、權限模式、專案生命週期

依任務路由表，新功能一律先走 `superpowers:brainstorming`。

## 技術棧

Java 21 + Spring Boot 3.4.0（Maven）、PostgreSQL 16（Docker）、Spring Security 6 表單登入 + Spring Session JDBC（無 JWT）、Thymeleaf 3 頁殼 + Vue 3 離線版（無 build 工具，全 vendored 在 `static/js/vue.global.prod.min.js`）、Apache POI 5.3.0 匯出、spring-dotenv 4.0.0、Lombok。依賴座標與版本沿用兩舊專案已驗證組合，`mssql-jdbc` 已換成 `org.postgresql:postgresql`。

- **v1 純 REST**，不做 WebSocket；service 層是唯一寫入口（v2 才在此掛 STOMP 廣播）
- Schema 手寫於 `sql/01_ddl.sql` + `sql/02_test_data.sql`，`ddl-auto: none`，不用 Flyway/Liquibase
- Spring Session 的 `SPRING_SESSION*` 表由 `spring.session.jdbc.initialize-schema: always` 啟動時自動建立，DDL 不需手寫

## 常用指令

```bash
docker compose up -d                          # 建 DB（首次自動執行 sql/*.sql）；需先 cp .env.example .env 填密碼
docker compose down -v && docker compose up -d  # 重置 DB（改過 sql/*.sql 或測試資料後必做）
mvn spring-boot:run                           # 啟動應用（http://localhost:8080）
mvn test                                      # 全部測試
mvn test -Dtest=ClassName                     # 單一測試類別
mvn test -Dtest=ClassName#methodName          # 單一測試方法
```

- 專案未使用 `mvnw` wrapper，一律用本機 `mvn`
- 測試用 H2 PostgreSQL 相容模式（`MODE=PostgreSQL`）＋`application-test.yml`，不需 Docker
- 測試帳號（密碼皆為 `password123`）：`director`（主任，部級）／`chief`（科長，系統科）／`leader`／`member`（系統科）／`member2`（網路科，供跨科隔離測試）
- 進容器查 DB：`docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "..."'`（角色 `postgres` 不存在，必須用環境變數）

## 後端結構（`src/main/java/com/missionboard/`）

| 套件 | 職責 |
|---|---|
| `auth` | `SecurityConfig`、`CustomUserDetailsService`、`AuthController`（`GET /login`、`GET /home`） |
| `common` | `ApiResponse` 統一信封、`GlobalExceptionHandler`（皆自舊專案移植） |
| `department` | 科別實體與 repository |
| `user` | 使用者、角色；`UserController`（`/api/users/me`、`/api/users/me/dashboard`、`/api/users`） |
| `project` | 專案、成員、**權限核心** `ProjectService.canRead/canWrite/canArchive`；`ProjectController` 同時提供頁面（`/projects`、`/projects/{id}`）與 REST（`/api/projects`、`/archive`、`/unarchive`、`/members`、`/owner`） |
| `task` | 任務、分類、分類選單、儀表板、匯出：`TaskController`（`/api/projects/{projectId}/tasks`、`/status`、`/assignee`、`/move`、`/export.xlsx`）、`TaskCategoryController`（`/task-categories`）、`TaskCategoryPresetController`（`/api/task-category-presets`）、`DashboardService`、`TaskExportService` |

表單登入：登入頁 `/login`、處理 `/auth/login`、成功導向 `/home`；`/api/**` 未登入回 401 JSON（自訂 `AuthenticationEntryPoint`），其餘路徑導向 `/login`；BCrypt。REST 有 CSRF，前端從 `<meta name="_csrf">`／`_csrf_header` 取 token 帶入 header。

API 慣例：統一 `ApiResponse` 信封＋`GlobalExceptionHandler`；狀態變更與派工走專用 PATCH 端點（`/status`、`/assignee`、`/move`），不塞進泛用 PUT。

## 核心架構決策（違反即是 bug）

### 扁平任務模型：任務天生獨立，分類選配

不做樹狀階層。`tasks` 一張表即是唯一的工作單位，`category_id` 可為 NULL——任務不依附任何分類也能建立、指派、在看板拖曳，無「上層節點」概念。

### 選配的兩層分類（service 層強制上限，非 DB CHECK）

- `task_categories` 最多兩層（大類→子類），純粹用於歸類與篩選，**不可派工**、不持有 `assignee_id`／`status`／`priority`／起迄日——這些欄位只存在 `tasks`
- 深度上限（禁止第三層）由 **service 層驗證**，DB 不設 CHECK；`TaskCategoryService.update()` 允許子類改掛別的大項（`UpdateRequest.parentCategoryId`），但新父節點須為同專案的大項，大項本身不可被重新掛
- 建立分類 `TaskCategoryDto.CreateRequest` 的 `presetId`／`name` 擇一必填：從 `task_category_presets` 選單帶入時**存文字快照**，改選單不影響既有專案；選單有 `section_id` 科別隔離（NULL＝全域預設）
- 刪除分類 `ON DELETE SET NULL`，其下任務落回未歸類

### 權限與安全（移植舊專案鐵則）

- 權限集中於 `ProjectService.canRead / canWrite / canArchive`，一律先 null 防禦；四角色：DIRECTOR（跨科唯讀）、SECTION_CHIEF（科內全權）、PROJECT_LEADER（自有專案）、PROJECT_MEMBER（僅參與專案），目前 LEADER 與 MEMBER 權限判斷完全相同
- **IDOR 防護**：`task`／`task_category` 操作各自先驗證所屬 `project` 與 URL 路徑上的 project 一致（`TaskService.getTaskInProject()`、`TaskCategoryService.getCategoryInProject()`，兩條平行檢查）
- 指派人必須是專案成員；移除成員時自動解除其身上的指派；現任負責人不可被移除（`ProjectService.removeMember` 回 400，前端也停用按鈕）
- 封存專案＝全員唯讀（`canWrite` 對所有角色恆為 `false`）；封存／解封存冪等；**能否解封存只能看 `canArchive`**，不是 `canWrite`

### 首頁儀表板依角色分流（`DashboardService`）

`GET /api/users/me/dashboard` 依 `user.getRole()` switch：`PROJECT_LEADER`／`PROJECT_MEMBER`→`buildPersonalView`（我參與的進行中專案＋指派給我的任務，`TaskRepository.findActiveByAssigneeId`）；`SECTION_CHIEF`→`buildSectionView`（本科所有未封存專案，不限自己是否為成員）；`DIRECTOR`→`buildOrgView`（跨科依 `Project::getSection` 分組聚合）。`DashboardDto.Response` 用靜態工廠 `personal/section/org` 讓互斥欄位維持 null，前端依 `viewType` 切換。**踩坑**：`Department` 未覆寫 `equals`/`hashCode`、lazy proxy 每次請求是新實例，分組後**務必依 `sectionId` 排序**，否則科別順序每次請求不同。

## 前端模式

三個頁面皆為 Thymeleaf 頁殼＋一支 Vue 3 檔：`home.js`（儀表板）、`project-list.js`（列表）、`project-detail.js`（詳情，約 1200 行，含所有 mixin／元件）。`home.js` 與 `project-list.js` 是純前端拉 API、無 `th:data-*` 掛載參數的頁面。

### 視覺與導覽

- `app.css` 開頭 `:root` token：`--paper`／`--surface`／`--ink`／`--ink-muted`／`--line`／`--signal`／`--danger` 七色、`--font-ui`／`--font-mono` 兩字體堆疊；卡片浮起一律 `border: 1px solid var(--line)`，不用 `box-shadow`
- 唯一例外：`.task-card`／`.wbs-task-row` 的 `priority-HIGH/MEDIUM/LOW` 左側色條維持語意色 `#d63031`／`#e17055`／`#00b894`，不 token 化（優先度需一眼辨識）
- 側邊欄 `fragments/sidebar.html` 在固定的「首頁／專案列表」後留一個空 `<div id="project-nav-slot">`，只有詳情頁的 root app 用 `<Teleport to="#project-nav-slot">`（全專案唯一使用 Teleport 處）把「看板／人員派工／WBS 檢視」渲染進去；專案名稱由 `ProjectController.detail()` 的 `projectName` model 屬性傳入

### 專案列表頁（`project-list.js`）

未封存／已封存頁籤＋「建立專案」Modal：`POST /api/projects` 只送 `name`／`description`，`section`／`owner` 由後端代入建立者部門與本人。

### 專案詳情頁（`project-detail.js`）

根元件持有 `activeTab`，三分頁以 `v-if` 切換（**非 `v-show`**，避免分頁間資料不同步），各自獨立 `loadAll()`。根元件另有專案層級工具列：封存／解封存按鈕依 `canArchive` 顯示；「成員管理」面板（新增成員下拉列出全部使用者不限科別、移除成員、換負責人）獨立於三分頁之外，異動後以 `dataVersion` 計數器（prop 傳給當前分頁並 `watch`）觸發該分頁重新 `loadAll()`。

共用件：
- `taskBoardMixin`：載入 tasks／categories／members 三份資料＋`taskWriteQueue`（同一任務的連續寫入序列化，避免後完成者用舊快照蓋掉新資料）。**注意** modal 的 `saveTask()` 未走此佇列，不涵蓋拖曳與 modal 併發
- `taskModalMixin`＋`TaskModal` 元件：新增／編輯／刪除任務（看板與 WBS 共用）
- `toastMixin`：提示訊息與計時器（三分頁共用）
- `categoryPresetMixin`：preset 選單載入與挑選（`stagePresets`／`categoryPresets`／`openPresetPicker`／`createCategoryFromPreset`），**只有 `WbsView` 混入**（需 `sectionId` prop）
- `WbsTaskRow`：WBS 三處（未歸類／大項直屬／子類）任務列共用

所有修改走 REST、樂觀更新＋失敗回滾、fetch 失敗 toast。

**看板 `KanbanView`（預設分頁）**：三欄依狀態；拖曳跨欄呼叫 `/move`；建立任務預設未歸類；點卡片開 modal。不再管理分類（「分類管理」面板已整個移除）。

**人員派工 `AssignmentView`**：依成員分欄（含「未指派」欄），拖曳跨欄呼叫 `/assignee`；欄內排序固定依到期日（無到期日排最後）；v1 不支援點卡片開 modal。

**WBS 檢視 `WbsView`**：大類→子類→任務樹＋固定「未歸類」節點，節點旁任務數／完成度前端即時算；點任務列開同一個 modal。這是**全站唯一**管理分類的地方，純前端疊在既有分類 REST 上：
- 拖曳任務改分類：重用任務 `PUT`（原欄位＋新 `categoryId`），不新增端點
- 大項管理：底部 `stage-toggle-row` 對 `stagePresets` 逐一顯示「啟用／已啟用」，啟用走 `createCategoryFromPreset`，再點走 `toggleStage`→`deleteCategory`。**踩坑**：`toggleStage`／`isStageActive`／`findActiveStage` 以名稱比對，大項一旦雙擊改名就與 preset 不同名、切換列誤判為未啟用，因此每個大項標頭另有獨立「刪除」按鈕確保仍可管理
- 「+ 新增子項」直接輸入名稱建立子類（走 `CreateRequest.name`）；「未歸類」與各子類節點旁「+ 新增」為 `quickAdd` 只填標題的快速建任務
- 任一分類雙擊原地改名（`startEditCategoryName`／`commitCategoryName`）、拖「⠿」把手同層排序（`onCategoryDragStart`／`onCategoryDrop`／`reorderSiblings`）；子類可跨大項重新掛父（`reparentSubCategory`：更新 `parentCategoryId`、新舊兩組子類分別重編 `sortOrder`、平行送多筆 PUT）
- `WbsTaskRow` 右側狀態文字在 `canWrite` 時是按鈕，`cycleTaskStatus` 在未開始→進行中→已完成循環，重用 `/move`
- **踩坑**：刪除分類後前端必須同步清空本地 `tasks` 中對應的 `categoryId`，否則 modal 的「所屬類別」下拉對不到選項會顯示空白
- 「匯出 Excel」用 `fetch`＋blob 下載 `GET /api/projects/{id}/export.xlsx`：**不能** `window.location.href` 直接導航（401/403 時無 `Content-Disposition`，整頁會被導去看 JSON 錯誤），也不能沿用 `api()` 骨架（固定 `res.json()`）；非 200 解析錯誤 toast，200 才從 `filename*=UTF-8''...` 取檔名。後端 `TaskExportService` 欄位固定「大類／子類／任務標題／指派人／狀態／優先度／起始日／到期日」，列序對齊 WBS 樹（未歸類→各大類→其子類），權限比照 `canRead`

## 測試重點

測試類別在 `src/test/java/com/missionboard/` 依套件對應（`ProjectServiceTest` 權限矩陣、`TaskServiceTest`／`TaskCategoryServiceTest` IDOR 與深度上限、`DashboardServiceTest` 角色分流、`TaskExportServiceTest` 匯出排序等）。改動須覆蓋：權限矩陣（4 角色 × 讀／寫／封存，套用到 `tasks`／`task_categories` CRUD）、任務可獨立存在（`category_id = NULL` 全流程）、第三層分類被拒、指派人須為成員／移除成員解除指派、`move` 端點欄內與跨欄重新編號、preset 科別隔離、IDOR、儀表板依角色分流。

## 說明文件

- `README.md`、`docs/dev.md`：**`/sync-docs` 生成物**，不要手改，功能／行為變更完成後提醒使用者跑 `/sync-docs`
- `docs/user-guide.md`：面向使用者的功能說明，手寫，UI 行為改變時要同步
- `docs/missionboard-intro.mp4`＋`docs/demo-video-storyboard.md`：專案介紹短片與分鏡（產片 pipeline 在 `scripts/intro-video/`，重跑方式見該目錄 README；工作幀在 gitignored 的 `.superpowers/intro-video/`）

## 驗證與完成定義（宣稱完成前必須全數通過）

- [ ] `docker compose up -d` 成功，容器內 `psql` 確認 DDL 與測試帳號已載入
- [ ] 應用程式實際啟動成功（附啟動記錄關鍵行）
- [ ] 核心功能逐項實測（HTTP 回應／測試輸出為證），登入後看板可操作（拖曳、建立任務、歸類、指派）
- [ ] **有畫面就有截圖**：主要頁面用 chrome-devtools 截圖附在回報中
- [ ] console 無錯誤、版面無異常
- [ ] commit 前跑過 `mvn test`
- 以上以實際執行結果為準，不以讀 code 代替驗證

chrome-devtools 操作注意：`click` 工具常觸發不到 Vue handler／表單 submit，改用 `evaluate_script` 的 `element.click()`／`form.submit()`；`take_screenshot` 的 `filePath` 必須在 workspace 內。
