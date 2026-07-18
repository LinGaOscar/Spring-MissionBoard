# Spring-WbsFlow 子專案 B：認證與登入 設計文件

日期：2026-07-18
狀態：已與使用者確認核准
上位文件：`docs/superpowers/specs/2026-07-17-wbsflow-design.md`（技術棧見第 2 節、權限模型見第 4 節）

## 目的

本文件補充主設計文件未涵蓋、因「子專案分批實作」順序而產生的細節決策。技術選型（Spring Security 6 表單登入＋Spring Session JDBC、四角色）已在主文件與子專案 A（`ProjectService.canRead/canWrite`，已完成）中定案，不重複列出。

## 架構

- `com.wbsflow.auth`
  - `SecurityConfig`：表單登入、`spring.session.store-type: jdbc`（沿用 pom.xml 既有 `spring-session-jdbc`）、`BCryptPasswordEncoder`
  - `CustomUserDetailsService`：依 `User.username` 查詢，角色轉換為 `ROLE_<role>` 權限
  - `AuthController`：`GET /login`（回傳登入頁 view）、`GET /home`（占位首頁 view）
- `com.wbsflow.user.UserController`：新增 `GET /api/users/me`（回傳目前登入者 id/username/displayName/role，包 `ApiResponse`）
- 前端頁殼（Thymeleaf）：
  - `templates/auth/login.html`（移植自 Spring-WbsScaff，欄位改用 `username`）
  - `templates/home.html`（新增，占位首頁）
  - `templates/fragments/{header,footer,sidebar}.html`（移植，簡化導覽內容）
  - `static/css/app.css`（整份移植自 Spring-WbsScaff，純樣式無邏輯，後續子專案頁面共用）

## 與 Spring-WbsScaff 原模式的差異（因子專案排序而生，非需求變動）

1. **登入欄位**：用 `username`（對應本專案 schema），非 WbsScaff 的 `email`。
2. **`defaultSuccessUrl("/home", true)`**：子專案 C（專案管理）尚未建立專案列表頁，故登入成功先導向占位首頁 `/home`（顯示「歡迎 {displayName}」＋登出按鈕）。子專案 C 完成後另開任務把此處改為 `/projects`。
3. **側邊欄只有「首頁」一項連結**：不預先建立指向未實作頁面的假連結；後續子專案建好對應頁面時各自在 `sidebar.html` 加一行 `<li>`。
4. **不建 `WebSocketSecurityConfig`**：主設計文件第 2 節已明定 v1 純 REST、不做 WebSocket。
5. **不手動暴露 `AuthenticationManager` bean**：本專案沒有自訂 POST 登入邏輯，表單登入完全交給 Spring Security 內建 filter chain 處理（WbsScaff 是為了讓自訂 `AuthController` 呼叫才暴露此 bean，本專案不需要）。
6. **`GET /api/users`（使用者列表）不在本子專案範圍**：留到之後真正需要列表的子專案（例如成員挑選器）再實作，避免建立目前無人呼叫的端點。

## 測試策略

- `SecurityConfig` + `CustomUserDetailsService`：`@SpringBootTest` + `MockMvc` + `spring-security-test` 的 `SecurityMockMvcRequestBuilders.formLogin()`：
  - 正確帳密登入 → 302 導向 `/home`
  - 密碼錯誤 → 302 導向 `/login?error`
  - 未登入存取受保護頁面（如 `/home`）→ 302 導向 `/login`
- `GET /api/users/me`：以已登入 session 呼叫，驗證回傳的 `ApiResponse` 內容（id/username/displayName/role）與登入帳號一致；未登入呼叫應回 302（走表單登入導頁，非 401，因為是頁面式應用非純 API）。

## 範圍外（明確不做）

- 密碼重設、忘記密碼流程（設計文件未提及，YAGNI）
- 多次登入失敗鎖定帳號（設計文件未提及）
- 記住我（remember-me）功能
