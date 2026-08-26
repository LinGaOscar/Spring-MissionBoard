# 子類別跨大項拖曳重新掛父節點 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WBS 檢視拖曳子類別到「別的大項」（標題列或該大項底下任一子類別）時，子類別（連同底下任務）改掛到新的大項底下，不再被「跨層一律無視」擋掉。

**Architecture:** 後端 `TaskCategoryDto.UpdateRequest` 加一個可選 `parentCategoryId` 欄位，`TaskCategoryService.update()` 補驗證邏輯；前端 `categoryPresetMixin.onCategoryDrop()` 拆成「同層排序」與「跨大項重新掛父節點」兩條路徑。

**Tech Stack:** Spring Boot 3 + JPA、vanilla Vue 3（無 build 工具）。

## Global Constraints

- 大項（DEV/SIT/UAT/PROD 這類 STAGE，`parentCategoryId === null`）完全不受這次改動影響：大項互相拖曳排序的既有邏輯不能變、大項不能被改掛父節點
- 層數上限維持兩層：新的父節點一定要是大項，不能把子類別掛到另一個子類別底下
- 不跳確認框（非破壞性操作，跟現有改名/排序一致，走 toast 提示）
- 不做「游標位置決定插入 vs 巢狀」的雙模式手勢——兩層模型下子類別只有「屬於哪個大項」跟「排第幾個」兩個變數
- 任務不用額外處理：任務的 `category_id` 指向子類別本身，子類別換父節點時任務欄位不變
- 這個專案的前端沒有 JS 測試框架，前端驗證一律用啟動應用程式後以瀏覽器手動操作 + chrome-devtools 截圖；後端改動需要 `mvn test` 通過
- 程式碼風格比照檔案現有寫法：不使用 optional chaining（`?.`）
- 測試帳號：`leader` / `password123`；種子資料 `project_id=1` 已有一個 `SIT` 大類、其下 `程式開發` 子類別（見 `sql/02_test_data.sql`）；應用程式網址 `http://localhost:8080`

---

### Task 1: 後端——`TaskCategoryDto.UpdateRequest` 支援改父節點

**Files:**
- Modify: `src/main/java/com/missionboard/task/TaskCategoryDto.java`
- Modify: `src/main/java/com/missionboard/task/TaskCategoryService.java:65-71`（`update()` 方法）
- Modify: `src/test/java/com/missionboard/task/TaskCategoryServiceTest.java`（1 處既有呼叫加參數 + 4 個新測試）
- Modify: `src/test/java/com/missionboard/task/TaskCategoryControllerTest.java`（1 個新測試）

**Interfaces:**
- Consumes：無新依賴
- Produces：`TaskCategoryDto.UpdateRequest(String name, Integer sortOrder, Long parentCategoryId)`——後續任務（Task 2）的前端 `onCategoryDrop` 會呼叫 `PUT /api/projects/{id}/task-categories/{id}`，body 帶 `{ parentCategoryId: 新大項id, sortOrder: 新位置 }`

- [ ] **Step 1: 修改 `TaskCategoryDto.UpdateRequest`，加入可選 `parentCategoryId` 欄位**

現在的 `TaskCategoryDto.java`：

```java
    public record UpdateRequest(String name, Integer sortOrder) {
    }
```

改成：

```java
    public record UpdateRequest(String name, Integer sortOrder, Long parentCategoryId) {
    }
```

（`parentCategoryId` 放最後——這會讓既有的 2 參數呼叫變成需要 3 參數，Step 3 統一處理）

- [ ] **Step 2: 修改 `TaskCategoryService.update()`，補上改父節點的驗證與邏輯**

現在（`TaskCategoryService.java:65-71`）：

```java
    @Transactional
    public TaskCategory update(Long projectId, Long categoryId, TaskCategoryDto.UpdateRequest req) {
        TaskCategory category = getCategoryInProject(projectId, categoryId);
        if (req.name() != null) category.setName(req.name());
        if (req.sortOrder() != null) category.setSortOrder(req.sortOrder());
        return taskCategoryRepository.save(category);
    }
```

改成：

```java
    // 改父節點：只有子類別能改（大項的 parentCategory 永遠是 null，不接受這個操作），
    // 新的父節點必須是大項（不能把子類別掛到另一個子類別底下，維持兩層上限）
    @Transactional
    public TaskCategory update(Long projectId, Long categoryId, TaskCategoryDto.UpdateRequest req) {
        TaskCategory category = getCategoryInProject(projectId, categoryId);
        if (req.name() != null) category.setName(req.name());
        if (req.sortOrder() != null) category.setSortOrder(req.sortOrder());
        if (req.parentCategoryId() != null) {
            if (category.getParentCategory() == null) {
                throw new IllegalArgumentException("大項不能改變父節點");
            }
            TaskCategory newParent = getCategoryInProject(projectId, req.parentCategoryId());
            if (newParent.getParentCategory() != null) {
                throw new IllegalArgumentException("新的父節點必須是大項");
            }
            category.setParentCategory(newParent);
        }
        return taskCategoryRepository.save(category);
    }
```

