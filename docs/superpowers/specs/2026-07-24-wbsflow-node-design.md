# 子專案 D：WBS 節點與選單管理 設計文件

日期：2026-07-24
狀態：已與使用者逐節確認核准

## 1. 範圍

延續 `docs/superpowers/specs/2026-07-17-wbsflow-design.md` 第 3-5 節。本子專案做：

- `wbs_nodes` 的 CRUD、三層固定語意的應用層強制、reorder（含跨層級搬移）、父層狀態/日期即時彙總
- 建專案時的 L1 骨架初始化（子專案 C 留下的伏筆，補上 `POST /api/projects/{id}/nodes/init`）
- `wbs_presets`（STAGE/CATEGORY 選單）的 CRUD

**明確排除（留給後續子專案）：**
- 前端四檢視（樹編輯器／看板／人員派工／甘特）——純後端 REST，連 Thymeleaf 頁殼都不加，因為真正的互動需要 Vue，跟子專案 C 只加靜態頁殼的做法不同（那時候 fetch+render 就夠用；這裡的拖拉排序、批次延遲儲存等互動已經超出靜態頁殼能表達的範圍）
- 匯出 XLSX（`GET /api/projects/{id}/export.xlsx`）

## 2. 資料模型

不新增欄位或表格，沿用子專案 A 已建立的 `WbsNode`、`WbsPreset` entity 與 `sql/01_ddl.sql` 既有 schema（含四條 CHECK 約束：`chk_level_parent`、`chk_assignee_l3_only`、`chk_status_l3_only`、`chk_priority_l3_only`、`chk_dates_l3_only`）。應用層驗證是這些 DB 約束的第一道防線，避免使用者以無效資料觸發資料庫層例外（轉譯不友善）。

## 3. 節點建立與層級驗證

- L1：`parentId` 必須為 null，需傳 `presetId`（型別 `STAGE`），後端驗證該 preset 屬於全域（`section_id IS NULL`）或該專案所屬科別，把 `preset.name` 存為 `title` 文字快照（不建 FK，改選單不影響既有專案）
- L2：`parentId` 必須指向一個 L1 節點，需傳 `presetId`（型別 `CATEGORY`），驗證規則同上
- L3：`parentId` 必須指向一個 L2 節點，`title` 為自由文字，不接受 `presetId`
- 一律先驗證 `parent.project.id` 與路徑 `projectId` 一致（IDOR 防護），父節點層級 `parent.level` 若已是 3 則拒絕（400，不允許超過三層）
- `level` 由後端依父節點推算（`parentId == null` → 1；否則 `parent.level + 1`），不接受前端傳入

## 4. 節點更新

`PUT /api/projects/{id}/nodes/{nodeId}`：
- `title`、`notes`：任何層級可改
- `priority`、`startDate`、`endDate`：僅 L3 可改，非 L3 節點傳這些欄位值一律拒絕（400，訊息說明「僅細項可設定」），不寄望 DB CHECK 約束攔截以取得更友善的錯誤訊息
- **不接受** `status`、`assigneeId`——這兩個欄位只能透過下方的專用 PATCH 端點變更（沿用 CLAUDE.md「狀態變更與派工走專用 PATCH 端點，不塞進泛用 PUT」的既有原則）

## 5. 父層狀態／日期即時彙總

`GET /api/projects/{id}/nodes` 回傳整棵樹（含所有層級的節點）時，L1/L2 節點的 `status`／`startDate`／`endDate` 三個欄位在回應中是**計算值**，不是 DB 儲存值（DB 裡這些欄位對 L1/L2 恆為 null）：

- **狀態**：由下往上逐層算——先算每個 L2 節點的彙總狀態（依其 L3 子節點：全部 `DONE` → `DONE`；全部 `NOT_STARTED`（**含沒有任何子節點的空節點**）→ `NOT_STARTED`；其餘（含混合、含 `IN_PROGRESS`）→ `IN_PROGRESS`），再用同一規則、以每個 L2 的彙總結果作為輸入，算出其所屬 L1 的彙總狀態
- **日期**：`startDate` = 直接子節點彙總日期的最小值、`endDate` = 最大值（L2 取其 L3 子節點的 min/max；L1 取其 L2 子節點彙總後的 min/max）；沒有子節點時兩者皆為 `null`

## 6. reorder（含跨層級搬移）

`PATCH /api/projects/{id}/nodes/reorder`，body 為一組 `{nodeId, parentId, sortOrder}`：

