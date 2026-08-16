# 專案管理功能（建立/封存/成員管理/匯出） 設計文件

## 背景與範圍

`docs/user-guide.md` 撰寫時盤點出四項功能後端 API 已存在（建立專案、封存/解封存、成員管理）或部分規劃過（匯出，`docs/superpowers/specs/2026-07-23-wbsflow-project-management-design.md` 曾規劃 `export.xlsx` 但隨舊模型移除從未落地），**前端完全沒有對應介面**。本次補上這四項功能的前端，並在過程中修補兩個順帶發現的既有缺口：

1. `ProjectController.detail()` 只傳 `canWrite`／`sectionId` 給 Thymeleaf model，前端無法區分「唯讀」與「可解封存」（封存後 `canWrite` 恆為 `false`）。
2. `ProjectService.removeMember` 沒有防止移除現任專案負責人（owner），會留下 `project.owner` 指向非成員的髒資料。

匯出功能是全新後端實作（`pom.xml` 已有 `poi-ooxml` 依賴，但目前 `src/main/java` 內完全沒有 export 相關程式碼）。

## 不做的事（明確排除）

- 不重做既有的 `canRead`/`canWrite`/`canArchive` 權限判斷邏輯，四項功能全部沿用現有規則
- 不新增匯出的 CSV/JSON 格式，僅做 Excel（xlsx）
- 匯出不做非同步/背景工作，任務量小，同步產生即可
- 不做通知（例如新增成員後通知對方）
- 不改動 `task-category-presets`／分類管理既有邏輯

## 1. 建立專案

### 前端：`project/list.html` 改掛 Vue 3

目前 `project/list.html` 是純 vanilla JS（`fetch` 後用 DOM API 組卡片），與 `project-detail.js` 的 Vue 3 模式不一致。本次一併改掛 Vue，新增 `static/js/project-list.js`，掛載到 `#list-app`，寫法比照 `project-detail.js`（`createApp`、CSRF header、`api()` 骨架）。原本的卡片渲染邏輯改寫為 Vue 元件，行為不變（點卡片連到 `/projects/{id}`）。

### Modal

- 列表頁右上角「新增專案」按鈕（不判斷角色，任何登入使用者皆可建立，見 `ProjectService.createProject`）
- 欄位：名稱（必填）、描述（選填）。**不需要**選 `section`／`owner`——後端自動代入建立者部門與建立者本人（`ProjectService.createProject` 既有邏輯不變）
- 提交 `POST /api/projects`，成功後導向新專案的 `/projects/{id}`
- 失敗（例如 `使用者無所屬部門，無法建立專案`）顯示錯誤訊息，不關閉 Modal

## 2. 封存 / 解封存

### 後端：暴露 `canArchive`

`ProjectController.detail()`（`src/main/java/com/missionboard/project/ProjectController.java:28-38`）新增：

```java
model.addAttribute("canArchive", projectService.canArchive(id, user));
```

`ProjectDto.Response` 已有 `archived` 欄位，列表 API 不需改動。

### 前端

- **列表頁**：頂部加「未封存／已封存」頁籤，切換打 `GET /api/projects?archived=false` 或 `?archived=true`
- **詳情頁工具列**：讀取 Thymeleaf 傳入的 `canArchive`（比照現有 `canWrite` 傳法，經 `data-can-archive` 屬性帶進 Vue）
  - `canArchive === true` 且專案未封存 → 顯示「封存」按鈕，呼叫 `PATCH /api/projects/{id}/archive`
  - `canArchive === true` 且專案已封存 → 顯示「解封存」按鈕，呼叫 `PATCH /api/projects/{id}/unarchive`
  - `canArchive === false` → 不顯示任何封存相關按鈕（主任恆為 `false`；科長/負責人/成員僅能動自己管得到範圍內的專案）
- 封存/解封存成功後重新載入專案基本資料（`archived` 狀態、按鈕文字），任務相關的三分頁不需重新整理