（`getCategoryInProject` 已經有 IDOR 防護，`newParent` 若不存在或不屬於這個專案會自動拋錯，不用額外寫檢查）

- [ ] **Step 3: 更新 `TaskCategoryServiceTest.java` 既有 1 處 `new TaskCategoryDto.UpdateRequest(...)` 呼叫**

`TaskCategoryServiceTest.java:129` 現在是：

```java
        TaskCategory updated = taskCategoryService.update(project.getId(), category.getId(),
            new TaskCategoryDto.UpdateRequest("改名後階段", 5));
```

改成：

```java
        TaskCategory updated = taskCategoryService.update(project.getId(), category.getId(),
            new TaskCategoryDto.UpdateRequest("改名後階段", 5, null));
```

- [ ] **Step 4: 在 `TaskCategoryServiceTest.java` 新增 4 個測試**

在 `updatesNameAndSortOrder` 測試（Step 3 改過的那個）之後，新增：

```java
    @Test
    void reparentsSubCategoryToAnotherStage() {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory stageB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageA.getId(), categoryPreset.getId(), null, null));

        TaskCategory moved = taskCategoryService.update(project.getId(), sub.getId(),
            new TaskCategoryDto.UpdateRequest(null, 0, stageB.getId()));

        assertThat(moved.getParentCategory().getId()).isEqualTo(stageB.getId());
    }

    @Test
    void rejectsReparentingAStageItself() {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory stageB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));

        assertThatThrownBy(() -> taskCategoryService.update(project.getId(), stageA.getId(),
                new TaskCategoryDto.UpdateRequest(null, null, stageB.getId())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReparentingToASubCategory() {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory subA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageA.getId(), categoryPreset.getId(), null, null));
        TaskCategory stageB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory subB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageB.getId(), categoryPreset.getId(), null, null));

        assertThatThrownBy(() -> taskCategoryService.update(project.getId(), subA.getId(),
                new TaskCategoryDto.UpdateRequest(null, null, subB.getId())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReparentingToStageFromAnotherProject() {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory subA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageA.getId(), categoryPreset.getId(), null, null));
        TaskCategory foreignStage = taskCategoryService.create(otherProject.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));

        assertThatThrownBy(() -> taskCategoryService.update(project.getId(), subA.getId(),
                new TaskCategoryDto.UpdateRequest(null, null, foreignStage.getId())))
            .isInstanceOf(SecurityException.class);
    }
```

- [ ] **Step 5: 在 `TaskCategoryControllerTest.java` 新增 1 個端對端測試**

先在測試類別加入 `TaskCategoryService` 的 autowire（其他欄位旁邊，例如 `taskCategoryPresetRepository` 之後）：

```java
    @Autowired
    private TaskCategoryService taskCategoryService;
```

然後在 `createDeniedForOutsideSectionUser` 測試之後新增：

```java
    @Test
    void reparentSubCategoryViaPutEndpoint() throws Exception {
        TaskCategory stageA = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory stageB = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(null, stagePreset.getId(), null, null));
        TaskCategory sub = taskCategoryService.create(project.getId(),
            new TaskCategoryDto.CreateRequest(stageA.getId(), null, "子類別", null));

        Cookie session = loginAs("leaderA");
        mockMvc.perform(put("/api/projects/{id}/task-categories/{catId}", project.getId(), sub.getId())
                .cookie(session).with(csrf())
                .contentType("application/json")
                .content("{\"parentCategoryId\":" + stageB.getId() + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.parentCategoryId").value(stageB.getId()));
    }
```

需要在檔案頂端的 import 區塊加入 `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;`——檢查現有的 `import static ... MockMvcRequestBuilders.*;`（`TaskCategoryControllerTest.java:25`）是不是萬用字元 import，若已經是 `.*` 就不用額外加，`put` 已經包含在內。

- [ ] **Step 6: 執行測試確認全部通過**

