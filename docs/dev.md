# 開發文件（dev.md）

> 本檔由 /sync-docs 自動生成，請勿手動編輯——手改內容下次會被覆蓋。

## 環境需求

- Java 21（`pom.xml` 的 `java.version`）
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

- 服務：`docker-compose.yml` 中的 `db`（`postgres:16`），對外 port `5432`，資料庫與使用者皆為 `missionboard`
- 初始化：`docker compose up -d` 首次啟動時自動執行 `sql/01_ddl.sql`（schema）與 `sql/02_test_data.sql`（測試帳號＋種子資料）
- 重置（調整過 `sql/*.sql` 後必做）：
  ```bash
  docker compose down -v && docker compose up -d
  ```
- Schema 為手寫 DDL，`ddl-auto: none`，不使用 Flyway/Liquibase
- `SPRING_SESSION*` 表由 `spring.session.jdbc.initialize-schema: always` 於啟動時自動建立，不需手寫 DDL
- 進容器查資料庫（角色 `postgres` 不存在，必須帶環境變數）：
  ```bash
  docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1"'
  ```
- 測試帳號（密碼皆為 `password123`，來源：`sql/02_test_data.sql`）：

  | 帳號 | 顯示名稱 | 角色 | 所屬科別 |
  |---|---|---|---|
  | `director` | 主任 | `DIRECTOR` | 資訊部（部級） |
  | `chief` | 科長 | `SECTION_CHIEF` | 系統科 |
  | `leader` | 專案負責人 | `PROJECT_LEADER` | 系統科 |
  | `member` | 專案成員 | `PROJECT_MEMBER` | 系統科 |
  | `member2` | 專案成員二 | `PROJECT_MEMBER` | 網路科（供跨科隔離測試） |
- 測試套件使用 H2（PostgreSQL 相容模式）＋ `src/test/resources/application-test.yml`，不需要 Docker

## API 文件

應用啟動後可查閱 OpenAPI 規格（`springdoc-openapi` 2.7.0）：

| 路徑 | 內容 |
|---|---|
| http://localhost:8080/swagger-ui/index.html | Swagger UI 互動式介面 |
| http://localhost:8080/v3/api-docs | OpenAPI 3 JSON 規格 |

兩者都需要先登入——未登入存取會被導向 `/login`。

## 常用指令

| 指令 | 用途 |
|---|---|
| `mvn spring-boot:run` | 啟動應用程式 |
| `mvn test` | 執行全部測試 |
| `mvn test -Dtest=ClassName` | 執行單一測試類別 |
| `mvn test -Dtest=ClassName#methodName` | 執行單一測試方法 |
| `docker compose up -d` | 啟動 PostgreSQL |
| `docker compose down -v && docker compose up -d` | 重置資料庫（清空 volume 後重新初始化） |

## 開發輔助腳本

`scripts/intro-video/` 是專案介紹短片的產片 pipeline（Python 3 + Pillow + Playwright + ffmpeg），與應用程式本身無關，只在需要重製 `docs/missionboard-intro.mp4` 時使用。重跑方式與注意事項見該目錄的 `README.md`。

```bash
cd scripts/intro-video && python3 -m unittest discover -s tests -t .   # pipeline 自身的測試
```