## 3. 成員管理

### 前端：詳情頁工具列新增「成員管理」展開面板

比照現有「分類管理」面板的展開/收合模式（`project-detail.js` 既有 pattern），面板內容：

- 目前成員清單（`GET /api/projects/{id}/members`），負責人特別標示
- 每位成員旁「移除」按鈕，**owner 那一列停用**（見下方「移除負責人防護」），並標註提示文字「請先轉移負責人」
- 新增成員：下拉選單（`GET /api/users`，**列出全部使用者，不限科別**——依討論結果，不用 `departmentId` 篩選），選定後 `POST /api/projects/{id}/members`
- 換負責人：下拉選單（現有成員清單），選定後 `PUT /api/projects/{id}/owner`

`canWrite === false` 時面板整體降為唯讀（隱藏移除/新增/換負責人的互動元件，沿用既有「唯讀時停用寫入控制項」慣例）。

### 移除成員時清理本地任務快照

`DELETE /api/projects/{id}/members/{userId}` 成功後，前端需同步：

1. 把 `taskBoardMixin` 共用的本地 `tasks` 快照中，該使用者的 `assigneeId` 清空（比照 CLAUDE.md 記載的分類刪除踩坑——後端已聯動清空指派，若前端快照不同步，看板/人員派工/WBS 三分頁會顯示殘留的舊指派人直到重新整理頁面）
2. 重新載入 `members` 清單（供人員派工分頁欄位、任務 modal 指派人下拉使用）

### 移除負責人防護（新發現缺口的修補）

`ProjectService.removeMember`（`src/main/java/com/missionboard/project/ProjectService.java:131-144`）目前沒有檢查是否移除現任 owner。新增檢查：

```java
if (project.getOwner().getId().equals(userId)) {
    throw new IllegalArgumentException("無法移除專案負責人，請先轉移負責人");
}
```

沿用既有「業務規則違反 → 400」慣例（`GlobalExceptionHandler` 對 `IllegalArgumentException` 的既有處理，比照分類深度上限的作法）。前端「成員管理」面板同步在 owner 列停用「移除」按鈕，避免使用者送出必然失敗的請求；後端檢查是最終防線（防止繞過前端直接呼叫 API）。

## 4. Excel 匯出

### 觸發點

只在 **WBS 檢視分頁**工具列加「匯出 Excel」按鈕（依討論結果，不在看板／人員派工分頁提供入口）。

### 後端

新增 `src/main/java/com/missionboard/task/TaskExportService.java`，移植 Spring-TaskFlow 的 `TaskExportService` 寫法（`XSSFWorkbook`），但改讀本專案的樹狀資料（`task_categories` 兩層 + 未歸類任務）而非扁平清單。

在既有 `TaskController` 新增端點：

```
GET /api/projects/{id}/export.xlsx
```