Run: `mvn test -Dtest=TaskCategoryServiceTest,TaskCategoryControllerTest`
Expected: 全部通過（既有測試因為改成 3 參數不會壞掉行為，新增的 5 個測試通過）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/missionboard/task/TaskCategoryDto.java src/main/java/com/missionboard/task/TaskCategoryService.java src/test/java/com/missionboard/task/TaskCategoryServiceTest.java src/test/java/com/missionboard/task/TaskCategoryControllerTest.java
git commit -m "feat: 分類更新 API 支援子類別改掛父節點大項"
```

---

### Task 2: 前端——WBS 檢視子類別跨大項拖曳重新掛父節點

**Files:**
- Modify: `src/main/resources/static/js/project-detail.js:296-325`（`categoryPresetMixin.onCategoryDrop`）

**Interfaces:**
- Consumes：Task 1 產出的 `PUT /api/projects/{id}/task-categories/{id}` 新增 `parentCategoryId` 欄位
- Produces：無其他任務依賴此任務的產出，這是最後一個任務

現在的 `onCategoryDrop`（`project-detail.js:295-325`，含上面的說明註解）：

```js
      // 只在同一層內重新排序：跨層（parentCategoryId 不同）一律忽略，不送任何請求
      async onCategoryDrop(targetCategory) {
        const draggingId = this.draggingCategoryId;
        this.draggingCategoryId = null;
        if (draggingId == null || draggingId === targetCategory.id) return;
        const dragging = this.categories.find(c => c.id === draggingId);
        if (!dragging || dragging.parentCategoryId !== targetCategory.parentCategoryId) return;

        const siblings = this.categories
          .filter(c => c.parentCategoryId === dragging.parentCategoryId)
          .sort((a, b) => a.sortOrder - b.sortOrder);
        const fromIdx = siblings.findIndex(c => c.id === dragging.id);
        const toIdx = siblings.findIndex(c => c.id === targetCategory.id);
        siblings.splice(fromIdx, 1);
        siblings.splice(toIdx, 0, dragging);

        const changed = [];
        siblings.forEach((c, i) => {
          if (c.sortOrder !== i) {
            c.sortOrder = i;
            changed.push(c);
          }
        });
        if (changed.length === 0) return;

        const results = await Promise.all(changed.map(c =>
          api(`/api/projects/${this.projectId}/task-categories/${c.id}`, {
            method: 'PUT', body: JSON.stringify({ sortOrder: c.sortOrder }),
          })
        ));
        if (results.some(r => !r.success)) {
          this.showToast('排序失敗，已重新載入');
          await this.loadAll();
        }
      },
