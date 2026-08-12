# Identity and Data Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить локальные учётные записи, роли, серверные сессии, CSRF-защиту и изоляцию существующих пользовательских данных без потери текущих записей.

**Architecture:** Spring Security обслуживает session-based authentication и CSRF, а JDBC-репозитории связывают chat/practice данные с `app_user`. Flyway заменяет неверсированную инициализацию `schema.sql`; старые диалоги мигрируют seeded admin, общий сгенерированный контент — системному пользователю без права входа. Frontend получает только текущего пользователя и CSRF token, а все mutation-запросы проходят через единый API helper.

**Tech Stack:** Java 21, Spring Boot 3.5.4, Spring Security 6, Spring JDBC, Flyway, H2, JUnit 5, MockMvc, vanilla JavaScript.

---

## Scope boundary and file map

Этот этап оставляет существующие trainer/practice алгоритмы без изменений. Его результат — защищённое, многопользовательское приложение, на которое следующие планы смогут безопасно перенести curriculum и прогресс.

**Create:**

- `backend/src/main/resources/db/migration/V1__baseline.sql` — текущая схема для новой базы.
- `backend/src/main/resources/db/migration/V2__identity_and_ownership.sql` — users, roles и owner foreign keys.
- `backend/src/main/java/ru/questionhacker/trainer/auth/AppUser.java` — безопасная проекция пользователя без password hash.
- `backend/src/main/java/ru/questionhacker/trainer/auth/UserAccountRepository.java` — JDBC-доступ к accounts и roles.
- `backend/src/main/java/ru/questionhacker/trainer/auth/DatabaseUserDetailsService.java` — адаптер Spring Security.
- `backend/src/main/java/ru/questionhacker/trainer/auth/SecurityConfig.java` — session, CSRF и правила URL.
- `backend/src/main/java/ru/questionhacker/trainer/auth/AuthService.java` — регистрация и текущий principal.
- `backend/src/main/java/ru/questionhacker/trainer/auth/AuthController.java` — `/api/auth/*`.
- `backend/src/main/java/ru/questionhacker/trainer/auth/AdminSeeder.java` — идемпотентный first-admin seed.
- `backend/src/test/java/ru/questionhacker/trainer/auth/AuthControllerTest.java` — auth/CSRF/role tests.
- `backend/src/test/java/ru/questionhacker/trainer/ChatOwnershipTest.java` — межпользовательская изоляция.

**Modify:**

- `backend/pom.xml` — Spring Security и Flyway.
- `backend/src/main/resources/application.yml` — Flyway, session cookie, admin properties.
- `backend/src/main/java/ru/questionhacker/trainer/DatabaseStore.java` — owner-aware chat queries.
- `backend/src/main/java/ru/questionhacker/trainer/ChatService.java` — user id во всех операциях.
- `backend/src/main/java/ru/questionhacker/trainer/ApiController.java` — principal-bound API и удаление wildcard CORS.
- `backend/src/main/java/ru/questionhacker/trainer/ApiExceptionHandler.java` — единый 401/403/problem response.
- `backend/src/test/java/ru/questionhacker/trainer/ContextTest.java` — authenticated test setup.
- `frontend/index.html` — минимальный login/register shell и user menu.
- `frontend/app.js` — auth bootstrap, CSRF-aware fetch и session-expired state.
- `frontend/styles.css` — auth form and errors.
- `.env.example`, `docker-compose.yml`, `README.md` — first-admin configuration.

## Task 1: Replace ad-hoc schema initialization with Flyway

**Files:**

- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V1__baseline.sql`
- Delete after migration is verified: `backend/src/main/resources/schema.sql`
- Test: `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java`

- [ ] **Step 1: Write a migration smoke test**

```java
@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:migration;DB_CLOSE_DELAY=-1"
})
class MigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatesBaselineTables() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN ('CHAT_SESSION','CHAT_MESSAGE','GENERATED_SCENARIO','FLYWAY_SCHEMA_HISTORY')",
                Integer.class);
        assertThat(count).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Run the focused test and confirm the missing Flyway migration failure**

Run: `cd backend && mvn -Dtest=MigrationTest test`

Expected: FAIL because migration resources and Flyway are not configured.

- [ ] **Step 3: Add dependencies and Flyway configuration**

Add to `backend/pom.xml`:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
```

Replace `spring.sql.init` in `application.yml` with:

```yaml
  sql:
    init:
      mode: never
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 1
    locations: classpath:db/migration
