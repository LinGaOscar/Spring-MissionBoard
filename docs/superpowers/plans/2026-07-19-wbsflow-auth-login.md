# 子專案 B：認證與登入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 Spring-WbsFlow 的表單登入流程——`CustomUserDetailsService`、`SecurityConfig`、登入頁與頁殼、占位首頁、`GET /api/users/me`——讓四角色測試帳號可以實際登入、看到頁面、登出。

**Architecture:** `com.wbsflow.auth` package 放認證專屬類別（`SecurityConfig`、`CustomUserDetailsService`、`AuthController`），`com.wbsflow.user.UserController` 放使用者自身資訊查詢。前端頁殼用 Thymeleaf fragment（`header`/`footer`/`sidebar`）+ 移植的 `app.css`，無 Vue（登入/首頁是純伺服器渲染頁，Vue 留給子專案 D 起的四檢視互動頁面）。

**Tech Stack:** Spring Security 6 表單登入、Spring Session JDBC（`pom.xml`/`application.yml` 已具備，本計畫不需再加 dependency 或設定）、BCrypt、Thymeleaf + `thymeleaf-extras-springsecurity6`、MockMvc + `spring-security-test`。

## Global Constraints

- 沿用既有 package 慣例：認證邏輯在 `com.wbsflow.auth`，使用者自身資訊查詢在既有的 `com.wbsflow.user`
- 登入欄位為 `username`（非 email），對應 `User.username`
- `SecurityConfig` 的 `defaultSuccessUrl("/home", true)`——`/projects` 尚未建立（留給子專案 C）
- 側邊欄 `sidebar.html` 現階段只含「首頁」一個連結
- **不建** `WebSocketSecurityConfig`（v1 純 REST）；**不暴露** `AuthenticationManager` bean（無自訂 POST 登入邏輯，交給 Spring Security 內建 filter chain）
- `PasswordEncoder` 只有一個 bean：`BCryptPasswordEncoder`
- 所有 Controller 測試使用 `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional`（H2，`spring.session.store-type: none`，已在 `application-test.yml` 設定好）
- 表單登入測試一律用 `spring-security-test` 的 `SecurityMockMvcRequestBuilders.formLogin(...)`/`logout(...)`，不得手動組 HTTP request 繞過標準登入流程
- `mvn test` 必須全數通過才能進入下一個 Task 的 commit

---

### Task 1: CustomUserDetailsService

**Files:**
- Create: `src/main/java/com/wbsflow/auth/CustomUserDetailsService.java`
- Test: `src/test/java/com/wbsflow/auth/CustomUserDetailsServiceTest.java`

**Interfaces:**
- Consumes: `com.wbsflow.user.User`、`com.wbsflow.user.UserRepository`（已存在，子專案 A）
- Produces: `CustomUserDetailsService implements UserDetailsService`——`loadUserByUsername(String): UserDetails`，回傳的 `UserDetails` 帶單一 `ROLE_<role>` 權限、`enabled=true`（本專案 schema 無帳號停用欄位，故恆為 `true`）

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.auth;

