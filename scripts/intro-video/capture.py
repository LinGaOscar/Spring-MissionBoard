# -*- coding: utf-8 -*-
"""用 Playwright 擷取分鏡所需的真實畫面。

唯一需要瀏覽器的階段。腳本自己包辦 DB 重置與 app 啟動，
因此重跑結果具確定性（分鏡流程會現場建立專案與任務）。
"""
import json
import os
import re
import subprocess
import time
import urllib.request

from playwright.sync_api import sync_playwright

import storyboard as sb

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORK = os.path.join(REPO, ".superpowers", "intro-video")
RAW = os.path.join(WORK, "raw")
BASE_URL = "http://localhost:8080"
PASSWORD = "password123"
PORT = 8080

META = {"sprites": {}}


def _sh(cmd, check=True):
    return subprocess.run(cmd, shell=True, cwd=REPO, check=check)


def _port_pids(port):
    """回傳目前佔用該埠的 pid 清單（可能為空）。給啟動前檢查與收尾強制清理共用。

    這是「動資料庫前的最後守門員」，lsof 只有 returncode 0（查到程序）
    與 1（沒查到）是正常結果；其他 returncode（例如指令根本不存在時
    shell 回的 127）代表 lsof 沒有真的執行過，此時絕不能回傳空 list
    當作「沒有占用」放行——靜默誤判比直接炸掉危險得多。
    """
    probe = subprocess.run("lsof -ti:{}".format(port), shell=True,
                            capture_output=True, text=True)
    if probe.returncode not in (0, 1):
        raise RuntimeError(
            "檢查埠 {} 占用狀態失敗，lsof 執行異常"
            "（returncode={}, stderr={}）".format(
                port, probe.returncode, probe.stderr.strip()))
    return [pid for pid in probe.stdout.split() if pid]


def _ensure_port_free():
    """8080 若已被占用就直接炸掉，不做任何事。

    刻意獨立成函式，讓 main() 能在 reset_db() 之前就呼叫它：這次執行
    反正會失敗，失敗要趁早——不能等資料庫都砍掉重建完才發現「其實一開始
    就不可能成功」，那樣使用者白白付出一次不可逆的破壞性操作。
    start_app() 內部也呼叫同一份邏輯，讓它被單獨呼叫（例如互動除錯時
    跳過 main()）時依然安全，不必依賴呼叫端先做過檢查。
    """
    stale = _port_pids(PORT)
    if stale:
        raise RuntimeError(
            "埠 {} 已被程序占用（pid: {}），疑似上次執行殘留的孤兒程序，"
            "請先手動確認並清除再重跑".format(PORT, ", ".join(stale)))


def reset_db():
    """重置 DB。不重置的話第二次跑資料會疊加、畫面與分鏡對不上。"""
    print("[capture] 重置資料庫…")
    _sh("docker compose down -v")
    _sh("docker compose up -d")
    for _ in range(60):
        probe = subprocess.run(
            "docker compose exec -T db sh -c "
            "'pg_isready -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"'",
            shell=True, cwd=REPO, capture_output=True)
        if probe.returncode == 0:
            print("[capture] DB 就緒")
            return
        time.sleep(2)
    raise RuntimeError("DB 未在 120 秒內就緒")


def start_app():
    print("[capture] 啟動應用程式…")
    # main() 已在 reset_db() 之前呼叫過一次，這裡再呼叫同一份邏輯是為了
    # start_app() 被單獨呼叫（跳過 main()）時依然安全——否則就緒探測會
    # 直接打到殘留的舊程序拿到 200，誤判「啟動成功」，實際截到的其實是
    # 舊版畫面，而且完全不報錯。
    _ensure_port_free()

    log_path = os.path.join(WORK, "app.log")
    log = open(log_path, "w")
    proc = subprocess.Popen("mvn spring-boot:run", shell=True, cwd=REPO,
                            stdout=log, stderr=subprocess.STDOUT)
    for _ in range(90):
        try:
            if urllib.request.urlopen(BASE_URL + "/login", timeout=2).status == 200:
                print("[capture] 應用程式已就緒")
                return proc, log
        except Exception:
            pass
        time.sleep(2)
    # 逾時也要走完整的 terminate → wait → kill → 埠強制清理，不能只發一次
    # terminate 就放著——這條路徑本來就是最容易半途而廢、留下孤兒程序的地方。
    stop_app(proc, log)
    raise RuntimeError("應用程式未在 180 秒內啟動，詳見 " + log_path)


