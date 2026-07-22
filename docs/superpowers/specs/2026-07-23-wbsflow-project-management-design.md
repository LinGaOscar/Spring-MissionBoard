# 子專案 C：專案管理 設計文件

日期：2026-07-23
狀態：已與使用者逐節確認核准

## 1. 範圍

延續 `docs/superpowers/specs/2026-07-17-wbsflow-design.md` 第 4 節「專案」端點群組。本子專案只做 **Project 本體的生命週期管理**：建立、列表、查詢、封存/還原、成員管理、換負責人，以及支援用的 `GET /api/users`。

**明確排除（留給後續子專案）：**
- 建立專案時「勾選階段自動產生 L1 骨架」——需要 `WbsNode` 的建立邏輯，留給節點子專案一併設計，避免同時碰兩個尚未定案的領域。
- `GET /api/departments` 端點——目前沒有前端場景需要它（section 由後端自動代入，不經前端選單），等未來真的有選單管理或部門管理需求再開。
- 前端 Vue 四檢視（樹編輯器／看板／人員派工／甘特）——純後端 REST，只加一個 Thymeleaf `/projects` 頁殼作為登入後的入口頁面，沿用子專案 B 的 header/sidebar/footer fragment。

## 2. 資料模型

不新增欄位或表格，沿用子專案 A 已建立的 `Project`、`ProjectMember`、`ProjectMemberId` entity 與 `sql/01_ddl.sql` 既有 schema。

## 3. 權限模型（擴充 `ProjectService`）

現有 `canRead`/`canWrite` 不變。新增：

```java
// 邏輯與 canWrite 相同，但不檢查 archived 旗標——
// 否則已封存的專案因 canWrite 對封存旗標的判斷永遠回 false，會變成沒有人能解封存
public boolean canArchive(Long projectId, User user)
```

角色矩陣（`canArchive`）：
| 角色 | 可封存/還原 |
|---|---|
| DIRECTOR | 否 |
| SECTION_CHIEF | 同科的專案：是 |
| PROJECT_LEADER / PROJECT_MEMBER | 是該專案成員：是 |

**建立專案權限**：任何已登入使用者皆可建立（不限角色）。

**建立專案的 section 代入**：`section = creator.getDepartment()`。若建立者 `department` 為 `null`，拋 `IllegalStateException`（訊息：`使用者無所屬部門，無法建立專案`），交由 `GlobalExceptionHandler` 轉 400。這是邊界防禦——目前種子資料四角色都有部門，此路徑預期不會在正常流程觸發。

**成員管理權限（新增/移除成員、換負責人）**：沿用既有 `canWrite`（DIRECTOR 一律不可、SECTION_CHIEF 同科可、LEADER/MEMBER 是成員即可），不另建更嚴格的專屬權限層。

**換負責人的成員不變量**：`changeOwner` 若新 owner 尚未是專案成員，自動補加為成員（`assignedBy` = 執行操作者），確保新 owner 一定擁有對自己專案的 `canWrite`。

**移除成員的連動清除（CLAUDE.md 核心規則）**：`removeMember(projectId, userId)` 除了刪除 `project_members` 列，還必須把該 project 下 `wbs_nodes.assignee_id = userId` 的節點指派清空。透過 `WbsNodeRepository` 新增一個 `@Modifying @Query` 方法達成（`WbsNode` entity/repository 已在子專案 A 建好，即使節點 CRUD 尚未實作，這個清除邏輯仍屬於「成員生命週期」的一部分，必須在本子專案完成，否則移除成員後殘留無效指派違反 CLAUDE.md 明列的鐵則）。

## 4. API 端點

統一 `ApiResponse` 信封，`GlobalExceptionHandler` 轉換例外。