import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void loadsUserWithRolePrefixedAuthority() {
        User user = new User();
        user.setUsername("chief");
        user.setPassword("$2b$10$hash");
        user.setDisplayName("科長");
        user.setRole(User.Role.SECTION_CHIEF);
        when(userRepository.findByUsername("chief")).thenReturn(Optional.of(user));

        UserDetails details = customUserDetailsService.loadUserByUsername("chief");

        assertThat(details.getUsername()).isEqualTo("chief");
        assertThat(details.getPassword()).isEqualTo("$2b$10$hash");
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_SECTION_CHIEF");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void throwsWhenUserNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("ghost"))
            .isInstanceOf(UsernameNotFoundException.class);
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=CustomUserDetailsServiceTest`
Expected: FAIL（編譯錯誤，`CustomUserDetailsService` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.auth;

import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    // Spring Security 以 username 作為登入帳號，登入與 session 還原都會經過此方法
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("使用者不存在: " + username));
        return new org.springframework.security.core.userdetails.User(
            user.getUsername(),
            user.getPassword(),
            true, true, true, true,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=CustomUserDetailsServiceTest`
Expected: PASS（2 個測試）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/auth/CustomUserDetailsService.java src/test/java/com/wbsflow/auth/CustomUserDetailsServiceTest.java
git commit -m "feat: 新增 CustomUserDetailsService"
```

---

### Task 2: SecurityConfig + 登入頁 + 占位首頁

**Files:**
- Create: `src/main/java/com/wbsflow/auth/SecurityConfig.java`
- Create: `src/main/java/com/wbsflow/auth/AuthController.java`
- Create: `src/main/resources/templates/auth/login.html`
- Create: `src/main/resources/templates/home.html`
- Create: `src/main/resources/templates/fragments/header.html`
- Create: `src/main/resources/templates/fragments/footer.html`
- Create: `src/main/resources/templates/fragments/sidebar.html`
- Create: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/wbsflow/auth/AuthFlowTest.java`

**Interfaces:**
- Consumes: `CustomUserDetailsService`（Task 1，透過 Spring 自動偵測為 `UserDetailsService` bean，`SecurityConfig` 不需顯式注入）、`com.wbsflow.user.UserRepository`、`com.wbsflow.department.Department`/`DepartmentRepository`（子專案 A）
- Produces: `SecurityConfig` 的 `SecurityFilterChain`/`PasswordEncoder` bean；`AuthController.home(Principal, Model): String`——之後任何頁面若要顯示登入者姓名，比照此處 `model.addAttribute("displayName", ...)` 的做法

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.auth;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Department section = new Department();
        section.setName("系統科");
        section = departmentRepository.save(section);

        User user = new User();
        user.setUsername("chief");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setDisplayName("科長");
        user.setRole(User.Role.SECTION_CHIEF);
        user.setDepartment(section);
        userRepository.save(user);
    }

    @Test
    void correctCredentialsLoginRedirectsToHome() throws Exception {
        mockMvc.perform(formLogin("/auth/login").user("chief").password("password123"))
            .andExpect(authenticated())
            .andExpect(redirectedUrl("/home"));
    }

    @Test
    void wrongPasswordRedirectsToLoginError() throws Exception {
        mockMvc.perform(formLogin("/auth/login").user("chief").password("wrong"))
            .andExpect(unauthenticated())
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void unauthenticatedAccessToHomeRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/home"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void logoutInvalidatesSessionAndRedirectsToLoginLogout() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin("/auth/login").user("chief").password("password123"))
            .andExpect(authenticated())
            .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(logout("/auth/logout").session(session))
            .andExpect(unauthenticated())
            .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void homePageShowsDisplayNameAfterLogin() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin("/auth/login").user("chief").password("password123"))
            .andExpect(authenticated())
            .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/home").session(session))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attribute("displayName", "科長"));
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=AuthFlowTest`
Expected: FAIL（`SecurityConfig`/`AuthController` 不存在，或找不到 `auth/login`、`home` 樣板）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/auth/login")
                .defaultSuccessUrl("/home", true)
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/auth/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("SESSION")
            );
        return http.build();
    }

    // BCrypt 預設 strength=10，與 sql/02_test_data.sql 種子帳號的 $2b$ 雜湊格式相容
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

```java
package com.wbsflow.auth;

import com.wbsflow.user.User;
import com.wbsflow.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    // 占位首頁：子專案 C 建好專案列表後，SecurityConfig 的 defaultSuccessUrl 會改指向 /projects
    @GetMapping("/home")
    public String home(Principal principal, Model model) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        model.addAttribute("displayName", user.getDisplayName());
        return "home";
    }
}
```

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-TW">
<head>
  <meta charset="UTF-8">
  <title>登入 - WBS 管理系統</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body class="login-page">
<div class="login-box">
  <h1>WBS 管理系統</h1>
  <div th:if="${param.error}" class="alert alert-error">帳號或密碼錯誤</div>
  <div th:if="${param.logout}" class="alert alert-info">已成功登出</div>
  <form th:action="@{/auth/login}" method="post">
    <div class="form-group">
      <label for="username">帳號</label>
      <input type="text" id="username" name="username" required autofocus>
    </div>
    <div class="form-group">
      <label for="password">密碼</label>
      <input type="password" id="password" name="password" required>
    </div>
    <button type="submit" class="btn btn-primary btn-block">登入</button>
  </form>
</div>
</body>
</html>
```

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <title>首頁 - WBS 管理系統</title>
  <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
<div th:replace="~{fragments/header :: header}"></div>
<div class="layout">
  <div th:replace="~{fragments/sidebar :: sidebar}"></div>
  <main class="main-content">
    <h1 th:text="'歡迎，' + ${displayName}"></h1>
  </main>
</div>
<div th:replace="~{fragments/footer :: footer}"></div>
</body>
</html>
```

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<body>
<header th:fragment="header">
  <nav class="navbar">
    <div class="navbar-brand">
      <a th:href="@{/home}">WBS 管理系統</a>
    </div>
    <div class="navbar-user" sec:authorize="isAuthenticated()">
      <span sec:authentication="principal.username"></span>
      <form th:action="@{/auth/logout}" method="post" style="display:inline">
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
        <button type="submit" class="btn-link">登出</button>
      </form>
    </div>
  </nav>
</header>
</body>
</html>
```

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<nav th:fragment="sidebar" class="sidebar">
  <ul>
    <li><a th:href="@{/home}">首頁</a></li>
  </ul>
