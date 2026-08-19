# WBS 檢視：分類管理全面收斂 + 快速新增任務 + 任務狀態快速切換 設計文件

> **修訂版，取代本檔案第一版**（第一版只做「大項啟用/停用」與「未歸類快速新增」，範圍已擴大）。前置閱讀：`docs/superpowers/specs/2026-08-15-wbs-view-design.md`（WBS 檢視原始設計）、專案根 `CLAUDE.md` 前端模式章節。
>
> 第一版已完成的 Task 1（`deleteCategory` 搬進共用 `categoryPresetMixin`、`createCategoryFromPreset` 加可選 `parentCategoryId` 參數，commit `828b244`）在本修訂版中仍然有效、不用重做。

## 背景與問題

第一版設計原本只打算：(1) WBS 底部「+ 新增大項」改成啟用/停用切換鈕、(2)「未歸類」節點旁加快速新增任務。實際確認需求後，範圍擴大為：**看板「分類管理」面板整個移除，分類（大類/子類）的新增/改名/刪除/排序全部收斂到 WBS 檢視**，另外還要在「未歸類」與每個子類別節點旁都能快速新增任務，以及每筆任務可以快速循環切換狀態。

這推翻了專案 `CLAUDE.md` 既有記載的設計原則（「WBS 檢視不做新增子類別/改名/刪除/排序，這些維持只在看板分類管理面板操作」），是這次確認後的明確新方向：**WBS 檢視取代看板的分類管理面板，成為分類操作的唯一入口**。實作完成後需要同步更新 `CLAUDE.md`。

## 目標

1. 看板「分類管理」面板（開關按鈕、面板本身、新增階段/子類別彈窗、改名、拖曳排序、刪除按鈕）整個移除
2. WBS 檢視取得完整的分類 CRUD 能力：
   - 大項（STAGE）：啟用/停用切換鈕（沿用第一版設計）
   - 子類別（CATEGORY）：每個大項節點右邊加「+ 新增子項」，輸入名稱直接建立
   - 任一分類節點（大項或子類別）：雙擊改名、同層拖曳排序、旁邊加刪除按鈕
3. 「未歸類」節點與每個子類別節點右邊，都加「+ 新增」快速建立任務（只填標題）
4. 每筆任務列右邊加一個按鈕，點擊在「未開始 → 進行中 → 已完成」間循環切換狀態

## 範圍限制

- 快速新增任務的「+ 新增」不出現在大項（STAGE）節點旁——大項底下不能直接掛任務，任務只能落在「未歸類」或子類別，這是既有的資料模型限制（`tasks.category_id` 若非 NULL 必須指向類別，但看板/WBS 呈現時大項本身不收任務，任務只出現在其子類別或未歸類下），維持現狀不改
- 子類別的建立這次**不**引入選單挑選（`categoryPresets`/CATEGORY 型別 preset）與自由輸入名稱並存的兩種模式——直接拿掉選單挑選，全部改成輸入名稱；`task_category_presets` 的 CATEGORY 型別資料保留在資料庫（未來若要恢復挑選不影響資料），只是這次 UI 不用它
- 大項仍然只能從 STAGE 型別 preset 啟用/停用，不開放自由輸入大項名稱（DEV/SIT/UAT/PROD 這類階段名稱應該維持統一，不同專案間可比較）
- 不新增任務狀態以外的快速操作（不加指派人快速切換等）

## 設計 A：移除看板「分類管理」面板

`KanbanView` 移除：
- `data()` 裡的 `categoryPanelOpen`、`editingCategoryId`、`categoryNameDraft`、`draggingCategoryId`
- `methods` 裡的 `startEditCategoryName`、`commitCategoryName`、`onCategoryDragStart`、`onCategoryDrop`
- template 裡整個「分類管理」面板區塊（開關按鈕＋面板 markup）

`categoryPresetMixin` 裡的 `openPresetPicker`/`closePresetPicker`/`presetPicker` 狀態、`stagePresets`/`categoryPresets`/`loadCategoryPresets` 這些第一版就已經共用的邏輯**不動**——`categoryPresets`（CATEGORY 型別 preset 清單）雖然這次 UI 用不到了，但留著不影響行為，之後真的要清也是另一輪的事，這次不做無關的清理。

## 設計 B：WBS 分類管理面板（新增/改名/刪除/排序，大項+子類別）

### 大項（STAGE）