- 每個 `nodeId`／`parentId` 一律先驗證屬於路徑上的 `projectId`（IDOR 防護，比照子專案 A/舊專案 WbsScaff 的既有模式）
- **允許跨層級搬移**（例如把一個 L3 節點直接搬到根層變 L1）：搬移後的新 `level` = 新父節點的 `level + 1`（或 1，若 `parentId` 為 null）
- 若搬移的節點本身帶有子樹，**必須連動位移整棵子樹的 level**（位移量 = 新 level − 舊 level），並在套用前先驗證：子樹中任何節點的新 level 若會超過 3，整個 reorder 操作直接拒絕（400），不做部分套用
- 子樹中原本是 L3、搬移後不再是 L3 的節點，`assignee_id`／`status`／`priority`／`start_date`／`end_date` 一併清空為 null（滿足 DB CHECK 約束，也符合「這些欄位僅 L3 適用」的語意）；原本不是 L3、搬移後變成 L3 的節點，這些欄位維持 null（不會自動生資料，之後由使用者透過專用端點設定）

## 7. 狀態與指派專用端點

- `PATCH /api/projects/{id}/nodes/{nodeId}/status`：僅 L3 可用（非 L3 節點呼叫回 400），驗證 enum 值合法
- `PATCH /api/projects/{id}/nodes/{nodeId}/assignee`：僅 L3 可用，新 `assigneeId` 必須是該專案的成員（呼叫既有 `ProjectService.isMember`），否則 400

## 8. 建專案 L1 骨架初始化

新端點 `POST /api/projects/{id}/nodes/init`：
- body：`{stagePresetIds: [Long]}`（不傳或空陣列 = 該專案科別可見的全部已啟用 `STAGE` 型別 preset，即全域 + 該科自訂）
- 為每個選定的 STAGE preset 建一個 L1 節點（`sortOrder` 依 `preset.sortOrder` 遞增）
- **冪等防護**：若該專案底下已存在任何 `WbsNode`，直接拒絕（400，訊息「專案已有節點，無法重複初始化」），避免重複呼叫產生重複骨架
- 這是子專案 C 遺留的整合點：C 的 `POST /api/projects` 建立專案後不自動產生節點，前端流程是「建立專案 → 接著呼叫這個 init 端點」兩步驟，不修改已上線並審查過的 `ProjectService.createProject`

## 9. wbs_presets 選單管理

- `GET /api/presets?type=STAGE|CATEGORY&sectionId=`：已登入即可查詢，回傳「全域（`section_id IS NULL`）＋指定科別自訂」合併結果，依 `sortOrder` 排序；`enabled=false` 的預設不回傳
- `POST/PUT/DELETE /api/presets`：僅 `SECTION_CHIEF` 可操作，且只能新增/編輯/刪除 `section_id` 等於自己所屬部門的項目；`section_id IS NULL` 的全域預設任何角色皆不可修改（含 `SECTION_CHIEF` 與 `DIRECTOR`）
- 刪除一個仍被既有 `WbsNode`（文字快照）參照的 preset 不影響既有節點——因為節點存的是文字快照而非 FK，這點沿用子專案 A 已定案的設計，本子專案不需要額外處理

## 10. 錯誤處理

沿用既有 `GlobalExceptionHandler`（`EntityNotFoundException`→404、`IllegalArgumentException`→400、`SecurityException`→403），不新增例外類型。所有驗證失敗（層級超限、非 L3 設定專屬欄位、非成員指派、選單權限範圍）一律用 `IllegalArgumentException` 或 `SecurityException`，依語意選擇。

## 11. 測試重點

延續既有分層測試風格（H2 + `@ActiveProfiles("test")`）：

- **節點建立**：三層深度上限（第 4 層建立被拒）、L1/L2 的 presetId 型別與科別驗證、L3 自由文字、IDOR（父節點跨專案）
- **父層彙總**：全 DONE／全 NOT_STARTED／混合／空節點四種情境，且驗證 L1 的彙總正確反映 L2 彙總後的結果（不是直接跳過 L2 看 L3）
- **reorder**：同層搬移、跨層級搬移（含子樹連動位移）、子樹超過三層上限被拒（且驗證拒絕時完全不套用、不是部分套用）、降級為非 L3 時專屬欄位被清空
- **狀態/指派專用端點**：非 L3 呼叫被拒、指派非專案成員被拒
- **L1 初始化**：正常建立、非空專案重複呼叫被拒、不傳 stagePresetIds 時預設全選已啟用 STAGE
- **選單管理權限矩陣**：SECTION_CHIEF 對自己科別可寫、對全域與其他科別唯讀或拒絕；其餘角色唯讀

## 12. 驗證與完成定義

比照子專案 B/C：`mvn test` 全綠 → 實機啟動 → 用測試帳號透過 API（curl 或瀏覽器開發工具）逐項驗證上述端點，特別是 reorder 跨層級搬移與彙總邏輯需要用實際多層節點資料手動驗證計算結果正確。
