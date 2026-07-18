# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 專案狀態

**骨架已建立，功能尚未實作。** 唯一的真相來源是 `docs/superpowers/specs/2026-07-17-wbsflow-design.md`——動手前先讀它；本檔僅摘錄關鍵決策。
已完成：docker-compose（PostgreSQL 16）、`sql/` 初始 DDL＋測試帳號種子、可開機的最小 Spring Boot 骨架（無業務邏輯）、Vue 3 離線版 vendor 檔案。
尚未實作：所有 Entity/Repository/Service/Controller、Spring Security 表單登入設定、前端四檢視頁面。依任務路由表，新功能一律先走 `superpowers:brainstorming`。

## 專案定位

綜合性任務管理器：WBS 規劃與任務派工同一系統。整合本機兩個舊專案的已驗證程式碼與模式（全新 repo，不以任一者為基底）：

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

### 單一資料模型：WBS 節點即任務

不做「規劃層＋執行層」雙模型。`wbs_nodes` 一張表，樹編輯器／看板／人員派工／甘特是同一份節點資料的四種檢視，無同步問題。

### 三層固定語意（後端強制上限）

- L1 階段（SIT/UAT/PROD）、L2 大項類別——皆**不可派工**，狀態與日期由子節點即時彙總（全完成→DONE、部分→IN_PROGRESS、全未動→NOT_STARTED），**不落地**
- L3 細項——唯一可派工層；`assignee_id`、`status`、`priority`、起迄日**僅 L3 儲存**，以 DB CHECK 約束保證（`level` 冗餘欄位就是為此存在）
- L1/L2 名稱從 `wbs_presets` 選單帶入後**存文字快照**，改選單不影響既有專案；選單有 `section_id` 科別隔離（NULL＝全域預設）

### 權限與安全（移植舊專案鐵則）

- 權限集中於 `ProjectService.canRead / canWrite`，一律先 null 防禦；四角色：DIRECTOR（跨科唯讀）、SECTION_CHIEF（科內全權）、PROJECT_LEADER（自有專案）、PROJECT_MEMBER（僅參與專案）
- **IDOR 防護**：所有節點操作先驗證 `node.project` 與 URL 路徑上的 project 一致
- 指派人必須是專案成員；移除成員時自動解除其身上的指派
- 封存專案＝全員唯讀；封存／解封存操作冪等

### API 慣例

統一 `ApiResponse` 信封＋`GlobalExceptionHandler`（自舊專案移植）。狀態變更與派工走專用 PATCH 端點（`/status`、`/assignee`、`/reorder`），不塞進泛用 PUT。

## 前端模式

專案詳情頁一次載入 `GET .../nodes`，四個 tab 共用同一份響應式資料；所有修改走 REST，成功後就地更新（樂觀更新＋失敗回滾、fetch 失敗顯示 toast）。甘特為純 SVG 唯讀，無依賴線（YAGNI，v2 再議）。

## 測試重點

權限矩陣（4 角色 × 讀／寫／封存）、三層深度上限、「僅 L3 可派工／可設狀態」約束、父層彙總邏輯、reorder 跨父搬移、移除成員解除指派。

## 驗證與完成定義（宣稱完成前必須全數通過）

- [ ] `docker compose up -d` 成功，容器內 `psql` 確認 DDL 與測試帳號已載入
- [ ] 應用程式實際啟動成功（附啟動記錄關鍵行）
- [ ] 核心功能逐項實測（HTTP 回應 / 測試輸出為證），登入後四檢視（樹編輯器／看板／人員派工／甘特）可操作
- [ ] **有畫面就有截圖**：主要頁面用 chrome-devtools 截圖附在回報中
- [ ] console 無錯誤、版面無異常
- [ ] commit 前跑過 `mvn test`
- 以上以實際執行結果為準，不以讀 code 代替驗證。

## 開發注意事項

- pom.xml 依 Spring-TaskFlow / Spring-WbsScaff 慣例調整（`mssql-jdbc` → `org.postgresql:postgresql`），dependency 座標與版本沿用兩舊專案已驗證組合（Spring Boot 3.4.0、Java 21、`spring-dotenv:4.0.0`、`poi-ooxml:5.3.0`）
- `spring-boot-starter-security` 已在 classpath 但尚無自訂 `SecurityConfig`，目前為 Spring Boot 預設表單登入（隨機產生密碼，見開機日誌）；實作登入功能時務必加上自訂 `SecurityConfig` + `CustomUserDetailsService`，否則測試帳號無法登入
- Spring Session JDBC 的 `SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES` 表由 `spring.session.jdbc.initialize-schema: always` 在應用啟動時自動建立，`sql/01_ddl.sql` 不需手寫