```

Copy the three existing table definitions and index from `schema.sql` into `V1__baseline.sql` without semantic changes.

- [ ] **Step 4: Run the migration test and the existing suite**

Run: `cd backend && mvn -Dtest=MigrationTest test`

Expected: PASS, three application tables plus `flyway_schema_history` exist.

Run: `cd backend && mvn test`

Expected: PASS with no duplicate schema initialization errors.

- [ ] **Step 5: Remove `schema.sql` and commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/main/resources/db/migration/V1__baseline.sql backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java
git rm backend/src/main/resources/schema.sql
git commit -m "build: introduce versioned database migrations"
```

## Task 2: Add account schema and repository

**Files:**

- Create: `backend/src/main/resources/db/migration/V2__identity_and_ownership.sql`
- Create: `backend/src/main/java/ru/questionhacker/trainer/auth/AppUser.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/auth/UserAccountRepository.java`
- Test: `backend/src/test/java/ru/questionhacker/trainer/auth/UserAccountRepositoryTest.java`

- [ ] **Step 1: Write repository tests for normalized login, roles and duplicate rejection**

```java
@JdbcTest
@Import(UserAccountRepository.class)
class UserAccountRepositoryTest {
    @Autowired UserAccountRepository users;

    @Test
    void findsUserByNormalizedLoginWithRoles() {
        AppUser created = users.create("Nick", "nick@example.test", "$2a$hash", Set.of("USER"), false);
        AppUser loaded = users.findByLogin(" NICK ").orElseThrow();
        assertThat(loaded.id()).isEqualTo(created.id());
        assertThat(loaded.roles()).containsExactly("USER");
    }

    @Test
    void rejectsDuplicateNormalizedUsername() {
        users.create("Nick", null, "$2a$one", Set.of("USER"), false);
        assertThatThrownBy(() -> users.create("nick", null, "$2a$two", Set.of("USER"), false))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
```

- [ ] **Step 2: Run the tests and confirm missing identity tables/types**

Run: `cd backend && mvn -Dtest=UserAccountRepositoryTest test`

Expected: FAIL because `AppUser`, repository and migration are absent.

- [ ] **Step 3: Create the identity migration**

`V2__identity_and_ownership.sql` must contain:

```sql
CREATE TABLE app_user (
  id UUID PRIMARY KEY,
  username VARCHAR(80) NOT NULL,
  normalized_username VARCHAR(80) NOT NULL UNIQUE,
  email VARCHAR(254),
  normalized_email VARCHAR(254) UNIQUE,
  password_hash VARCHAR(255),
  system_account BOOLEAN NOT NULL DEFAULT FALSE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_login_capability CHECK (system_account OR password_hash IS NOT NULL)
);

CREATE TABLE user_role (
  user_id UUID NOT NULL,
  role VARCHAR(24) NOT NULL,
  PRIMARY KEY (user_id, role),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT chk_user_role CHECK (role IN ('USER', 'ADMIN'))
);

INSERT INTO app_user(id, username, normalized_username, email, normalized_email,
                     password_hash, system_account, enabled, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', '__system__', '__system__',
        NULL, NULL, NULL, TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

The fixed UUID is reserved exclusively for migration ownership and common content. `DatabaseUserDetailsService` rejects `system_account=true`, so this row can never authenticate.

- [ ] **Step 4: Implement immutable safe projection and JDBC repository**

```java
public record AppUser(UUID id, String username, String email, Set<String> roles,
                      boolean systemAccount, boolean enabled) {
    public boolean admin() { return roles.contains("ADMIN"); }
}
```

Repository public contract:

```java
Optional<AccountRow> findAccountByLogin(String login);
Optional<AppUser> findPublicById(UUID id);
AppUser create(String username, String email, String passwordHash, Set<String> roles, boolean systemAccount);
void addRole(UUID userId, String role);
boolean usernameExists(String normalizedUsername);
boolean emailExists(String normalizedEmail);
```

`AccountRow` is package-private and contains password hash for authentication only. Normalize username with `strip().toLowerCase(Locale.ROOT)` and email with the same rule.

- [ ] **Step 5: Run repository tests**

Run: `cd backend && mvn -Dtest=UserAccountRepositoryTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V2__identity_and_ownership.sql backend/src/main/java/ru/questionhacker/trainer/auth/AppUser.java backend/src/main/java/ru/questionhacker/trainer/auth/UserAccountRepository.java backend/src/test/java/ru/questionhacker/trainer/auth/UserAccountRepositoryTest.java
git commit -m "feat: add local user accounts and roles"
```

## Task 3: Configure session security and CSRF

**Files:**

- Create: `backend/src/main/java/ru/questionhacker/trainer/auth/DatabaseUserDetailsService.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/auth/SecurityConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/ru/questionhacker/trainer/auth/SecurityConfigTest.java`

- [ ] **Step 1: Write security boundary tests**

```java
@SpringBootTest(properties = {"app.acp.enabled=false", "spring.datasource.url=jdbc:h2:mem:security;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class SecurityConfigTest {
    @Autowired MockMvc mvc;

    @Test void statusIsPublic() throws Exception {
        mvc.perform(get("/api/system/status")).andExpect(status().isOk());
    }

    @Test void applicationApiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/chat/sessions")).andExpect(status().isUnauthorized());
    }

    @Test void mutationWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                .content("{\"username\":\"user-one\",\"password\":\"long-password-123\"}"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run focused tests and confirm endpoints are currently open**

Run: `cd backend && mvn -Dtest=SecurityConfigTest test`

Expected: FAIL because `/api/chat/sessions` is not protected and CSRF is not enforced.

- [ ] **Step 3: Implement UserDetailsService and filter chain**

Use this authorization map:

```java
http
    .cors(AbstractHttpConfigurer::disable)
    .csrf(Customizer.withDefaults())
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/system/status", "/api/auth/csrf", "/api/auth/register", "/api/auth/login").permitAll()
        .requestMatchers("/api/admin/**").hasRole("ADMIN")
        .requestMatchers("/api/**").authenticated()
        .anyRequest().permitAll())
    .formLogin(AbstractHttpConfigurer::disable)
    .httpBasic(AbstractHttpConfigurer::disable)
    .logout(AbstractHttpConfigurer::disable)
    .exceptionHandling(errors -> errors
        .authenticationEntryPoint((request, response, error) -> response.sendError(401))
        .accessDeniedHandler((request, response, error) -> response.sendError(403)));
```

Expose a `BCryptPasswordEncoder(12)`, an `AuthenticationManager`, and use `SessionCreationPolicy.IF_REQUIRED`.

Add to `application.yml`:

```yaml
server:
  servlet:
    session:
      cookie:
        http-only: true
        same-site: lax
        secure: ${SESSION_COOKIE_SECURE:false}
      timeout: 30d
```

- [ ] **Step 4: Run security tests**

Run: `cd backend && mvn -Dtest=SecurityConfigTest test`

Expected: system status returns 200, protected endpoint 401, mutation without CSRF 403.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/auth/DatabaseUserDetailsService.java backend/src/main/java/ru/questionhacker/trainer/auth/SecurityConfig.java backend/src/main/resources/application.yml backend/src/test/java/ru/questionhacker/trainer/auth/SecurityConfigTest.java
git commit -m "feat: enforce session and csrf security"
```

## Task 4: Implement registration, login, logout and current-user API

**Files:**

- Create: `backend/src/main/java/ru/questionhacker/trainer/auth/AuthService.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/auth/AuthController.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ApiExceptionHandler.java`
- Test: `backend/src/test/java/ru/questionhacker/trainer/auth/AuthControllerTest.java`

- [ ] **Step 1: Write end-to-end auth tests**

Test these exact cases with MockMvc and `.with(csrf())`:

```java
registerReturnsSessionUser();
registerRejectsPasswordShorterThanTwelveCharacters();
registerRejectsDuplicateNormalizedUsername();
loginRotatesSessionIdAndReturnsUser();
loginRejectsWrongPasswordWithoutRevealingWhichFieldWasWrong();
logoutInvalidatesSession();
meReturns401WhenAnonymous();
```

Representative successful assertion:

```java
mvc.perform(post("/api/auth/register").with(csrf()).contentType(APPLICATION_JSON)
        .content("{\"username\":\"alice\",\"email\":\"alice@example.test\",\"password\":\"correct horse 123\"}"))
   .andExpect(status().isCreated())
   .andExpect(jsonPath("$.username").value("alice"))
   .andExpect(jsonPath("$.roles[0]").value("USER"));
```

- [ ] **Step 2: Run the test class and confirm missing controller/service failures**

Run: `cd backend && mvn -Dtest=AuthControllerTest test`

Expected: FAIL with missing auth routes.

- [ ] **Step 3: Implement request/response contract**

```java
record RegisterRequest(@NotBlank @Size(min=3,max=80) String username,
                       @Email @Size(max=254) String email,
                       @NotBlank @Size(min=12,max=200) String password) {}
record LoginRequest(@NotBlank @Size(max=254) String login,
                    @NotBlank @Size(max=200) String password) {}
record CsrfResponse(String headerName, String token) {}
```

Routes:

- `GET /api/auth/csrf` accepts injected `CsrfToken` and returns header/token.
- `POST /api/auth/register` creates `USER`, authenticates it and returns 201.
- `POST /api/auth/login` authenticates, changes session id, saves `SecurityContext` and returns 200.
- `POST /api/auth/logout` clears context, invalidates session and returns 204.
- `GET /api/auth/me` returns `AppUser` or 401.

Map duplicate login/email to RFC 9457-style `409` problem JSON and invalid credentials to the same generic `401` message.

- [ ] **Step 4: Run auth and security tests**

Run: `cd backend && mvn -Dtest=AuthControllerTest,SecurityConfigTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/auth/AuthService.java backend/src/main/java/ru/questionhacker/trainer/auth/AuthController.java backend/src/main/java/ru/questionhacker/trainer/ApiExceptionHandler.java backend/src/test/java/ru/questionhacker/trainer/auth/AuthControllerTest.java
git commit -m "feat: add local authentication api"
```

## Task 5: Seed the system account and first administrator

**Files:**

- Create: `backend/src/main/java/ru/questionhacker/trainer/auth/AdminSeeder.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/AppProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/ru/questionhacker/trainer/auth/AdminSeederTest.java`

- [ ] **Step 1: Write idempotency and safety tests**

```java
migrationProvidesSystemAccountWithoutPassword();
seederCreatesAdminFromConfiguredCredentials();
secondRunDoesNotCreateDuplicatesOrReplacePasswordHash();
seederFailsStartupWhenOnlyOneAdminCredentialIsConfigured();
```

- [ ] **Step 2: Run the test and confirm missing configuration/seeder failures**

Run: `cd backend && mvn -Dtest=AdminSeederTest test`

Expected: FAIL because `AdminSeeder` and `app.admin` properties are absent.

- [ ] **Step 3: Add typed configuration**

```yaml
app:
  admin:
    username: ${APP_ADMIN_USERNAME:}
    password: ${APP_ADMIN_PASSWORD:}
    email: ${APP_ADMIN_EMAIL:}
```

Extend `AppProperties` with `Admin(String username, String password, String email)`.

- [ ] **Step 4: Implement transactional startup seeding**

Verify the reserved `__system__` row created by `V2`; never attach roles or a password to it. If both admin username and password are blank, skip admin creation; if exactly one is blank, fail with a clear configuration error. If the normalized admin username already exists, do not change password or roles automatically.

- [ ] **Step 5: Run seeder tests and commit**

Run: `cd backend && mvn -Dtest=AdminSeederTest test`

Expected: PASS.

```bash
git add backend/src/main/java/ru/questionhacker/trainer/auth/AdminSeeder.java backend/src/main/java/ru/questionhacker/trainer/AppProperties.java backend/src/main/resources/application.yml backend/src/test/java/ru/questionhacker/trainer/auth/AdminSeederTest.java
git commit -m "feat: seed system and first admin accounts"
```

## Task 6: Migrate chat ownership and enforce it in repositories

**Files:**

- Create: `backend/src/main/resources/db/migration/V3__chat_ownership.sql`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/DatabaseStore.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ChatService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ApiController.java`
- Test: `backend/src/test/java/ru/questionhacker/trainer/ChatOwnershipTest.java`

- [ ] **Step 1: Write two-user isolation tests**

```java
userListsOnlyOwnSessions();
userCannotReadAnotherUsersMessages();
userCannotPostToAnotherUsersSession();
userCannotDeleteAnotherUsersSession();
createdSessionBelongsToAuthenticatedUser();
```

Assert foreign ids return 404, not 403, so the API does not reveal their existence.

- [ ] **Step 2: Run the focused tests and confirm ownership is not enforced**

Run: `cd backend && mvn -Dtest=ChatOwnershipTest test`

Expected: FAIL because all sessions are globally visible.

- [ ] **Step 3: Add and backfill owner ids**

`V3__chat_ownership.sql` adds `owner_id`, assigns every legacy row to the fixed system UUID, makes the column `NOT NULL`, and adds the foreign key/index in the same migration. When `AdminSeeder` creates the first configured admin, it atomically reassigns only those legacy chat sessions still owned by `__system__` to that admin. New application writes always provide the authenticated owner directly.

Required migration statements:

```sql
ALTER TABLE chat_session ADD COLUMN owner_id UUID;
UPDATE chat_session SET owner_id='00000000-0000-0000-0000-000000000001' WHERE owner_id IS NULL;
ALTER TABLE chat_session ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE chat_session ADD CONSTRAINT fk_chat_session_owner
  FOREIGN KEY (owner_id) REFERENCES app_user(id);
```

Required index:

```sql
CREATE INDEX idx_chat_session_owner_updated ON chat_session(owner_id, updated_at DESC);
```

- [ ] **Step 4: Make store and service contracts owner-aware**

```java
SessionRow createSession(UUID ownerId, String title);
Optional<SessionRow> findSession(UUID ownerId, UUID sessionId);
List<SessionRow> listSessions(UUID ownerId);
boolean deleteSession(UUID ownerId, UUID sessionId);
List<MessageRow> listMessages(UUID ownerId, UUID sessionId);
```

Every SQL predicate contains both `owner_id=?` and the resource id. `ApiController` resolves the immutable user id through `AuthService.requireCurrentUser()`; it never accepts owner id from request data.

- [ ] **Step 5: Run ownership and regression tests**

Run: `cd backend && mvn -Dtest=ChatOwnershipTest,ContextTest test`

Expected: PASS; cascade deletion still removes messages.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__chat_ownership.sql backend/src/main/java/ru/questionhacker/trainer/auth/AdminSeeder.java backend/src/main/java/ru/questionhacker/trainer/DatabaseStore.java backend/src/main/java/ru/questionhacker/trainer/ChatService.java backend/src/main/java/ru/questionhacker/trainer/ApiController.java backend/src/test/java/ru/questionhacker/trainer/ChatOwnershipTest.java backend/src/test/java/ru/questionhacker/trainer/ContextTest.java
git commit -m "feat: isolate chat data by owner"
```

## Task 7: Add the thin authentication UI and centralized API client

**Files:**

- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Create: `frontend/auth.js`
- Create: `frontend/api.js`

- [ ] **Step 1: Extract the API helper without changing behavior**

`frontend/api.js` owns base URL, JSON parsing, credentials and CSRF:

```javascript
window.QH_API = (() => {
  let csrf;

  async function csrfToken() {
    if (!csrf) csrf = await raw('/api/auth/csrf', { method: 'GET' });
    return csrf;
  }

  async function raw(path, options = {}) {
    const response = await fetch(path, { credentials: 'same-origin', ...options });
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) throw Object.assign(new Error(body?.detail || `HTTP ${response.status}`), { status: response.status, body });
    return body;
  }

  async function request(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers || {});
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const token = await csrfToken();
      headers.set(token.headerName, token.token);
    }
    if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    try { return await raw(path, { ...options, method, headers }); }
    catch (error) {
      if (error.status === 403) csrf = undefined;
      throw error;
    }
  }

  function resetCsrf() { csrf = undefined; }
  return { request, resetCsrf };
})();
```

Load `api.js` before `auth.js` and `app.js`. Replace the old local `api()` implementation with `QH_API.request`.

- [ ] **Step 2: Add accessible login/register shell**

Add a `main#auth-view` containing two labelled forms, inline error region with `role="alert"`, 12-character password hint and mode switch buttons. Wrap the existing application in `div#app-shell` with `hidden` until `GET /api/auth/me` succeeds.

