# WBS 檢視：子類別跨大項拖曳重新掛父節點 設計文件

> 前置閱讀：`docs/superpowers/specs/2026-08-19-wbs-stage-toggle-and-quick-add-design.md`（WBS 分類管理收斂設計，本檔延伸自其中的排序機制）。

## 背景

WBS 檢視現在的分類拖曳只支援「同一個大項底下、子類別互換順序」——`onCategoryDrop` 明確擋掉「拖曳來源與放置目標的 `parentCategoryId` 不同」的情況，直接無視不送任何請求。子類別一旦建錯大項，唯一的補救方式是刪除重建（會把底下任務打回未歸類，需要再手動一一改分類，體驗很差）。

第一代 WbsScaff（單機版）的樹狀編輯器支援拖曳到任意節點、依游標在目標列的上/下半部決定「插入同層」或「變成子節點」，是真正的任意重組。這次的目標不是照搬那一套（WbsScaff 沒有「任務」與「分類」的區分，MissionBoard 有），而是針對 MissionBoard 實際卡住的那一點——**子類別沒辦法換掛到別的大項底下**——做一個限定範圍的加強。

## 目標

拖曳一個子類別，放到「別的大項」的標題列，或放到「別的大項底下的任一子類別」上，該子類別（連同底下的任務）改掛到新的大項底下。

## 範圍限制（跟使用者已確認的前提）

- **大項（DEV/SIT/UAT/PROD 這類 STAGE）完全不受影響**：大項的啟用/停用、彼此順序，維持現有機制不動；這次新增的拖曳邏輯只套用在「拖曳物件是子類別」的情況，大項互相拖曳排序的既有邏輯不改
- **層數上限維持兩層**：子類別重新掛父節點後還是子類別（掛在某個大項底下），不會變成大項、也不會被拖進另一個子類別底下變成第三層——目標永遠解析成「某個大項」，不會是「某個子類別」
- **不做 WbsScaff 那種「游標在上/下半部決定插入 vs 巢狀」的雙模式手勢**：兩層模型下子類別只有「屬於哪個大項」跟「排在第幾個」兩個變數，不需要判斷要不要往下多一層
- **任務不用額外處理**：任務的 `category_id` 指向子類別本身的 id，子類別換父節點時任務欄位不變，資料自動跟著過去
- **不跳確認框**：重新掛父節點不是刪除，沒有資料流失風險，跟現有的改名/排序一樣走 toast 提示即可（詳見下方「已決定的兩個問題」）

## 已決定的兩個問題

1. **放置判定範圍**：拖到目標大項的「標題列」或該大項底下「任一子類別」，兩種都視為「重新掛到這個大項」。放標題列 → 補到該大項子類別清單最後面；放某個子類別上 → 插入到該子類別的位置（新大項底下、原本排在該位置的子類別跟後面的都往後推一位），跟現有同層拖曳排序的插入邏輯一致，不另外發明規則
2. **不跳確認框**：理由同上，非破壞性操作，維持跟改名/排序一致的低摩擦體驗；只有真正刪除（大項停用、子項刪除）才需要確認框

## 後端改動

**`TaskCategoryDto.UpdateRequest`** 加一個可選欄位 `parentCategoryId`：

```java
public record UpdateRequest(String name, Integer sortOrder, Long parentCategoryId) {}
```

**`TaskCategoryService.update()`** 新增處理 `parentCategoryId` 的分支，驗證規則：
- 只有「目前已經是子類別」（`category.getParentCategory() != null`）的分類才能改父節點——若對大項（`parentCategory == null` 的節點）送 `parentCategoryId`，回 400 報錯（明確拒絕，不要靜默忽略）
- 新的父節點必須存在、屬於同一個專案（沿用 `getCategoryInProject` 的 IDOR 防護）、且本身必須是大項（`newParent.getParentCategory() == null`）——不能把子類別掛到另一個子類別底下
- 驗證通過後 `category.setParentCategory(newParent)`

**既有的 `create()`／`delete()`／IDOR 防護（`getCategoryInProject`）不受影響，不用改。**

## 前端改動

`categoryPresetMixin.onCategoryDrop(targetCategory)`（`project-detail.js:296` 附近）現在的「跨層一律無視」規則，改成：
- 如果拖曳物件本身是大項（`dragging.parentCategoryId === null`）→ 維持現有行為不變（只能跟其他大項換順序，目標也必須是大項）
- 如果拖曳物件是子類別，且目標所屬的大項（目標若是大項本身，就是它自己；目標若是子類別，就是它的 `parentCategoryId`）跟拖曳物件目前的 `parentCategoryId`不同 → 判定為「重新掛父節點」，呼叫 `PUT /task-categories/{id}` 帶 `{ parentCategoryId: 新大項id, sortOrder: 計算出的新位置 }`，同時：
  - 本地把 `dragging.parentCategoryId` 更新成新值
  - 舊大項底下剩下的子類別重新編號 `sortOrder`（補上因為移走一個而空出來的位置）
  - 新大項底下（含新插入的 dragging）重新編號 `sortOrder`
- 如果拖曳物件是子類別，目標所屬大項跟原本相同 → 維持現有的同層排序邏輯不變

## 不做的事（YAGNI）

- 不支援子類別拖進另一個子類別變第三層
- 不支援大項改自由拖曳重新歸類（大項本身的排列/存廢維持既有機制）
- 不做 WbsScaff 式「游標位置決定插入 vs 巢狀」的雙模式判定
- 不加重新掛父節點的確認框

## 測試重點

- 子類別拖到別的大項標題列 → 改掛成功，補在該大項子類別清單最後面，原大項底下的其他子類別排序遞補、新大項底下排序正確
- 子類別拖到別的大項底下的某個子類別上 → 插入到該位置，前後排序都正確
- 子類別底下有任務時重新掛父節點 → 任務不用動、WBS 樹狀結構任務數與所屬大項正確反映新位置
- 大項互相拖曳排序 → 行為與現在完全一致，不受這次改動影響
- 嘗試把子類別拖進另一個子類別底下（模擬要生成第三層）→ 前端根本不會觸發這條路徑（因為判定邏輯只解析到「大項」層級），後端也要補一個防禦性測試：直接呼叫 API 帶一個子類別 id 當 `parentCategoryId` 應該報錯
- 後端 `TaskCategoryServiceTest`／`TaskCategoryControllerTest` 補：改子類別的 `parentCategoryId` 成功案例、目標 IDOR（換到別的專案的大項應該報錯）、目標型別錯誤（換到另一個子類別底下應該報錯）、對大項本身送 `parentCategoryId` 應該報錯
