# Spring-MissionBoard：任務導向重構＋改名 設計文件

## 背景與定位轉向

原專案 `Spring-WbsFlow` 是「WBS 樹狀規劃為主」：先建 L1 階段／L2 類別骨架，再往下長出 L3 可派工細項，三層語意由 DB CHECK 約束強制（僅 L3 可指派/設狀態，L1/L2 狀態由子節點彙總、不落地）。

使用後認為此流程過重：使用者想要的是「先建任務、事後歸類」，任務應天生可獨立存在，不需要先搭好階層骨架才能開始工作。本次重構把資料關係整個反過來，並同步把專案改名為 **Spring-MissionBoard**，以任務看板為核心體驗。

已完成的子專案 A（資料層與權限核心）、B（認證）、C（專案管理）**完全保留不動**；本次取代的是子專案 D（節點與選單管理）與尚未開始的前端四檢視，其中本輪只做看板一個檢視。

## 改名範圍與方式

| 層級 | 舊 | 新 |
|---|---|---|
| 本地目錄／GitHub repo | `Spring-WbsFlow` | `Spring-MissionBoard` |
| Java package | `com.wbsflow` | `com.missionboard` |
| Maven `groupId`/`artifactId`/`<name>` | `com.wbsflow` / `spring-wbsflow` / `Spring-WbsFlow` | `com.missionboard` / `spring-missionboard` / `Spring-MissionBoard` |
| DB／文件 | 各處提及 WbsFlow、WBS 樹狀規劃的敘述 | 改為 Spring-MissionBoard、任務導向的定位敘述 |

- 目錄與 GitHub repo 改名是「影響外部系統」的操作，執行前另外向使用者確認一次，不在本計畫內自動執行。
- 改名完成後，於**新終端機**開啟改名後的專案（`cc-open`），並關閉目前這個終端機視窗（`cc-close`）。
- **不改**：已存在的 design/plan 文件中，仍保留（未被本次列為刪除對象的）歷史文件維持原樣用字，不回頭改名——它們是決策時點的紀錄。

## 資料模型

### `tasks`（取代 `wbs_nodes` 的 L3 語意，天生扁平獨立）

| 欄位 | 說明 |
|---|---|
| `id` | PK |
| `project_id` | 必填，任務仍屬於某個專案（子專案 C 的專案容器定位不變） |
| `category_id` | 可為 NULL，FK → `task_categories`。NULL＝未歸類 |
| `title` | 必填 |
| `description` | 選填 |
| `assignee_id` | 可為 NULL，指派人必須是專案成員（沿用既有規則） |
| `status` | `NOT_STARTED` / `IN_PROGRESS` / `DONE`，沿用既有列舉 |
| `priority` | 沿用既有欄位定義 |
| `start_date` / `due_date` | 沿用既有欄位定義 |
| `sort_order` | 看板同一狀態欄內的排序，取代原本 reorder 的排序欄位 |

### `task_categories`（取代 `wbs_nodes` 的 L1/L2，兩層但改為選配）

| 欄位 | 說明 |
|---|---|
| `id` | PK |
| `project_id` | 必填 |
| `parent_category_id` | 可為 NULL：NULL＝階段層，非 NULL＝類別層（掛在某階段下） |
| `name` | 從 `task_category_presets` 選單帶入後存文字快照，改選單不影響既有專案（沿用既有規則） |
| `sort_order` | 同層排序 |

深度上限固定兩層，由 **service 層驗證**強制（建立類別時若 `parent_category_id` 指向的節點自己也有 `parent_category_id`，拒絕），不使用 DB CHECK／trigger，沿用專案「應用層驗證優先」慣例。

### `task_category_presets`（原 `wbs_presets` 改名）

欄位與權限規則不變：`type`（STAGE/CATEGORY）、`section_id`（NULL＝全域預設），僅 `SECTION_CHIEF` 可管自己科別的選單。

### 捨棄

`wbs_nodes` 表與其 CHECK 約束（L1/L2 不可派工、僅 L3 可指派）、父層狀態彙總邏輯（`tasks` 本身就有 `status`，不再需要從樹狀子節點推算）整個移除。開發階段無正式資料，`docker compose down -v` 重置即可，不需要遷移腳本。

## Service／Controller 與 API 端點

**取代**：`WbsNodeService`/`WbsNodeController` → `TaskService`/`TaskController`；`WbsPresetService`/`WbsPresetController` → `TaskCategoryPresetService`/`TaskCategoryPresetController`。

**新增**：`TaskCategoryService`/`TaskCategoryController`（階段/類別本身的 CRUD——原本靠節點 CRUD 順便處理，現在資源拆開需要專屬端點）。

**端點**（沿用「狀態變更/派工走專用 PATCH，不塞泛用 PUT」慣例）：

