# Spring-WbsFlow 設計文件

日期：2026-07-17
狀態：已與使用者逐節確認核准

## 1. 目的與定位

綜合性任務管理器：在同一系統中完成 WBS 規劃與任務派工。整合既有兩個專案的核心能力——

- **Spring-TaskFlow**：看板、任務指派（assignee FK）、成員機制、科別隔離
- **Spring-WbsScaff**：WBS 樹編輯、範本／快速項目概念、專案生命週期

本專案為**全新 repo**，不以任一舊專案為基底，但大量移植兩者已驗證的程式碼與模式。

### 核心概念：WBS 節點即任務（單一資料模型）

不做「規劃層＋執行層」雙模型。WBS 樹的細項節點（L3）本身就是可派工的任務，
看板、人員派工、甘特都是同一份節點資料的不同檢視。資料天然一致，無同步問題。

### 三層固定語意

| 層級 | 語意 | 範例 | 可派工 |
|---|---|---|---|
| L1 | 階段（Stage） | SIT、UAT、PROD | 否（狀態彙總） |
| L2 | 大項類別（Category） | 程式開發、環境建置、使用者測試 | 否（狀態彙總） |
| L3 | 細項（Item） | xxx功能開發、防火牆申請、xxx功能測試 | **是** |

L1/L2 名稱來自「預設選單＋可管理擴充」（`wbs_presets`），選入後**存文字快照**，
修改選單不影響既有專案。L3 自由輸入。後端強制三層上限。

## 2. 技術棧

| 項目 | 選擇 | 說明 |
|---|---|---|
| 後端 | Java 21 + Spring Boot 3.4.x（Maven） | 與兩舊專案一致 |
| 資料庫 | **PostgreSQL 16**（Docker） | 符合本機 /new-system 標準；schema 手寫於 `sql/`，`ddl-auto: none` |
| 安全 | Spring Security 6 表單登入 + Spring Session JDBC | HttpOnly cookie，無 JWT |
| 前端 | Thymeleaf 3 頁殼 + Vue 3 離線版（無 build 工具） | 混合 SSR + CSR，全 vendored |
| API 型態 | **v1 純 REST** | 不做 WebSocket；service 層為唯一寫入口，v2 加 STOMP 廣播即可 |
| 匯出 | Apache POI（XLSX） | 移植舊專案 |
| 其他 | spring-dotenv、Lombok、Bean Validation | 同舊專案慣例 |

## 3. 資料模型

沿用舊專案、僅換 PostgreSQL 方言的表：`departments`（部→科自參照樹）、
`users`（四角色：DIRECTOR / SECTION_CHIEF / PROJECT_LEADER / PROJECT_MEMBER）、
`projects`（含 owner、created_by、archived）、`project_members`（複合 PK＋assigned_by）。

### wbs_nodes（核心表，節點即任務）

| 欄位 | 型別／約束 | 說明 |
|---|---|---|
| id | bigserial PK | |
| project_id | FK → projects，NOT NULL | |
| parent_id | FK → wbs_nodes，NULL＝L1 | adjacency list |
| level | smallint CHECK (1..3) | 冗餘欄位，讓「僅 L3 可派工」可用 DB 約束保證 |
| title | varchar(300) NOT NULL | L1/L2 由選單帶入後存文字快照 |
| assignee_id | FK → users，NULL | **僅 L3 允許**（CHECK：level=3 或 IS NULL）；須為專案成員 |
| status | varchar CHECK (NOT_STARTED / IN_PROGRESS / DONE) | **僅 L3 儲存**；L1/L2 由子節點即時彙總（全完成→DONE、部分→IN_PROGRESS、全未動→NOT_STARTED），不落地 |
| priority | varchar CHECK (HIGH / MEDIUM / LOW) | 僅 L3，看板排序用 |
| start_date / end_date | date | 僅 L3 可編輯；L1/L2 顯示子節點 min/max（計算值） |
| notes | text | |
| sort_order | int | 同層排序 |
| created_at / updated_at | timestamptz | |

### wbs_presets（階段與類別選單）

| 欄位 | 說明 |
|---|---|
| id, name, sort_order, enabled | |
| type | STAGE / CATEGORY |
| section_id | NULL＝系統全域預設；有值＝該科自訂（科別隔離） |

種子資料：STAGE＝SIT、UAT、PROD；CATEGORY＝程式開發、環境建置、使用者測試。
SECTION_CHIEF 以上可於管理頁維護。