- [ ] **Step 3: Implement auth bootstrap**

`frontend/auth.js` public contract:

```javascript
window.QH_AUTH = { bootstrap, login, register, logout, currentUser };
```

`bootstrap()` calls `/api/auth/me`; a 401 shows `#auth-view`, any authenticated result shows `#app-shell` and renders username/roles. Successful login/register resets CSRF, refetches token and invokes `window.QH_APP?.start(user)`. Logout resets CSRF and returns to auth view without reloading.

- [ ] **Step 4: Prevent application bootstrap before authentication**

Move the current eager initialization into:

```javascript
window.QH_APP = {
  async start(user) {
    if (state.started) return;
    state.started = true;
    state.user = user;
    await init();
  },
  stop() {
    state.started = false;
    state.user = null;
  }
};
document.addEventListener('DOMContentLoaded', () => QH_AUTH.bootstrap());
```

If any API request returns 401 after startup, keep current textarea values in DOM, call `QH_APP.stop()` and show the login view.

- [ ] **Step 5: Add focused auth styles and syntax checks**

The auth view must remain usable at 320px width, show visible focus rings and never encode errors by color alone.

Run: `node --check frontend/api.js`

Expected: exit 0.

Run: `node --check frontend/auth.js`

Expected: exit 0.

Run: `node --check frontend/app.js`

Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add frontend/index.html frontend/app.js frontend/styles.css frontend/api.js frontend/auth.js
git commit -m "feat: add lightweight session authentication ui"
```

## Task 8: Configure deployment and document the first admin

**Files:**

- Modify: `.env.example`
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Modify: `scripts/dev-server.mjs`

- [ ] **Step 1: Add explicit environment contract**

Add:

```dotenv
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=
APP_ADMIN_EMAIL=
SESSION_COOKIE_SECURE=false
```

Pass all four variables to the backend container. Document that production requires `SESSION_COOKIE_SECURE=true`, TLS at the reverse proxy and a unique password of at least 12 characters.

- [ ] **Step 2: Ensure the development proxy preserves cookie and CSRF headers**

Keep request headers except the incoming Host replacement, forward all upstream `set-cookie` headers unchanged, and do not add CORS headers. Add a comment explaining that frontend and API intentionally share one origin through the proxy.

- [ ] **Step 3: Update README routes and startup flow**

Document `/api/auth/csrf`, `/register`, `/login`, `/logout`, `/me`, seeded admin behavior and that application APIs now require a session.

- [ ] **Step 4: Verify config and commit**

Run: `docker compose config`

Expected: exit 0 and the four identity/session variables appear under backend environment.

Run: `node --check scripts/dev-server.mjs`

Expected: exit 0.

```bash
git add .env.example docker-compose.yml README.md scripts/dev-server.mjs
git commit -m "docs: configure local accounts and secure sessions"
```

## Task 9: Run the phase acceptance suite

**Files:**

- Modify only if a verification failure exposes a real defect in files already listed above.

- [ ] **Step 1: Run all backend tests**

Run: `cd backend && mvn test`

Expected: BUILD SUCCESS with zero failures and zero errors.

- [ ] **Step 2: Build the backend artifact**

Run: `cd backend && mvn package`

Expected: BUILD SUCCESS and `backend/target/question-hacker-backend-1.0.0.jar` exists.

- [ ] **Step 3: Check all JavaScript entry points**

Run: `node --check frontend/api.js`

Run: `node --check frontend/auth.js`

Run: `node --check frontend/app.js`

Run: `node --check scripts/dev-server.mjs`

Expected: every command exits 0.

- [ ] **Step 4: Run a same-origin smoke test**

Start with configured admin credentials using `./scripts/run-local.sh`, then verify:

```text
GET  /api/auth/csrf       → 200
GET  /api/auth/me         → 401 before login
POST /api/auth/login      → 200 with valid CSRF and session cookie
GET  /api/chat/sessions   → 200 after login
POST /api/auth/logout     → 204
GET  /api/chat/sessions   → 401 after logout
```

- [ ] **Step 5: Confirm working tree scope**

Run: `git status --short`

Expected: no uncommitted files from this phase.

## Subsequent implementation plans

После успешного завершения этого плана следующие автономные планы создаются и выполняются в таком порядке:

1. `2026-08-12-server-curriculum-and-adaptive-trainer.md` — импорт theory/scenarios, `BACKCASTING`, evidence grades, 98 карточек, rationale, mastery и confusion.
2. `2026-08-12-four-step-practice-assessment.md` — assignments, четыре поля, три gate, асинхронная ACP-оценка, строгий JSON и честный fallback.
3. `2026-08-12-scenario-moderation-queue.md` — генерация candidates, автоотбраковка, ADMIN queue, audit и публикация.
4. `2026-08-12-thin-frontend-and-accessibility.md` — удаление локальной бизнес-логики, server view models, accessible flip-card, keyboard/focus/live regions и итоговые end-to-end проверки.

Каждый следующий план начинается только после зелёной acceptance suite предыдущего, поэтому приложение остаётся запускаемым на каждом checkpoint.