- `GET/POST /api/projects/{id}/tasks`、`PUT/DELETE .../tasks/{taskId}`
- `PATCH .../tasks/{taskId}/status`
- `PATCH .../tasks/{taskId}/assignee`
- `PATCH .../tasks/{taskId}/move`（看板拖曳：改變 `status` ＋ 目標欄內位置，取代原本的 `/reorder`）
- `GET/POST /api/projects/{id}/task-categories`、`PUT/DELETE .../task-categories/{categoryId}`
- `GET/POST/PUT/DELETE /api/task-category-presets`（`?type=&sectionId=`，行為對齊原 `/api/presets`）

**權限與 IDOR 防護**：完全沿用 `ProjectService.canRead/canWrite`、「先驗證資源的 `project_id` 與 URL 路徑 project 一致」的既有鐵則，這塊不變動。

## 看板前端（新的預設首頁）

借鏡本機 `~/Documents/GitHub/Spring-TaskFlow` 的 `board.js` 拖曳/欄位/modal 模式，但改用本專案既定的「v1 純 REST，不做 WebSocket」原則——把它的 STOMP 寫入操作換成 REST + 樂觀更新＋失敗回滾（沿用專案詳情頁既有慣例）。

- **欄位**：依 `status` 分三欄（未開始／進行中／完成），卡片＝任務，同欄內以 `sort_order` 排序
- **拖曳**：跨欄拖曳＝呼叫 `PATCH .../tasks/{taskId}/move`（改 status ＋ 目標位置），本地先樂觀更新畫面，失敗才回滾＋toast
- **卡片點擊**：開 modal 編輯標題/描述/指派人/優先度/日期/所屬類別（類別可選「未歸類」）
- **新增任務**：modal 建立，`category_id` 預設 NULL（先建任務、事後歸類）
- **逾期提示**：沿用 TaskFlow 的邏輯（本地日期字串比對，避免 UTC 誤差；`DONE` 不顯示逾期）
- **路由**：專案詳情頁分頁預設開啟看板（取代目前預設開樹編輯器），CSRF/`api()`/toast 骨架沿用既有 `project-detail.js` 架構

**本次交付範圍**：只做看板一個檢視。樹編輯器/人員派工/甘特三個 tab 因資料模型改變，本輪先移除（見下節），列為後續子專案再依新模型重做。

## 對既有產物的影響與清理範圍

**整個刪除**（依新資料模型已無法相容）：

- `wbs/WbsNodeService.java`、`WbsNodeController.java`、`WbsPresetService.java`、`WbsPresetController.java` 及對應 Entity/Repository
- `sql/01_ddl.sql` 中 `wbs_nodes`／`wbs_presets` 的 DDL 與 CHECK 約束，`02_test_data.sql` 對應種子資料
- `project-detail.js` 中樹編輯器相關元件（`WbsNodeRow`、`buildTree`/`numbering` 等）與其測試
- 舊 design/plan 文件（不留歷史紀錄，直接刪除）：
  - `docs/superpowers/specs/2026-07-24-wbsflow-node-design.md`
  - `docs/superpowers/specs/2026-07-29-project-detail-shell-tree-editor-design.md`
  - `docs/superpowers/plans/2026-07-24-wbsflow-node-management.md`
  - `docs/superpowers/plans/2026-07-29-project-detail-shell-tree-editor.md`

**新增測試資料**（`02_test_data.sql`）：改為幾筆 `task_categories`（示範兩層：階段→類別）與若干 `tasks`（含已歸類、未歸類、各種狀態/指派人），供看板與權限測試使用。

**保留完全不動**：`auth/`、`project/`、`user/`、`department/`（子專案 A/B/C 的產物），以及 `ProjectService.canRead/canWrite/canArchive` 權限核心。

**CLAUDE.md**：直接改寫「專案狀態」段落反映當前狀態與新方向，不提被取代的舊方向描述。

## 交付順序

本次一次做完：改名 → 資料模型／Service／Controller／REST → 看板前端，不中途停下評估（與子專案 A-D 逐段停下評估的節奏不同，是使用者本次明確選擇的交付方式）。

## 測試重點

- 權限矩陣沿用（4 角色 × 讀／寫／封存），套用到 `tasks`／`task_categories` 的 CRUD
- **任務可獨立存在**：`category_id = NULL` 建立/查詢/指派/看板拖曳皆正常
- **兩層深度上限**：`task_categories` 建立第三層應被拒絕（service 層驗證）
- 指派人必須是專案成員；移除成員時自動解除其身上的任務指派（沿用既有規則）
- 看板 `move` 端點：跨欄（改狀態）與同欄（改排序）皆正確更新 `sort_order`，且不影響其他任務排序
- `task_category_presets` 科別隔離（僅 `SECTION_CHIEF` 管自己科別）沿用既有測試模式
- IDOR：`task`／`task_category` 的 `project_id` 與 URL 路徑 project 不一致時應拒絕

## 驗證與完成定義

沿用專案既有驗證紀律：`docker compose down -v && up -d` 確認新 DDL／種子資料載入成功、應用程式實際啟動、`mvn test` 全數通過、chrome-devtools 實測看板拖曳/建立任務/歸類/指派並截圖存證、console 無錯誤。