### 建專案初始化

建立專案時勾選階段（預設全選），系統自動產生 L1 骨架；
L2 從類別選單點選加入；L3 自由輸入。

## 4. API 與權限

統一 `ApiResponse` 信封＋`GlobalExceptionHandler`（移植舊專案）。

### 端點

- 認證與基礎：Spring Security 表單登入、`GET /api/users/me`、`GET /api/users`、`GET /api/departments`
- 專案：`GET/POST /api/projects`、`GET /api/projects/{id}`、`POST .../archive|unarchive`、
  `GET/POST/DELETE .../members`、`PUT .../owner`（封存＝全員唯讀）
- 節點：`GET/POST /api/projects/{id}/nodes`、`PUT/DELETE .../nodes/{nodeId}`、
  `PATCH .../nodes/reorder`（同層拖拉＋跨父搬移）、
  `PATCH .../nodes/{nodeId}/status`（看板拖拉專用）、
  `PATCH .../nodes/{nodeId}/assignee`（派工專用）
- 選單管理：`GET/POST/PUT/DELETE /api/presets?type=STAGE|CATEGORY`（科別隔離）
- 匯出：`GET /api/projects/{id}/export.xlsx`（欄位：階段／類別／細項／指派人／狀態／起迄）

### 權限（移植 WbsScaff 模式）

- 集中於 `ProjectService.canRead / canWrite`，一律先做 null 防禦
- DIRECTOR 跨科唯讀；SECTION_CHIEF 科內全權；PROJECT_LEADER 自有專案；PROJECT_MEMBER 僅參與專案
- IDOR 防護：所有節點操作先驗證 `node.project` 與路徑上的 project 一致（TaskFlow 鐵則）
- 指派人必須是專案成員；移除成員時自動解除其身上的指派（TaskFlow 既有行為）

## 5. 前端：單頁四檢視

專案詳情頁一次載入 `GET .../nodes`，四個 tab 共用同一份響應式資料；
所有修改走 REST 成功後就地更新（樂觀更新＋失敗回滾）。

1. **樹編輯器**（主檢視）：三層縮排樹、階層編號（1、1.1、1.1.1）、父層狀態／日期即時彙總、
   同層拖拉排序、L2 由類別選單快速加入、統計列（總數／完成率）。
   大量沿用 `wbs-editor.js` 做法（含批次延遲儲存）。
2. **看板**：三欄（未開始／進行中／完成），卡片＝L3 節點，
   顯示「階段 › 類別」麵包屑＋指派人＋截止日；HTML5 拖拉改狀態（移植 `board.js`）。
3. **人員派工視圖**：依指派人分組（含「未指派」組），每人顯示任務數與逾期數；
   卡片上可直接以下拉（專案成員）改指派人——派工主戰場。
4. **簡易甘特**：純 SVG 唯讀檢視，列＝L3（依階段／類別分組），橫條＝起迄日期，畫今日線。
   無依賴線、無關鍵路徑（YAGNI）。

其他頁面（移植舊專案）：登入、專案列表、封存歷史、成員管理、選單管理、錯誤頁。

## 6. 錯誤處理

- 後端：統一信封、全域例外處理、Bean Validation（純 REST，無「WebSocket 繞過驗證」問題）
- 前端：fetch 失敗顯示 toast 並回滾樂觀更新
- Null 防禦與冪等：封存／解封存冪等、權限檢查一律 null-guard（舊專案教訓）

## 7. 測試

- JUnit 分層測試（repository / service / controller），H2 PostgreSQL 相容模式＋`@ActiveProfiles("test")`
- 必測重點：權限矩陣（4 角色 × 讀／寫／封存）、三層深度上限、
  「僅 L3 可派工／可設狀態」約束、父層彙總邏輯、reorder 跨父搬移、移除成員解除指派

## 8. 部署

- docker compose：postgres:16 ＋ app；`.env` 管密碼（spring-dotenv，絕不硬編碼）
- `sql/01-schema.sql`＋`sql/02-seed.sql` 首次啟動自動執行
- 完全符合本機 `/new-system` 標準；實作第一步以 `/new-system` 建骨架
- 驗證定義：docker compose 啟動成功＋登入後四檢視可操作＋功能測試通過

## 9. v2 預留（本版不做）

- WebSocket 即時協作（STOMP 廣播掛在 service 寫入口即可）
- 專案另存為範本／套用範本
- 通知機制、甘特依賴線