</nav>
</body>
</html>
```

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<footer th:fragment="footer" class="footer">
  <p>WBS 管理系統 &copy; 2026</p>
  <script>
    (function() {
      const path = window.location.pathname;
      document.querySelectorAll('.sidebar a').forEach(function(a) {
        const href = a.getAttribute('href');
        if (href && (path === href || path.startsWith(href + '/'))) {
          a.classList.add('active');
        }
      });
    })();
  </script>
</footer>
</body>
</html>
```

```css
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f5f6fa; color: #2d3436; }
.navbar { display: flex; justify-content: space-between; align-items: center; padding: 0 1.5rem; height: 56px; background: #2d3436; color: #fff; }
.navbar a { color: #fff; text-decoration: none; font-weight: 600; font-size: 1.1rem; }
.navbar-user { display: flex; align-items: center; gap: 1rem; font-size: 0.9rem; }
.btn-link { background: none; border: none; color: #fff; cursor: pointer; font-size: 0.9rem; text-decoration: underline; }
.layout { display: flex; min-height: calc(100vh - 56px - 48px); }
.sidebar { width: 220px; background: #fff; border-right: 1px solid #dfe6e9; padding: 1rem 0; flex-shrink: 0; }
.sidebar ul { list-style: none; }
.sidebar ul li a { display: block; padding: 0.65rem 1.5rem; color: #2d3436; text-decoration: none; font-size: 0.9rem; }
.sidebar ul li a:hover { background: #f5f6fa; color: #0984e3; }
.sidebar-group-label { padding: 0.6rem 1.5rem 0.3rem; font-size: 0.72rem; font-weight: 700; color: #b2bec3; text-transform: uppercase; letter-spacing: 0.06em; margin-top: 0.5rem; }
.main-content { flex: 1; padding: 2rem; }
.footer { height: 48px; background: #dfe6e9; display: flex; align-items: center; justify-content: center; font-size: 0.85rem; color: #636e72; }
.login-page { display: flex; justify-content: center; align-items: center; min-height: 100vh; }
.login-box { background: #fff; padding: 2.5rem; border-radius: 8px; box-shadow: 0 4px 20px rgba(0,0,0,0.1); width: 380px; }
.login-box h1 { text-align: center; margin-bottom: 1.5rem; font-size: 1.4rem; }
.form-group { margin-bottom: 1rem; }
.form-group label { display: block; margin-bottom: 0.4rem; font-size: 0.9rem; font-weight: 500; }
.form-group input, .form-group select { width: 100%; padding: 0.6rem 0.8rem; border: 1px solid #b2bec3; border-radius: 4px; font-size: 0.95rem; }
.form-group input:focus, .form-group select:focus { outline: none; border-color: #0984e3; }
.btn { padding: 0.6rem 1.2rem; border: 1px solid #b2bec3; border-radius: 4px; cursor: pointer; font-size: 0.95rem; background: #fff; }
.btn-primary { background: #0984e3; color: #fff; border-color: #0984e3; }
.btn-primary:hover { background: #0773c5; }
.btn-block { width: 100%; margin-top: 0.5rem; }
.sidebar ul li a.active { background: #e8f4fd; color: #0984e3; font-weight: 600; border-left: 3px solid #0984e3; padding-left: calc(1.5rem - 3px); }
.btn-sm { padding: 0.3rem 0.6rem; font-size: 0.8rem; margin-right: 0.25rem; }
.btn-danger { border-color: #e17055; color: #e17055; }
.btn-danger:hover { background: #e17055; color: #fff; }
.alert { padding: 0.75rem 1rem; border-radius: 4px; margin-bottom: 1rem; font-size: 0.9rem; }
.alert-error { background: #ffe0e0; color: #c0392b; }
.alert-info { background: #e0f0ff; color: #2980b9; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.5rem; }
.data-table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 6px; overflow: hidden; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
.data-table th, .data-table td { padding: 0.75rem 1rem; text-align: left; border-bottom: 1px solid #f0f0f0; font-size: 0.9rem; }
.data-table th { background: #f8f9fa; font-weight: 600; color: #636e72; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 8px; padding: 2rem; width: 440px; box-shadow: 0 8px 32px rgba(0,0,0,0.15); }
.modal h3 { margin-bottom: 1.5rem; font-size: 1.1rem; }
.modal-actions { display: flex; gap: 0.75rem; margin-top: 1.5rem; }
.project-grid { display: grid; grid-template-columns: repeat(auto-fill,minmax(260px,1fr)); gap: 1rem; }
.project-card { background:#fff; border-radius:8px; padding:1.25rem 1.5rem; cursor:pointer; box-shadow:0 1px 4px rgba(0,0,0,0.08); border:1px solid #f0f0f0; transition:box-shadow 0.15s; }
.project-card:hover { box-shadow:0 4px 16px rgba(0,0,0,0.12); }
.project-card-name { font-size:1.05rem; font-weight:600; margin-bottom:0.5rem; }
.project-card-meta { font-size:0.85rem; color:#636e72; }
.presence-bar { display:flex; gap:6px; align-items:center; }
.presence-avatar { width:32px; height:32px; border-radius:50%; display:flex; align-items:center; justify-content:center; color:#fff; font-weight:700; font-size:0.85rem; cursor:default; }
.cursor-indicators { display:flex; gap:3px; margin-left:4px; }
.cursor-dot { width:10px; height:10px; border-radius:50%; display:inline-block; border:2px solid #fff; box-shadow:0 0 0 1px rgba(0,0,0,0.2); }
/* 匯出下拉選單（純 CSS hover） */
.export-dropdown { position:relative; display:inline-block; }
.export-dropdown .dropdown-menu { display:none; position:absolute; right:0; top:calc(100% + 2px); background:#fff; border:1px solid #dfe6e9; border-radius:4px; box-shadow:0 4px 12px rgba(0,0,0,0.12); z-index:200; min-width:100px; }
.export-dropdown:hover .dropdown-menu { display:block; }
.dropdown-item { display:block; width:100%; padding:0.45rem 1rem; text-align:left; background:none; border:none; cursor:pointer; font-size:0.85rem; color:#2d3436; }
.dropdown-item:hover { background:#f5f6fa; }
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=AuthFlowTest`
Expected: PASS（5 個測試）；同時跑 `mvn test` 全套確認未影響子專案 A 的 24 個既有測試

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/auth/SecurityConfig.java src/main/java/com/wbsflow/auth/AuthController.java src/main/resources/templates src/main/resources/static/css/app.css src/test/java/com/wbsflow/auth/AuthFlowTest.java
git commit -m "feat: 新增表單登入 SecurityConfig、登入頁與占位首頁"
```

---

### Task 3: UserController — GET /api/users/me

**Files:**
- Create: `src/main/java/com/wbsflow/user/UserController.java`
- Test: `src/test/java/com/wbsflow/user/UserControllerTest.java`

**Interfaces:**
- Consumes: `com.wbsflow.common.ApiResponse`（子專案 A）、`UserRepository`（子專案 A）、Task 2 的表單登入（測試需先登入取得 session）
- Produces: `GET /api/users/me` → `ApiResponse<UserController.UserMeResponse>`，`UserMeResponse` 為 `record(Long id, String username, String displayName, String role)`

- [ ] **Step 1: 寫失敗測試**

```java
package com.wbsflow.user;