def stop_app(proc, log=None):
    proc.terminate()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        # SIGKILL 不會觸發 spring-boot-maven-plugin 的 shutdown hook
        # （RunProcessKiller 只掛在 SIGTERM 路徑上），mvn 程序沒了但它 fork
        # 出去的真正 java app 會變孤兒繼續佔用埠。因此 kill 之後再依連接埠
        # 強制收尾一次，並印出明確警告讓人工確認。
        proc.kill()
        proc.wait()
        stale = _port_pids(PORT)
        if stale:
            print("[capture] 警告：mvn 程序已 SIGKILL，但埠 {} 仍被 pid {} 占用"
                  "（疑似繞過 Maven shutdown hook 的孤兒 java 程序），"
                  "強制清除中，建議事後人工確認…".format(PORT, ", ".join(stale)))
            for pid in stale:
                subprocess.run("kill -9 {}".format(pid), shell=True)
    if log is not None:
        log.close()


def shot(page, scene_id):
    page.wait_for_timeout(400)
    page.screenshot(path=os.path.join(RAW, scene_id + ".png"))
    print("[capture] 截圖 " + scene_id)


def capture_card(page, scene):
    """片頭／片尾卡在同一顆瀏覽器裡渲染，字體與 UI 畫面才一致。"""
    page.set_content(scene.card_html)
    shot(page, scene.id)