| 方法 | 路徑 | 權限檢查 | 說明 |
|---|---|---|---|
| GET | `/api/projects?archived=false` | 依角色範圍 | DIRECTOR 全部；SECTION_CHIEF 同科；PROJECT_LEADER/PROJECT_MEMBER 僅參與 |
| POST | `/api/projects` | 任何登入者 | body: `{name, description}`；owner=createdBy=呼叫者，自動加為成員 |
| GET | `/api/projects/{id}` | `canRead` | |
| PATCH | `/api/projects/{id}/archive` | `canArchive` | 冪等（已封存再封存不報錯） |
| PATCH | `/api/projects/{id}/unarchive` | `canArchive` | 冪等 |
| GET | `/api/projects/{id}/members` | `canRead` | |
| POST | `/api/projects/{id}/members` | `canWrite` | body: `{userId}`；已是成員則略過（冪等） |
| DELETE | `/api/projects/{id}/members/{userId}` | `canWrite` | 連動清除節點指派 |
| PUT | `/api/projects/{id}/owner` | `canWrite` | body: `{userId}`；新 owner 非成員時自動補加 |
| GET | `/api/users?departmentId=` | 已登入即可 | 不傳參數回全部使用者；供成員新增下拉選單 |

### DTO（`ProjectDto`，record 風格，比照 `UserController.UserMeResponse`）

```java
record Response(Long id, String name, String description,
                 Long sectionId, String sectionName,
                 Long ownerId, String ownerUsername, String ownerDisplayName,
                 boolean archived, LocalDateTime createdAt)

record MemberResponse(Long userId, String username, String displayName,
                       String role, LocalDateTime joinedAt)

record CreateRequest(@NotBlank String name, String description)
```

`GET /api/users` 回傳精簡使用者資訊（沿用 `UserController.UserMeResponse` 的欄位形狀：`id, username, displayName, role`），不含密碼等敏感欄位。

## 5. IDOR 與 Null 防禦

- 所有 `{id}` 路徑操作一律先呼叫對應的 `canRead`/`canWrite`/`canArchive` 檢查，權限不足丟 `SecurityException`（`GlobalExceptionHandler` 轉 403）。
- `canRead`/`canWrite`/`canArchive` 一律先做 null 防禦（沿用既有實作慣例）。
- 移除成員、換負責人時，若目標 `userId` 不存在，丟 `EntityNotFoundException`（404）。

## 6. 前端頁殼

新增 `src/main/resources/templates/project/list.html`，沿用 `fragments/header`、`fragments/sidebar`、`fragments/footer`。純伺服器渲染的靜態卡片列表（呼叫 `GET /api/projects` 用 Thymeleaf 或極簡 inline script 渲染），不做互動式建立/封存操作——那些留給後續子專案接 Vue 後統一做樂觀更新。`fragments/sidebar.html` 加入「專案列表」連結。

## 7. 測試重點

延續子專案 A/B 的分層測試風格（H2 + `@ActiveProfiles("test")`）：

**`ProjectServiceTest`（擴充既有檔案）：**
- `canArchive` 四角色矩陣，含封存後 SECTION_CHIEF/LEADER 仍可解封存（驗證不受 archived 旗標影響）
- `createProject` 的 section 自動代入；建立者無部門時拋例外
- `changeOwner` 對非成員新 owner 的自動補加成員
- `removeMember` 連動清除該使用者在該專案下的節點指派（需先建立測試用 `WbsNode`）

**`ProjectControllerTest`（新增）：**
- `MockMvc` + `formLogin` 走完整登入態
- 列表依角色範圍（含 archived 參數切換）
- 建立、封存/解封存（含冪等）、成員增刪（含冪等）、換負責人
- 跨科 IDOR：非同科、非成員的角色對 `canRead`=false 的專案操作應被拒絕（403）

**`UserControllerTest`（擴充既有檔案）：**
- `GET /api/users` 全部與 `?departmentId=` 篩選

## 8. 驗證與完成定義

比照子專案 B：`mvn test` 全綠 → 實機啟動 → 瀏覽器以測試帳號登入後操作 `/projects` 頁面與對應 API（含跨角色測試：`chief` 建立/封存、`leader` 換 owner、`member` 被移除後指派清空）→ 截圖存證。