```

- [ ] **Step 1: 拆成「同層排序」與「跨大項重新掛父節點」兩條路徑**

整段改成：

```js
      // 大項（parentCategoryId === null）只能跟其他大項換順序，不接受重新掛父節點；
      // 子類別放到「別的大項標題列」或「別的大項底下的子類別」都算重新掛父節點，
      // 放到「自己目前所在大項的標題列」是沒有動作的 no-op（不是同層排序，targetCategory
      // 是大項本身、不在任何 siblings 清單裡，不能走 reorderSiblings，否則
      // findIndex 找不到會回傳 -1，Array.splice(-1, ...) 不是「不做事」而是插到倒數第二個位置，
      // 會產生不該發生的排序副作用）
      async onCategoryDrop(targetCategory) {
        const draggingId = this.draggingCategoryId;
        this.draggingCategoryId = null;
        if (draggingId == null || draggingId === targetCategory.id) return;
        const dragging = this.categories.find(c => c.id === draggingId);
        if (!dragging) return;

        if (dragging.parentCategoryId === null) {
          // 大項只能跟大項換順序，行為與改動前完全相同
          if (targetCategory.parentCategoryId !== null) return;
          await this.reorderSiblings(dragging, targetCategory, null);
          return;
        }

        if (targetCategory.parentCategoryId === null) {
          // 目標是大項的標題列本身
          if (targetCategory.id === dragging.parentCategoryId) return; // 放回自己目前所在的大項，no-op
          await this.reparentSubCategory(dragging, targetCategory.id, null);
          return;
        }

        // 目標是子類別
        if (targetCategory.parentCategoryId === dragging.parentCategoryId) {
          await this.reorderSiblings(dragging, targetCategory, dragging.parentCategoryId);
        } else {
          await this.reparentSubCategory(dragging, targetCategory.parentCategoryId, targetCategory);
        }
      },
      // 同一個父節點底下重新排序：原本 onCategoryDrop 的邏輯，抽成獨立方法讓兩條路徑共用
      async reorderSiblings(dragging, targetCategory, parentId) {
        const siblings = this.categories
          .filter(c => c.parentCategoryId === parentId)
          .sort((a, b) => a.sortOrder - b.sortOrder);
        const fromIdx = siblings.findIndex(c => c.id === dragging.id);
        const toIdx = siblings.findIndex(c => c.id === targetCategory.id);
        siblings.splice(fromIdx, 1);
        siblings.splice(toIdx, 0, dragging);

        const changed = [];
        siblings.forEach((c, i) => {
          if (c.sortOrder !== i) {
            c.sortOrder = i;
            changed.push(c);
          }
        });
        if (changed.length === 0) return;

        const results = await Promise.all(changed.map(c =>
          api(`/api/projects/${this.projectId}/task-categories/${c.id}`, {
            method: 'PUT', body: JSON.stringify({ sortOrder: c.sortOrder }),
          })
        ));
        if (results.some(r => !r.success)) {
          this.showToast('排序失敗，已重新載入');
          await this.loadAll();
        }
      },
      // 子類別換掛到別的大項：targetSibling 是 null 就補到新大項底下的最後面
      // （放的是標題列本身），是一個子類別物件就插入到那個位置（放的是某個子類別列）——
      // 新舊大項底下的子類別都要重新編號 sortOrder
      async reparentSubCategory(dragging, targetStageId, targetSibling) {
        const oldParentId = dragging.parentCategoryId;
        const newSiblings = this.categories
          .filter(c => c.parentCategoryId === targetStageId && c.id !== dragging.id)
          .sort((a, b) => a.sortOrder - b.sortOrder);
        const insertAt = targetSibling
          ? Math.max(0, newSiblings.findIndex(c => c.id === targetSibling.id))
          : newSiblings.length;
        newSiblings.splice(insertAt, 0, dragging);

        dragging.parentCategoryId = targetStageId;
        const changed = [dragging];
        newSiblings.forEach((c, i) => {
          if (c.sortOrder !== i) {
            c.sortOrder = i;
            if (c !== dragging) changed.push(c);
          }
        });

        const oldSiblings = this.categories
          .filter(c => c.parentCategoryId === oldParentId && c.id !== dragging.id)
          .sort((a, b) => a.sortOrder - b.sortOrder);
        oldSiblings.forEach((c, i) => {
          if (c.sortOrder !== i) {
            c.sortOrder = i;
            changed.push(c);
          }
        });

        const results = await Promise.all(changed.map(c => {
          const body = c === dragging
            ? { parentCategoryId: targetStageId, sortOrder: c.sortOrder }
            : { sortOrder: c.sortOrder };
          return api(`/api/projects/${this.projectId}/task-categories/${c.id}`, {
            method: 'PUT', body: JSON.stringify(body),
          });
        }));
        if (results.some(r => !r.success)) {
          this.showToast('重新掛父節點失敗，已重新載入');
          await this.loadAll();
        }
      },
```

- [ ] **Step 2: 啟動應用程式，手動驗證**

```bash
docker compose up -d
mvn spring-boot:run
```

用瀏覽器打開 `http://localhost:8080/login`，以 `leader` / `password123` 登入，進入 `project_id=1` 專案詳情頁、切到「WBS 檢視」分頁：

1. 先用底部的啟用/停用切換鈕多啟用一個大項（例如「UAT」），畫面應該有 SIT、UAT 兩個大項
2. 拖曳「SIT」底下「程式開發」子類別的「⠿」把手，放到「UAT」的**標題列**上 → 「程式開發」應該從 SIT 底下消失、出現在 UAT 底下（補在最後面），SIT 跟 UAT 的任務數統計同步更新，不用重新整理頁面
3. 重新整理頁面，確認「程式開發」持久化後仍在 UAT 底下（驗證真的寫進後端）
4. 「程式開發」底下若有任務，確認任務還在（沒有掉到未歸類），且任務數正確算進 UAT 的統計
5. 在 UAT 底下新增第二個子類別（例如「測試」），拖曳剛才的「程式開發」到「測試」的**列上**（不是標題列）→ 確認插入到「測試」的位置，兩者順序正確；重新整理頁面順序保持
6. 拖曳兩個大項（SIT、UAT）互相換順序 → 確認行為與改動前完全一致（不受這次改動影響）
7. 拖曳「程式開發」到自己目前所在的大項標題列上（沒有真的換大項）→ 不應該有任何請求送出、畫面無變化
8. console 無錯誤

Expected: 以上八項全部符合。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/project-detail.js
git commit -m "feat: WBS 檢視子類別可跨大項拖曳重新掛父節點"
```

---

## 完成後建議動作（不算在計畫任務內，執行者提醒使用者即可）

- 提醒使用者可執行 `/sync-docs` 同步 `docs/dev.md` 與 `README.md`
- 提醒使用者這次改動擴充了 `CLAUDE.md`「前端模式」章節記載的 WBS 分類管理能力（子類別現在可以跨大項重新掛父節點），建議一併更新 `CLAUDE.md`
