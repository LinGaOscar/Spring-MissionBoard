# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 專案狀態

**已完成任務導向模型重構，這是現行架構。** 真相來源是 `docs/superpowers/specs/2026-08-08-missionboard-task-oriented-rewrite-design.md`；動手前先讀它，本檔僅摘錄關鍵決策。

`tasks`/`task_categories`/`task_category_presets` 已取代舊的 `wbs_nodes`/`wbs_presets`，`wbs` 套件與相關 DDL 已移除。專案詳情頁為三分頁：看板（`KanbanView`，預設分頁）可拖曳卡片跨欄、建立任務、歸類、指派；人員派工（`AssignmentView`）依成員分欄，可拖曳卡片跨欄改指派人；WBS 檢視（`WbsView`）依兩層分類（大類/子類）呈現樹狀結構＋固定的「未歸類」節點，可拖曳任務改變所屬大類/子類，皆走 REST＋樂觀更新。舊的樹編輯器／人員派工／甘特三個分頁曾隨重構移除，人員派工與 WBS 檢視已依新的扁平任務模型重做完成，僅剩甘特分頁待補；若後續要重做，需另行設計，不可沿用舊 `wbs_nodes` 邏輯。依任務路由表，新功能一律先走 `superpowers:brainstorming`。

## 專案定位

綜合性任務管理器：以扁平任務模型為核心的看板式任務派工系統。整合本機兩個舊專案的已驗證程式碼與模式（全新 repo，不以任一者為基底）：

- `~/Documents/GitHub/Spring-TaskFlow`：看板（`board.js`）、任務指派、成員機制、科別隔離
- `~/Documents/GitHub/Spring-WbsScaff`：WBS 樹編輯（`wbs-editor.js`）、權限模式、專案生命週期

移植功能時先去對應舊專案讀既有實作，不要重新發明。

## 技術棧

Java 21 + Spring Boot 3.4.x（Maven）、PostgreSQL 16（Docker）、Spring Security 6 表單登入 + Spring Session JDBC（無 JWT）、Thymeleaf 3 頁殼 + Vue 3 離線版（無 build 工具，全 vendored）、Apache POI 匯出、spring-dotenv、Lombok。

- **v1 純 REST**，不做 WebSocket；service 層是唯一寫入口（v2 才在此掛 STOMP 廣播）
- Schema 手寫於 `sql/01_ddl.sql` + `sql/02_test_data.sql`，`ddl-auto: none`

## 開發環境啟動流程

```bash
# 1. 建立 DB（首次，自動執行 sql/*.sql）；需先複製 .env.example 為 .env 填入密碼
docker compose up -d

# 2. 重置 DB（調整測試資料後必做）
docker compose down -v && docker compose up -d

# 3. 啟動應用
mvn spring-boot:run

# 4. 測試
mvn test
mvn test -Dtest=ClassName
mvn test -Dtest=ClassName#methodName
```

測試帳號（密碼皆為 `password123`）：`director` / `chief` / `leader` / `member` / `member2`，對應四角色（`member2` 屬不同科，供跨科隔離測試）。
測試用 H2 PostgreSQL 相容模式（`MODE=PostgreSQL`）＋`application-test.yml`，不需 Docker。專案未使用 `mvnw` wrapper，一律用本機 `mvn`。

## 核心架構決策（違反即是 bug）

### 扁平任務模型：任務天生獨立，分類選配

不做樹狀階層。`tasks` 一張表即是唯一的工作單位，`category_id` 可為 NULL——任務不依附任何分類也能建立、指派、在看板拖曳，無「上層節點」概念。

### 選配的兩層分類（service 層強制上限，非 DB CHECK）

- `task_categories` 最多兩層（大類→子類），純粹用於歸類與篩選，**不可派工**、不持有 `assignee_id`/`status`/`priority`/起迄日——這些欄位只存在 `tasks`
- 深度上限（禁止建立第三層）由 **service 層驗證**，DB 不設 CHECK 約束；違反即是 bug
- 分類名稱從 `task_category_presets` 選單帶入後**存文字快照**，改選單不影響既有專案；選單有 `section_id` 科別隔離（NULL＝全域預設）

### 權限與安全（移植舊專案鐵則）

- 權限集中於 `ProjectService.canRead / canWrite`，一律先 null 防禦；四角色：DIRECTOR（跨科唯讀）、SECTION_CHIEF（科內全權）、PROJECT_LEADER（自有專案）、PROJECT_MEMBER（僅參與專案）
- **IDOR 防護**：`task`／`task_category` 操作各自先驗證所屬 `project` 與 URL 路徑上的 project 一致（`TaskService.getTaskInProject()`、`TaskCategoryService.getCategoryInProject()`，兩條平行檢查）
- 指派人必須是專案成員；移除成員時自動解除其身上的指派
- 封存專案＝全員唯讀；封存／解封存操作冪等

### API 慣例

統一 `ApiResponse` 信封＋`GlobalExceptionHandler`（自舊專案移植）。狀態變更與派工走專用 PATCH 端點（`/status`、`/assignee`、`/move`），不塞進泛用 PUT。

