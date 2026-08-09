<!-- 本檔由 /sync-docs 自動生成，請勿手動編輯——手改內容下次會被覆蓋。 -->
# Spring-MissionBoard

以扁平任務模型為核心的看板式任務管理系統：任務（`tasks`）天生獨立存在，分類（`task_categories`）選配、最多兩層，看板拖曳為主要操作介面。

## 技術棧

- Java 21 + Spring Boot 3.4.x（Maven）
- PostgreSQL 16（Docker）
- Spring Security 6 表單登入 + Spring Session JDBC
- Thymeleaf 3 頁殼 + Vue 3 離線版（無 build 工具，全 vendored）
- Apache POI（匯出）、spring-dotenv、Lombok

## 快速開始

```bash
cp .env.example .env
docker compose up -d
mvn spring-boot:run
```

開啟 http://localhost:8080 登入。完整開發環境設定（環境變數、資料庫重置、測試指令）見 [docs/dev.md](docs/dev.md)。

## 目錄結構

```
src/main/java/com/missionboard/
  auth/        # 表單登入、SecurityConfig
  department/  # 部門／科別
  user/        # 使用者與角色
  project/     # 專案、成員、權限核心（canRead/canWrite）
  task/        # 任務、分類、分類選單（核心資料模型）
  common/      # ApiResponse 信封、GlobalExceptionHandler
src/main/resources/
  templates/   # Thymeleaf 頁殼
  static/      # Vue 3 前端（js/project-detail.js 為看板主邏輯）
sql/           # 手寫 DDL 與測試種子資料
docs/          # 開發文件與設計文件（docs/superpowers/）
```
