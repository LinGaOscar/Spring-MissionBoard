# 開發文件（dev.md）

> 本檔由 /sync-docs 自動生成，請勿手動編輯——手改內容下次會被覆蓋。

## 環境需求

- Java 21
- Maven（本機安裝，專案未使用 `mvnw` wrapper）
- Docker（跑 PostgreSQL 16）

## 快速啟動

```bash
# 1. 複製環境變數範本，填入真實密碼
cp .env.example .env

# 2. 啟動 DB（首次會自動執行 sql/*.sql 完成初始化）
docker compose up -d

# 3. 啟動應用程式
mvn spring-boot:run
```

啟動後開啟 http://localhost:8080 ，用下方測試帳號登入。

## 環境變數

`.env.example` 定義的變數（複製為 `.env` 後填入真實值，`.env` 本體不可提交）：

| 變數 | 說明 |
|---|---|
| `POSTGRES_PASSWORD` | PostgreSQL 密碼 |

## 資料庫

- 服務：`docker-compose.yml` 中的 `db`（`postgres:16`），對外 port `5432`
- 初始化：`docker compose up -d` 首次啟動時自動執行 `sql/01_ddl.sql`（schema）與 `sql/02_test_data.sql`（測試帳號＋種子資料）
- 重置（調整過 `sql/*.sql` 後必做）：
  ```bash
  docker compose down -v && docker compose up -d
  ```
- Schema 為手寫 DDL，`ddl-auto: none`，不使用 Flyway/Liquibase
- 測試帳號（密碼皆為 `password123`）：`director` / `chief` / `leader` / `member` / `member2`，對應四種角色（`member2` 屬不同科，供跨科隔離測試）
- 測試套件使用 H2（PostgreSQL 相容模式）＋ `application-test.yml`，不需要 Docker

## 常用指令

| 指令 | 用途 |
|---|---|
| `mvn spring-boot:run` | 啟動應用程式 |
| `mvn test` | 執行全部測試 |
| `mvn test -Dtest=ClassName` | 執行單一測試類別 |
| `mvn test -Dtest=ClassName#methodName` | 執行單一測試方法 |
| `docker compose up -d` | 啟動 PostgreSQL |
| `docker compose down -v && docker compose up -d` | 重置資料庫（清空 volume 後重新初始化） |