import com.wbsflow.department.Department;
import com.wbsflow.department.DepartmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Department section = new Department();
        section.setName("系統科");
        section = departmentRepository.save(section);

        User user = new User();
        user.setUsername("leader");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setDisplayName("負責人");
        user.setRole(User.Role.PROJECT_LEADER);
        user.setDepartment(section);
        userRepository.save(user);
    }

    @Test
    void returnsAuthenticatedUserProfile() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin("/auth/login").user("leader").password("password123"))
            .andExpect(authenticated())
            .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/api/users/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.username").value("leader"))
            .andExpect(jsonPath("$.data.displayName").value("負責人"))
            .andExpect(jsonPath("$.data.role").value("PROJECT_LEADER"));
    }

    @Test
    void unauthenticatedRequestRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/api/users/me"))
            .andExpect(status().is3xxRedirection());
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `mvn test -Dtest=UserControllerTest`
Expected: FAIL（`UserController` 不存在）

- [ ] **Step 3: 寫最小實作**

```java
package com.wbsflow.user;

import com.wbsflow.common.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/api/users/me")
    public ApiResponse<UserMeResponse> me(Principal principal) {
        User user = userRepository.findByUsername(principal.getName())
            .orElseThrow(() -> new EntityNotFoundException("使用者不存在"));
        return ApiResponse.ok(new UserMeResponse(
            user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name()
        ));
    }

    public record UserMeResponse(Long id, String username, String displayName, String role) {
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `mvn test -Dtest=UserControllerTest`
Expected: PASS（2 個測試）；同時跑 `mvn test` 全套確認全部測試（子專案 A 24 個 + 子專案 B 至此累積的測試）皆綠燈

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wbsflow/user/UserController.java src/test/java/com/wbsflow/user/UserControllerTest.java
git commit -m "feat: 新增 GET /api/users/me"
```

---

## 全部完成後

- [ ] 執行 `mvn test` 確認全專案測試皆綠燈
- [ ] 實際啟動應用程式（`docker compose up -d` + `mvn spring-boot:run`），用瀏覽器以 `sql/02_test_data.sql` 的測試帳號（如 `chief`/`password123`）登入，確認導向 `/home` 並顯示正確姓名，登出後導回 `/login?logout`
- [ ] 回報使用者：本子專案完成，等待核准後進入子專案 C（專案管理）