- 權限：比照現有讀取端點用 `canRead`（`canWrite=false` 的唯讀角色、封存專案皆可匯出——匯出本質是唯讀操作）
- 回應：`byte[]`，`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- 檔名含中文（例如「{專案名稱}_任務清單.xlsx」），需處理 `Content-Disposition` 的 RFC 5987 `filename*` 編碼，移植 TaskFlow `BoardController.download()` helper 的寫法

### 欄位與排序

欄位順序：**大類 / 子類 / 任務標題 / 指派人 / 狀態 / 優先度 / 起始日 / 到期日**

- 未歸類任務：「大類」欄顯示「未歸類」，「子類」欄留空
- 只有大類、沒有子類的任務：「子類」欄留空
- 列的排序比照 WBS 檢視畫面的樹狀顯示順序（未歸類 → 各大類 → 各大類底下的子類，同節點內任務依 `dueDate` 升冪、無到期日排最後，同值時依 `id` 升冪，與 `WbsView` 既有排序規則一致）
- 狀態／優先度輸出中文標籤，對齊前端 `project-detail.js` 既有的 `STATUS_LABELS`（未開始/進行中/已完成）與優先度選項（高/中/低），優先度為 `null` 時留空
- 日期欄輸出 `yyyy-MM-dd`，無日期留空

## 資料流總覽

| 功能 | 端點 | 權限檢查 |
|---|---|---|
| 建立專案 | `POST /api/projects` | 登入即可 |
| 封存 | `PATCH /api/projects/{id}/archive` | `canArchive` |
| 解封存 | `PATCH /api/projects/{id}/unarchive` | `canArchive` |
| 新增成員 | `POST /api/projects/{id}/members` | `canWrite` |
| 移除成員 | `DELETE /api/projects/{id}/members/{userId}` | `canWrite` + 非 owner |
| 換負責人 | `PUT /api/projects/{id}/owner` | `canWrite` |
| 匯出 Excel | `GET /api/projects/{id}/export.xlsx`（新增） | `canRead` |

## 錯誤處理

沿用既有 `api()` 骨架（CSRF、網路錯誤攔截、`showToast`）。各功能失敗情境：

- 建立專案失敗（如使用者無部門）→ Modal 內顯示錯誤，不關閉
- 封存/解封存失敗 → toast，按鈕狀態不變
- 移除 owner 失敗（後端 400）→ toast 顯示「請先轉移負責人」（理論上前端已停用按鈕，此為防線非主要路徑）
- 匯出失敗（例如 401/403）→ toast，不觸發下載

## 範圍外（明確不做）

- 不做匯出的欄位客製化（使用者選欄位）——固定欄位即可，YAGNI
- 不做批次建立專案、批次匯出多專案
- 不做成員角色在專案內的差異化管理（`PROJECT_LEADER`/`PROJECT_MEMBER` 權限本就相同，見 `docs/user-guide.md`）
- 換負責人不做「新負責人需先同意」的邀請流程，維持既有「立即生效」行為

## 測試重點

### 後端（新增/修改測試）

- `TaskExportService`／匯出端點：比照 Spring-TaskFlow `TaskExportServiceTest` 的驗證模式，涵蓋大類/子類/未歸類任務的欄位輸出正確性
- 匯出權限矩陣：`member2`（跨科）呼叫應 403；封存專案的成員呼叫應成功（200，唯讀操作不受封存限制）
- `removeMember` 移除現任 owner 應拋 `IllegalArgumentException`（400），移除非 owner 成員行為不變（既有測試維持）
- 封存專案下呼叫新增成員／換負責人應維持既有 403（`canWrite` 既有邏輯，非本次新增，用於回歸確認 `canArchive` 新增沒有破壞 `canWrite`）

### 前端（實際啟動應用程式手動驗證，非自動化測試）

- 建立專案：Modal 填寫送出 → 導向新專案詳情頁 → 專案列表可見
- 封存/解封存：科長/負責人操作成功、主任看不到按鈕、成員在已封存專案看不到寫入按鈕
- 成員管理：新增/移除成員、換負責人皆生效；移除成員後看板/人員派工/WBS 三分頁的指派人顯示即時清空（不需重新整理）；owner 列的移除按鈕停用
- 匯出：WBS 分頁點「匯出 Excel」下載檔案，開啟確認欄位與樹狀順序正確；未歸類任務顯示「未歸類」
- console 無錯誤，四項功能各自截圖存證

## 後續清理

實作完成後：

1. `CLAUDE.md` 補上四項功能的說明（建立專案 Modal、封存/解封存入口、成員管理面板、WBS 分頁匯出按鈕），移除舊有「成員管理／建立專案／封存/解封存前端尚未串接」的相關描述（若有）
2. `docs/user-guide.md` 的「目前尚未提供操作介面的功能」章節，移除本次已實作的四項，改寫入對應章節的「可用功能」內
