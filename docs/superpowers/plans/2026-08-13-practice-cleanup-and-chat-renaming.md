# Practice Cleanup and Chat Renaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the redundant Practice overview controls and add backend-owned automatic and manual chat naming with inline editing.

**Architecture:** `ChatService` remains the single authority for title normalization and ownership checks. `ApiController` exposes an owner-scoped `PATCH` mutation, while the frontend keeps only ephemeral edit state and renders the server response. Practice creation continues to request published scenarios and no generation logic is added to that route.

**Tech Stack:** Java 21, Spring Boot MVC/Security, JdbcTemplate/H2, vanilla JavaScript, Node test runner, Maven/JUnit/MockMvc.

---

### Task 1: Backend-owned chat titles

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/ChatOwnershipTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ApiController.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ChatService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/DatabaseStore.java`

- [ ] **Step 1: Write failing ownership and naming tests**

Add MockMvc tests that create sessions for Alice and Bob and assert:

```java
mvc.perform(patch("/api/chat/sessions/{id}", own.id())
        .with(user("alice")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"  Новый заголовок  \"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.title").value("Новый заголовок"));
```

Cover blank and oversized titles with `400`, Bob's session with `404`, and a first message whose normalized Unicode prefix becomes exactly 30 code points plus `...`. Assert a manually renamed title remains unchanged after sending.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -f backend/pom.xml -Dtest=ChatOwnershipTest test`

Expected: FAIL because `PATCH /api/chat/sessions/{id}` does not exist and automatic titles still use the old 64-character rule without `...`.

- [ ] **Step 3: Implement the minimal backend API and rules**

Add:

```java
@PatchMapping("/chat/sessions/{sessionId}")
public DatabaseStore.SessionRow renameSession(
        @PathVariable UUID sessionId,
        @Valid @RequestBody RenameSessionRequest request) {
    return chat.renameSession(auth.requireCurrentUser().id(), sessionId, request.title());
}

public record RenameSessionRequest(@NotBlank @Size(max = 180) String title) {}
```

In `ChatService`, trim and validate the manual title, require ownership, persist it, and return the updated row. Replace `makeTitle` with Unicode-safe code-point truncation:

```java
private String makeTitle(String text) {
    String oneLine = text.replaceAll("\\s+", " ").strip();
    int count = Math.min(30, oneLine.codePointCount(0, oneLine.length()));
    int end = oneLine.offsetByCodePoints(0, count);
    return oneLine.substring(0, end) + "...";
}
```

Make the repository title update owner-scoped and return whether a row changed.

- [ ] **Step 4: Run focused and full backend tests**

Run: `mvn -f backend/pom.xml -Dtest=ChatOwnershipTest test`

Expected: PASS.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests PASS.

- [ ] **Step 5: Commit backend naming**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/ApiController.java \
  backend/src/main/java/ru/questionhacker/trainer/ChatService.java \
  backend/src/main/java/ru/questionhacker/trainer/DatabaseStore.java \
  backend/src/test/java/ru/questionhacker/trainer/ChatOwnershipTest.java
git commit -m "feat: rename coach dialogs on the server"
```

### Task 2: Inline chat rename interaction

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`

- [ ] **Step 1: Write failing frontend contract tests**

Assert that session rows include a pencil button and that `app.js` contains:

```js
api(`/chat/sessions/${sessionId}`, {
  method: 'PATCH', body: JSON.stringify({ title })
})
```

Assert the interaction handles `Enter`, `Escape`, cancel-on-blur, blocks duplicate saves, and does not implement the 30-character automatic-title rule in the browser.

- [ ] **Step 2: Run frontend tests and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because no rename control or `PATCH` call exists.

- [ ] **Step 3: Implement inline editing**

Keep `chatSessions`, `editingSessionId`, and `renameSubmitting` as ephemeral state. Render a pencil control for normal rows. For the editing row, render a labelled inline input plus save/cancel controls. Bind:

```js
if (event.key === 'Enter') saveSessionRename(sessionId, input.value);
if (event.key === 'Escape') cancelSessionRename();
```

Save through the backend endpoint, replace the matching session with the returned row, rerender, and preserve the typed value on failure. Blur cancels only when a save is not pending.

- [ ] **Step 4: Run frontend tests and syntax checks**

Run: `node --test frontend/tests/*.test.mjs`

Expected: all frontend tests PASS.

Run: `node --check frontend/app.js`

Expected: exit 0.

- [ ] **Step 5: Commit inline rename**

```bash
git add frontend/app.js frontend/styles.css frontend/tests/thin-client.test.mjs
git commit -m "feat: rename coach dialogs inline"
```

### Task 3: Remove redundant Practice overview UI

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Modify: `frontend/sw.js`

- [ ] **Step 1: Write failing Practice cleanup tests**

Assert the HTML does not contain `id="practice-home"`, `practice-number`, `practice-history-status`, or the sentence `Четыре шага превращают вопрос в проверяемое решение`. Assert `app.js` no longer binds `practice-home` or defines `showPracticeHome`, while `new-practice` still calls `/practice/assignments` and contains no scenario-generation endpoint.

- [ ] **Step 2: Run frontend tests and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL on the still-present overview elements and handler.

- [ ] **Step 3: Remove the overview behavior and requested copy**

Delete the overview button, decorative number, count/status element, explanatory copy, `showPracticeHome`, and its event listener. Keep the cycle list, random worked example, `startPractice`, and backend assignment request. Remove CSS selectors used only by deleted elements.

Bump the service-worker cache key from `question-hacker-v12` to `question-hacker-v13` so browsers receive the new shell.

- [ ] **Step 4: Run full verification**

Run: `node --test frontend/tests/*.test.mjs`

Expected: all frontend tests PASS.

Run: `node --check frontend/app.js && git diff --check`

Expected: exit 0.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests PASS.

- [ ] **Step 5: Commit Practice cleanup**

```bash
git add frontend/index.html frontend/app.js frontend/styles.css frontend/sw.js \
  frontend/tests/thin-client.test.mjs
git commit -m "refactor: simplify practice navigation"
```

### Task 4: Restart and browser verification

**Files:**
- No source changes expected.

- [ ] **Step 1: Restart the existing local application**

Stop only the current `run-local.sh` process group and start `main` again with:

```bash
FRONTEND_PORT=18090 SERVER_PORT=8081 ./scripts/run-local.sh
```

- [ ] **Step 2: Verify HTTP and authenticated flows**

Confirm `/` and `/api/auth/csrf` return `200`. Sign in as `demo`, open `#practice`, verify the removed UI is absent, then open `#coach`, rename a dialog, reload, and verify the server title persists. Create a fresh dialog, send a message, and verify its title is the first 30 normalized code points plus `...`.

- [ ] **Step 3: Check repository state**

Run: `git status --short`

Expected: clean working tree.