沿用第一版設計：一排啟用/停用切換鈕（讀 `stagePresets`，見第一版 spec 的「設計 A」段落，內容不變，此處不重複）。

### 子類別（CATEGORY）

每個大項節點的標題列右邊，加一個「+ 新增子項」按鈕（僅 `canWrite` 時顯示）。點擊後在同一列展開一個 inline 文字輸入框（跟「未歸類」快速新增任務的 UI 模式一致），輸入名稱、Enter 或按確認送出，呼叫建立分類 API（見下方「後端改動」）帶 `{ parentCategoryId: stage.id, name, sortOrder: null }`（不帶 `presetId`）。

### 改名、刪除、排序（大項與子類別共用同一套 UI）

WBS 樹狀結構裡，每個分類節點（大項、子類別皆是）：
- **改名**：雙擊節點名稱文字進入編輯狀態（`input` 就地取代文字），Enter 或失焦送出，呼叫既有的 `PUT /api/projects/{id}/task-categories/{id}`（`{ name }`）——這條路完全重用看板原本 `commitCategoryName` 的邏輯，只是換了觸發位置
- **刪除**：節點標題列右邊加一顆「刪除」按鈕（大項節點的刪除已經是啟用/停用切換鈕的「停用」動作，不用重複加；只有子類別節點需要新的刪除按鈕），呼叫既有的 `deleteCategory()`（Task 1 已搬進 `categoryPresetMixin`，WBS 直接可用）——子類別刪除時，其下任務會因為既有的 `tasks.category_id` `ON DELETE SET NULL` 自動落回未歸類，`deleteCategory()` 也已經處理好本地 `tasks` 快照同步，不用額外邏輯
- **排序**：同層（大項之間，或同一大項底下的子類別之間）拖曳重新排序，呼叫既有的 `PUT .../task-categories/{id}`（`{ sortOrder }`）——這條路完全重用看板原本 `onCategoryDragStart`/`onCategoryDrop` 的邏輯，只是換了觸發位置（原本掛在看板分類管理面板的節點上，現在掛在 WBS 樹狀節點上）

`startEditCategoryName`/`commitCategoryName`/`onCategoryDragStart`/`onCategoryDrop` 這四個方法從 `KanbanView` 搬到 `categoryPresetMixin`（跟 Task 1 搬 `deleteCategory` 是同一種重構，一次搬齊），讓 `WbsView` 能直接使用。

## 設計 C：快速新增任務（未歸類 + 每個子類別節點）

沿用第一版「未歸類」快速新增任務的設計（inline 輸入框、只填標題、Enter 送出、呼叫既有建立任務 API），額外把同一個 UI 模式套用到**每個子類別節點**旁邊，差別只在送出時 `categoryId` 帶該子類別的 `id`（未歸類節點送 `null`）。這是同一個共用邏輯（`openQuickAdd(categoryId)`/`closeQuickAdd()`/`submitQuickAdd()`，`openQuickAdd` 帶入目標 `categoryId`）套用在多個節點，不是各自獨立實作。

## 設計 D：任務狀態快速循環

`WbsTaskRow` 元件（`project-detail.js:706-729`）新增一個狀態按鈕，顯示目前狀態文字（沿用既有 `statusLabel`），點擊（`@click.stop`，避免觸發整列的 `@click="$emit('open')"` 開 modal）依序循環「未開始→進行中→已完成→未開始...」，`$emit('cycle-status')` 讓父層（`WbsView`）處理實際 API 呼叫。

`WbsView` 新增 `cycleTaskStatus(task)` 方法：依固定順序 `['NOT_STARTED', 'IN_PROGRESS', 'DONE']` 算出下一個狀態，重用既有的看板拖曳端點 `PATCH /api/projects/{id}/tasks/{id}/move`（`{ status, sortOrder }`），`sortOrder` 算法比照看板拖曳到欄尾的規則（`this.tasks.filter(t => t.status === nextStatus).length`，即附加到目標狀態的最後面）——不新增後端端點，這條端點本來就是為了「改狀態＋排序」設計的，插入到最後面是合理預設值。

## 後端改動（本次唯一的後端改動，範圍刻意壓到最小）

**`TaskCategoryDto.java`**：`CreateRequest` 加一個可選欄位 `name`：

```java
public record CreateRequest(Long parentCategoryId, Long presetId, String name, Integer sortOrder) {}
```