## 前端模式

專案詳情頁（`project-detail.js`）為三分頁，各自獨立載入資料、以 `v-if` 切換（非 `v-show`，避免分頁間資料不同步）：看板（`KanbanView`，預設分頁）一次載入任務與分類資料；人員派工（`AssignmentView`）載入任務與成員資料，依成員分欄（含「未指派」欄）呈現，v1 僅支援拖曳改指派，不支援點卡片開 modal（完整編輯回看板做）；WBS 檢視（`WbsView`）依兩層分類呈現大類→子類→任務的樹狀結構＋固定的「未歸類」節點，節點旁顯示任務數與完成度（前端即時算），核心互動是拖曳任務改變所屬大類/子類，點任務列開與看板相同的編輯 modal，分類本身的新增/改名/刪除/排序仍只在看板的「分類管理」面板操作；三處樹狀節點下的任務列共用 `WbsTaskRow` 元件，避免同一段 markup 在未歸類/階段直屬/子類清單各維護一份。看板與 WBS 檢視共用 `taskBoardMixin`（載入 tasks/categories/members 三份資料與 `taskWriteQueue` 序列化寫入佇列）、`taskModalMixin`（新增/編輯/刪除任務的邏輯）與 `TaskModal` 元件（modal 畫面本身），三分頁共用 `toastMixin`（提示訊息與計時器），避免重複邏輯。所有修改走 REST，成功後就地更新（樂觀更新＋失敗回滾、fetch 失敗顯示 toast）：看板拖曳卡片跨欄呼叫 `move` 端點、建立任務預設「未歸類」（`category_id` 為 NULL）、點卡片開 modal 編輯歸類／指派／優先度／日期；人員派工拖曳卡片跨欄呼叫 `assignee` 端點，欄內排序為固定規則（依到期日，無到期日排最後）；WBS 檢視拖曳任務改分類重用既有任務 `PUT` 端點（帶入原欄位＋新 `categoryId`），不新增後端端點。同一任務的連續寫入（例如快速連續兩次拖曳）經 `taskWriteQueue` 序列化，避免後完成者用舊快照蓋掉新資料；注意 modal 的 `saveTask()` 目前未走此佇列，不涵蓋拖曳與 modal 編輯併發的情境。

看板工具列的「分類管理」面板純前端疊加在既有分類 REST 端點上（無新後端端點）：從 `task_category_presets` 選單挑選建立階段／子類別、雙擊原地改名、同層拖曳重新排序、刪除（階段連坐刪其下子類別，後端 `ON DELETE SET NULL` 讓任務落回未歸類）。刪除分類後前端須同步清空本地 `tasks` 快照中對應的 `categoryId`，否則任務 modal 的「所屬類別」下拉會因對不到選項而顯示空白，要等重新整理頁面才會變回「未歸類」。

## 測試重點

權限矩陣（4 角色 × 讀／寫／封存，套用到 `tasks`／`task_categories` 的 CRUD）、任務可獨立存在（`category_id = NULL` 建立/查詢/指派/看板拖曳皆正常）、兩層深度上限（`task_categories` 第三層應被拒絕）、指派人須為專案成員／移除成員解除指派、看板 `move` 端點的欄內與跨欄重新編號、`task_category_presets` 科別隔離、IDOR（`task`／`task_category` 的 `project_id` 與 URL 路徑不一致應拒絕）。

## 驗證與完成定義（宣稱完成前必須全數通過）

- [ ] `docker compose up -d` 成功，容器內 `psql` 確認 DDL 與測試帳號已載入
- [ ] 應用程式實際啟動成功（附啟動記錄關鍵行）
- [ ] 核心功能逐項實測（HTTP 回應 / 測試輸出為證），登入後看板可操作（拖曳、建立任務、歸類、指派）
- [ ] **有畫面就有截圖**：主要頁面用 chrome-devtools 截圖附在回報中
- [ ] console 無錯誤、版面無異常
- [ ] commit 前跑過 `mvn test`
- 以上以實際執行結果為準，不以讀 code 代替驗證。

## 開發注意事項

- pom.xml 依 Spring-TaskFlow / Spring-WbsScaff 慣例調整（`mssql-jdbc` → `org.postgresql:postgresql`），dependency 座標與版本沿用兩舊專案已驗證組合（Spring Boot 3.4.0、Java 21、`spring-dotenv:4.0.0`、`poi-ooxml:5.3.0`）
- 表單登入已實作於 `auth/SecurityConfig.java` + `auth/CustomUserDetailsService.java`：登入頁 `/login`、登入處理 `/auth/login`、成功導向 `/home`；`/api/**` 未登入回 401 JSON（自訂 `AuthenticationEntryPoint`），其餘路徑未登入導向 `/login`；BCrypt 加密
- Spring Session JDBC 的 `SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES` 表由 `spring.session.jdbc.initialize-schema: always` 在應用啟動時自動建立，`sql/01_ddl.sql` 不需手寫