def login(page, username):
    page.goto(BASE_URL + "/login")
    page.fill("#username", username)
    page.fill("#password", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url(BASE_URL + "/home")


def logout(page):
    """點頁首真正的「登出」連結。

    只是 goto /login 並不會終止 session，後續 login() 是否真的換人
    取決於未經驗證的行為——切視角時會靜默拿到上一個使用者的畫面。
    """
    page.get_by_text("登出").click()
    page.wait_for_url("**/login**")


_BBOX_JS = """
(selector) => {
  const r = document.querySelector(selector).getBoundingClientRect();
  return [Math.round(r.x), Math.round(r.y), Math.round(r.right), Math.round(r.bottom)];
}
"""

# 直接改 DOM 樣式做出「拖曳中」外觀。不去設 Vue 內部狀態——
# global build 的 app 實例沒掛在 window 上，從外部改很脆。
_DRAG_ON_JS = """
([cardSel, colSel]) => {
  document.querySelector(cardSel).style.opacity = '0.35';
  const col = document.querySelector(colSel);
  col.style.outline = '2px dashed #1D4ED8';
  col.style.outlineOffset = '-6px';
}
"""

_DRAG_OFF_JS = """
([cardSel, colSel]) => {
  document.querySelector(cardSel).style.opacity = '';
  const col = document.querySelector(colSel);
  col.style.outline = '';
  col.style.outlineOffset = '';
}
"""


def capture_drag(page, clean_id, base_id, card_selector, column_selector):
    """產出補間需要的兩張圖。

    clean_id：卡片完整不透明，供裁 sprite（bbox 一併記進 meta）
    base_id ：原位卡片壓透明、目標欄加虛線框，當補間底圖
    """
    page.wait_for_timeout(400)
    META["sprites"][clean_id] = page.evaluate(_BBOX_JS, card_selector)
    page.screenshot(path=os.path.join(RAW, clean_id + ".png"))

    page.evaluate(_DRAG_ON_JS, [card_selector, column_selector])
    page.wait_for_timeout(200)
    page.screenshot(path=os.path.join(RAW, base_id + ".png"))
    page.evaluate(_DRAG_OFF_JS, [card_selector, column_selector])
    print("[capture] 拖曳底圖 {} / {}".format(clean_id, base_id))


def measure_box(page, scene_id, selector):
    """量測 selector 的 bbox，記進 META["boxes"][scene_id]（合成時畫紅框標示用）。"""
    META.setdefault("boxes", {}).setdefault(scene_id, []).append(
        page.evaluate(_BBOX_JS, selector))


def _csrf(page):
    token = page.eval_on_selector('meta[name="_csrf"]', "el => el.content")
    header = page.eval_on_selector('meta[name="_csrf_header"]', "el => el.content")
    return token, header


def _modal_field(page, label):
    """任務 modal 的欄位一律是「<label>文字</label><input/textarea/select>」相鄰結構，
    沒有 for/id 關聯，get_by_label 用不了；用 has_text 圈出該欄位所在的 .form-group 再取子節點。
    """
    return page.locator(".modal .form-group", has_text=label)


def _create_task(page, title, assignee_label, due):
    """assignee_label 傳 None 代表刻意不指派——s14「成員自我認領」需要「未指派」欄有東西可拖。"""
    page.get_by_text("新增任務").click()
    page.wait_for_timeout(300)
    _modal_field(page, "標題").locator("input").fill(title)
    _modal_field(page, "到期日").locator("input").fill(due)
    if assignee_label is not None:
        _modal_field(page, "指派人").locator("select").select_option(label=assignee_label)
    page.locator(".modal-actions .btn-primary").click()
    page.wait_for_timeout(600)


def _get_tasks(page, project_id):
    res = page.request.get("{}/api/projects/{}/tasks".format(BASE_URL, project_id))
    if not res.ok:
        raise RuntimeError("取得任務清單失敗：{} {}".format(res.status, res.text()))
    return res.json()["data"]


def _task_by_title(tasks, title):
    for t in tasks:
        if t["title"] == title:
            return t
    raise RuntimeError("找不到任務：{}".format(title))


def _set_task_status(page, project_id, task_id, status, sort_order=0):
    """直接呼叫看板拖曳放開後前端本來就會打的同一支 /move API，不模擬拖曳。

    原生 HTML5 拖曳（draggable + dragstart/dragover/drop）沒有對應的合成事件可以觸發，
    Playwright／CDP 都送不出真的拖放序列，capture_drag() 也只能靠 CSS 偽裝「拖曳中」的
    靜態外觀，並不會真的呼叫後端。要讓 s09 之後的畫面真的呈現「卡片已經在進行中欄」，
    唯一可靠的做法是繞過畫面直接打狀態變更 API——這正是 KanbanView.sendMove() 拖放後
    自己會呼叫的端點，行為完全對等，只是省去做不到的合成拖曳事件那一步。
    另一方案（WBS 檢視 cycleTaskStatus 的狀態鈕）此刻仍在看板分頁，得先切分頁才點得到，
    繞頁比直接打 API 麻煩且無必要，故不採用。
    """
    token, header = _csrf(page)
    res = page.request.patch(
        "{}/api/projects/{}/tasks/{}/move".format(BASE_URL, project_id, task_id),
        data={"status": status, "sortOrder": sort_order},
        headers={header: token},
    )
    if not res.ok:
        raise RuntimeError("移動任務狀態失敗：{} {}".format(res.status, res.text()))


def _set_task_category(page, project_id, task, category_id):
    """對應 WbsView.moveTaskToCategory()：PUT 整份任務、只換 categoryId，其餘欄位原樣送回。"""
    token, header = _csrf(page)
    res = page.request.put(
        "{}/api/projects/{}/tasks/{}".format(BASE_URL, project_id, task["id"]),
        data={
            "title": task["title"], "description": task.get("description"),
            "categoryId": category_id, "priority": task.get("priority"),
            "startDate": task.get("startDate"), "dueDate": task.get("dueDate"),
        },
        headers={header: token},
    )
    if not res.ok:
        raise RuntimeError("改分類失敗：{} {}".format(res.status, res.text()))


def _set_task_assignee(page, project_id, task_id, assignee_id):
    token, header = _csrf(page)
    res = page.request.patch(
        "{}/api/projects/{}/tasks/{}/assignee".format(BASE_URL, project_id, task_id),
        data={"assigneeId": assignee_id},
        headers={header: token},
    )
    if not res.ok:
        raise RuntimeError("指派失敗：{} {}".format(res.status, res.text()))


def capture_all(page):
    """依 21 個分鏡逐場景擷取。所有選擇器皆已用 Playwright 對本機真實畫面實測校正
    （見 scripts/intro-video 開發過程的探索腳本，未留在版控內），不是憑文案推測。
    """
    scenes = {s.id: s for s in sb.SCENES}

    capture_card(page, scenes["s00_title"])
    capture_card(page, scenes["s01_stack"])

    # --- leader 視角 ---
    page.goto(BASE_URL + "/login")
    measure_box(page, "s02_login", ".login-box form")
    shot(page, "s02_login")

    login(page, "leader")
    shot(page, "s03_home_leader")

    page.goto(BASE_URL + "/projects")
    page.get_by_text("新增專案").click()
    page.wait_for_timeout(300)
    page.locator(".modal .form-group", has_text="名稱").locator("input").fill("客服系統改版")
    shot(page, "s04_create_project")
    page.locator(".modal-actions .btn-primary").click()
    # 建立成功後前端直接 window.location.href 導去 /projects/{id}，不會停在專案列表頁，
    # 從導頁後的 URL 取真正的 project_id，後面所有 REST 校正呼叫都要用它
    page.wait_for_url(re.compile(r".*/projects/\d+$"))
    project_id = page.url.rstrip("/").split("/")[-1]
    page.wait_for_timeout(600)

    measure_box(page, "s05_board_empty", "#project-nav-slot")
    shot(page, "s05_board_empty")

    page.get_by_text("成員管理").click()
    page.wait_for_timeout(400)
    measure_box(page, "s06_members", ".member-add-row")
    shot(page, "s06_members")
    page.locator(".member-add-row select").select_option(label="專案成員")
    page.locator(".member-add-row button").click()
    page.wait_for_timeout(600)
    page.get_by_text("成員管理", exact=False).first.click()
    page.wait_for_timeout(300)

    # 第三個任務刻意不指派：s14「成員自我認領」需要「未指派」欄裡有東西可以拖，
    # 這是設計不是疏漏——改動這裡會讓 s14 沒有拖曳對象。
    _create_task(page, "整理客服工單流程需求", "專案成員", sb.DATES["due_a"])
    _create_task(page, "設計工單狀態流程圖", "專案負責人", sb.DATES["due_b"])
    _create_task(page, "建置後台工單列表頁", None, sb.DATES["due_c"])
    page.get_by_text("新增任務").click()
    page.wait_for_timeout(300)
    shot(page, "s07_new_task")
    # TaskModal 沒有掛 Escape 鍵關閉（只有 WBS 快速新增列的輸入框有 @keyup.esc），
    # 按 Escape 不會有任何反應，modal 會一路開著蓋住後面 s08 的拖曳畫面；改點「取消」關閉
    page.locator(".modal-actions").get_by_text("取消").click()
    page.wait_for_timeout(300)

    # 看板拖曳：先截「拖曳中」底圖，再用 REST 直接把卡片狀態改成「進行中」
    # （見 _set_task_status 說明：原生 HTML5 拖曳無法用合成事件觸發，這是能穩定跑通的方案）
    capture_drag(page, "s08_clean", "s08_base",
                 ".task-card", ".kanban-col:nth-child(2)")
    tasks = _get_tasks(page, project_id)
    first_task = _task_by_title(tasks, "整理客服工單流程需求")
    _set_task_status(page, project_id, first_task["id"], "IN_PROGRESS", 0)
    page.reload()
    page.wait_for_timeout(500)
    shot(page, "s09_board_after")

    # --- WBS 檢視 ---
    page.get_by_text("WBS 檢視").click()
    page.wait_for_timeout(600)
    # 建大項＋子類別，s10 才有真的樹狀結構可看，也讓 s11 有實際的拖曳落點
    page.get_by_text("SIT").click()
    page.wait_for_timeout(500)
    page.locator(".wbs-tree > .wbs-node").nth(1).get_by_text("+ 新增子項").click()
    page.wait_for_timeout(300)
    page.locator(".wbs-quick-add-row .wbs-quick-add-input").fill("前端開發")
    page.locator(".wbs-quick-add-row .btn-primary").click()
    page.wait_for_timeout(500)
    shot(page, "s10_wbs")

    capture_drag(page, "s11_clean", "s11_base",
                 ".wbs-task-row", ".wbs-node-child")
    tasks = _get_tasks(page, project_id)
    categories = page.request.get(
        "{}/api/projects/{}/task-categories".format(BASE_URL, project_id)).json()["data"]
    sub_category = next(c for c in categories if c["parentCategoryId"] is not None)
    _set_task_category(page, project_id, first_task, sub_category["id"])
    # 整頁 reload 會讓根元件 activeTab 重置回預設的「看板」，WBS 檢視要重新點回去
    page.reload()
    page.wait_for_timeout(600)
    page.get_by_text("WBS 檢視").click()
    page.wait_for_timeout(500)
    shot(page, "s12_wbs_after")

    measure_box(page, "s13_export", ".wbs-toolbar button")
    shot(page, "s13_export")

    # --- member 視角：自我認領 ---
    logout(page)
    login(page, "member")
    page.goto(BASE_URL + "/projects")
    page.get_by_text("客服系統改版", exact=True).first.click()
    page.wait_for_timeout(600)
    page.get_by_text("人員派工").click()
    page.wait_for_timeout(600)
    capture_drag(page, "s14_clean", "s14_base",
                 ".task-card", ".assignment-col:nth-child(3)")
    tasks = _get_tasks(page, project_id)
    unassigned_task = _task_by_title(tasks, "建置後台工單列表頁")
    member_id = next(t["assigneeId"] for t in _get_tasks(page, project_id)
                      if t["title"] == "整理客服工單流程需求")
    _set_task_assignee(page, project_id, unassigned_task["id"], member_id)
    # 整頁 reload 會讓根元件 activeTab 重置回預設的「看板」，人員派工要重新點回去
    page.reload()
    page.wait_for_timeout(500)
    page.get_by_text("人員派工").click()
    page.wait_for_timeout(500)
    shot(page, "s15_member_after")

    # --- chief 視角 ---
    logout(page)
    login(page, "chief")
    shot(page, "s16_chief_home")
    page.goto(BASE_URL + "/projects")
    page.get_by_text("客服系統改版", exact=True).first.click()
    page.wait_for_timeout(600)
    page.get_by_text("封存").click()
    page.wait_for_timeout(500)
    measure_box(page, "s17_archive", ".project-toolbar")
    shot(page, "s17_archive")
    # 立刻解封存：s19 還要靠 leader 示範拖曳，封存後 canWrite 對所有角色恆為 false 會卡住後續，
    # 這裡只是「借」畫面示範封存後的唯讀外觀，不是真的要讓專案停在封存狀態
    page.get_by_text("解封存").click()
    page.wait_for_timeout(500)

    # --- director 視角 ---
    logout(page)
    login(page, "director")
    shot(page, "s18_director")

    # --- 加映：完整拖曳 ---
    logout(page)
    login(page, "leader")
    page.goto(BASE_URL + "/projects")
    page.get_by_text("客服系統改版", exact=True).first.click()
    page.wait_for_timeout(600)
    capture_drag(page, "s19_clean", "s19_base",
                 ".task-card", ".kanban-col:nth-child(3)")

    capture_card(page, scenes["s20_end"])


def main():
    os.makedirs(RAW, exist_ok=True)
    # 埠檢查一定要排在 reset_db() 之前：這次執行若 8080 已被占用，
    # 不管資料庫有沒有先重置都注定失敗，先檢查才能在犯下「砍掉重建 DB」
    # 這個不可逆動作之前就讓它失敗，不要讓使用者白白付出一次破壞性操作。
    _ensure_port_free()
    reset_db()
    # app 起手設 None：start_app() 逾時拋例外時不會走到指派，finally 才不會
    # 誤呼叫 stop_app(None)——而 start_app 本身逾時分支已自行收尾過一次。
    app = None
    log = None
    try:
        app, log = start_app()
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page(
                viewport={"width": sb.WIDTH, "height": sb.HEIGHT},
                device_scale_factor=1)
            capture_all(page)
            browser.close()
        with open(os.path.join(RAW, "meta.json"), "w") as handle:
            json.dump(META, handle, ensure_ascii=False, indent=2)
    finally:
        if app is not None:
            stop_app(app, log)
    print("[capture] 完成，輸出於 " + RAW)


if __name__ == "__main__":
    main()