**`TaskCategoryService.create()`**：`presetId` 與 `name` 二擇一必填（都沒帶才報錯），有 `presetId` 走原本的 preset 快照邏輯（完全不變），沒有 `presetId` 但有 `name` 就直接用該名稱建立，其餘驗證（深度上限、IDOR）不變：

```java
@Transactional
public TaskCategory create(Long projectId, TaskCategoryDto.CreateRequest req) {
    if (req.presetId() == null && (req.name() == null || req.name().isBlank())) {
        throw new IllegalArgumentException("選單項目或名稱擇一必填");
    }
    Project project = projectService.getById(projectId);
    TaskCategory parent = null;
    if (req.parentCategoryId() != null) {
        parent = getCategoryInProject(projectId, req.parentCategoryId());
        if (parent.getParentCategory() != null) {
            throw new IllegalArgumentException("已達第二層，無法在類別下新增子類別");
        }
    }

    String name;
    if (req.presetId() != null) {
        TaskCategoryPreset preset = taskCategoryPresetRepository.findById(req.presetId())
            .orElseThrow(() -> new EntityNotFoundException("選單項目不存在"));
        TaskCategoryPreset.Type expectedType = parent == null
            ? TaskCategoryPreset.Type.STAGE : TaskCategoryPreset.Type.CATEGORY;
        if (preset.getType() != expectedType) {
            throw new IllegalArgumentException("選單項目型別不符");
        }
        if (preset.getSection() != null && !preset.getSection().getId().equals(project.getSection().getId())) {
            throw new IllegalArgumentException("選單項目不屬於此專案科別");
        }
        name = preset.getName();
    } else {
        name = req.name().trim();
    }

    TaskCategory category = new TaskCategory();
    category.setProject(project);
    category.setParentCategory(parent);
    category.setName(name);
    category.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
    return taskCategoryRepository.save(category);
}
```

前端呼叫方式：大項啟用（沿用第一版）帶 `presetId`；子類別新增改帶 `name`，不帶 `presetId`。兩種呼叫方式共用同一支 `categoryPresetMixin.createCategoryFromPreset`（Task 1 已加好可選 `parentCategoryId` 參數）不夠用了——子類別新增需要傳 `name` 而非 `presetId`，這次額外加一個 `createCategoryWithName(name, parentCategoryId)` 方法（放在 `categoryPresetMixin`，邏輯與 `createCategoryFromPreset` 幾乎一樣，只是 body 換成 `{ parentCategoryId, name, sortOrder: null }`）。

## 不做的事（YAGNI）

- 不支援大項自由輸入名稱（維持只能從 STAGE preset 啟用）
- 不支援子類別建立時「從選單挑選」與「輸入名稱」並存——這次只留輸入名稱一種
- 不支援任務狀態循環以外的批次操作、不支援自訂狀態循環順序
- 不刪除 `task_category_presets` 資料表裡的 CATEGORY 型別資料，也不刪對應的後端 API（`/api/task-category-presets?type=CATEGORY...`），只是這次 UI 不用它
- 不新增任務狀態以外的欄位（指派人/優先度/日期）快速編輯入口

## 測試重點

沿用第一版「未歸類快速新增任務」「大項啟用/停用」的測試重點（見第一版內容，此處不重複），額外新增：

- 子類別「+ 新增子項」輸入名稱建立 → 樹狀結構立即出現新子類別節點，不用重新整理頁面
- 子類別雙擊改名 → 呼叫既有 PUT 端點成功、失敗時前端回滾（比照看板原本 `commitCategoryName` 的回滾邏輯）
- 子類別拖曳排序（同一大項底下） → 呼叫既有 PUT 端點成功；跨大項拖曳（不同 parentCategoryId）應該被忽略、不送任何請求
- 子類別刪除 → 其下任務落回未歸類，樹狀結構與任務清單同步更新不用重新整理頁面
- 子類別旁「+ 新增」任務 → `categoryId` 正確帶該子類別 id
- 任務狀態循環按鈕：點擊三次應該依序「未開始→進行中→已完成→未開始」，看板分頁重新整理後狀態保持一致（驗證真的寫進後端，不是只改本地）
- 建立分類 API：`presetId` 與 `name` 都沒帶應該回 400；後端單元測試補一個「用 `name` 直接建立子類別成功」與「`presetId`/`name` 都缺失報錯」的案例（`TaskCategoryServiceTest`/`TaskCategoryControllerTest`）
- 看板分頁已無「分類管理」按鈕與面板，其餘看板功能（拖曳卡片跨欄、建立任務、指派）不受影響
