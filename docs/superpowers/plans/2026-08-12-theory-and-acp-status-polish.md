# Theory and ACP Status Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the obsolete theory footer, simplify evidence cards to two columns, and expose honest ACP fallback diagnostics from the lower agent card.

**Architecture:** Keep the browser a thin client. A focused backend `AcpAvailability` component owns configured/runtime availability and a sanitized last failure; `AcpGateway` updates it and `/api/system/status` exposes it. The frontend stores that status and renders it in a native accessible dialog without launching a diagnostic ACP process.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5/AssertJ, vanilla JavaScript, HTML/CSS, Node.js built-in test runner.

---

## File map

- `frontend/index.html` — remove the obsolete theory footer; make the lower ACP card interactive; add the status dialog.
- `frontend/app.js` — move evidence links into the first column and bind/render ACP status diagnostics.
- `frontend/styles.css` — change evidence layout to two columns and style the interactive ACP card/dialog.
- `frontend/tests/thin-client.test.mjs` — structural regression tests for all requested browser behavior.
- `backend/src/main/java/ru/questionhacker/trainer/AcpAvailability.java` — configured/runtime ACP state and sanitized reason.
- `backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java` — record ACP success/failure.
- `backend/src/main/java/ru/questionhacker/trainer/ApiController.java` — expose availability and reason in system status.
- `backend/src/test/java/ru/questionhacker/trainer/AcpAvailabilityTest.java` — unit coverage for disabled, failed, and recovered states.

### Task 1: Theory cleanup and two-column evidence

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`

- [ ] **Step 1: Write the failing frontend regression test**

Append this test to `frontend/tests/thin-client.test.mjs`:

```javascript
test('theory omits the server-program footer and evidence uses two content columns', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const css = await fs.readFile(new URL('styles.css', frontend), 'utf8');

  assert.doesNotMatch(html, /СЕРВЕРНАЯ ПРОГРАММА/);
  assert.match(app, /class="evidence-meta"[\s\S]*section\.source[\s\S]*<\/div><p>/);
  assert.match(css, /\.evidence-card\s*\{[^}]*grid-template-columns:\s*minmax\(180px,\s*\.7fr\)\s+1\.5fr;/);
});
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because `index.html` still contains `СЕРВЕРНАЯ ПРОГРАММА`, evidence has no `evidence-meta`, and CSS still declares a third `auto` column.

- [ ] **Step 3: Implement the minimal theory markup and CSS changes**

Delete the trailing `.compare-section` containing `СЕРВЕРНАЯ ПРОГРАММА` from `frontend/index.html`.

Change the evidence template in `frontend/app.js` to:

```javascript
${evidence.map(section => `<article class="evidence-card"><div class="evidence-meta"><span>${escapeHtml(section.evidenceGrade || '—')}</span><h4>${escapeHtml(section.title)}</h4>${section.source ? `<a href="${escapeHtml(section.source.url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(section.source.title)} ↗</a>` : ''}</div><p>${escapeHtml(section.content)}</p></article>`).join('')}
```

Change the desktop layout in `frontend/styles.css` to:

```css
.evidence-card { display: grid; grid-template-columns: minmax(180px, .7fr) 1.5fr; gap: 22px; align-items: start; padding: 20px 0; border-bottom: 1px solid var(--line); }
.evidence-meta a { display: inline-block; margin-top: 10px; }
```

Keep the existing mobile rule `.evidence-card { grid-template-columns: 1fr; }`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: all tests PASS.

- [ ] **Step 5: Commit the theory change**

```bash
git add frontend/index.html frontend/app.js frontend/styles.css frontend/tests/thin-client.test.mjs
git commit -m "fix: simplify theory evidence layout"
```

### Task 2: Backend ACP availability and reason

**Files:**
- Create: `backend/src/test/java/ru/questionhacker/trainer/AcpAvailabilityTest.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/AcpAvailability.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ApiController.java`

- [ ] **Step 1: Write the failing availability unit tests**

Create `backend/src/test/java/ru/questionhacker/trainer/AcpAvailabilityTest.java`:

```java
package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AcpAvailabilityTest {

    @Test
    void disabledConfigurationExplainsFallbackWithoutAProbe() {
        var status = new AcpAvailability(properties(false));

        assertThat(status.available()).isFalse();
        assertThat(status.reason()).isEqualTo("ACP отключён настройкой ACP_ENABLED=false.");
    }

    @Test
    void runtimeFailureIsSanitizedAndSuccessRestoresAvailability() {
        var status = new AcpAvailability(properties(true));

        status.recordFailure(new IllegalStateException("session failed\n  at internal.Client"));

        assertThat(status.available()).isFalse();
        assertThat(status.reason()).isEqualTo("Не удалось запустить ACP-сессию: session failed at internal.Client");

        status.recordSuccess();

        assertThat(status.available()).isTrue();
        assertThat(status.reason()).isNull();
    }

    private AppProperties properties(boolean enabled) {
        return new AppProperties(
                new AppProperties.Acp(enabled, true, "npx", List.of("codex-acp"), ".",
                        Duration.ofSeconds(1), 1024, List.of(), List.of("model"), "model"),
                new AppProperties.Chat(12000, 24),
                new AppProperties.Admin("admin", "password", "admin@example.test"));
    }
}
```

- [ ] **Step 2: Run the focused backend test and verify RED**

Run: `mvn -f backend/pom.xml -Dtest=AcpAvailabilityTest test`

Expected: compilation FAIL because `AcpAvailability` does not exist.

- [ ] **Step 3: Implement the focused availability component**

Create `backend/src/main/java/ru/questionhacker/trainer/AcpAvailability.java`:

```java
package ru.questionhacker.trainer;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class AcpAvailability {

    private static final int MAX_REASON_LENGTH = 320;
    private final AppProperties properties;
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    public AcpAvailability(AppProperties properties) {
        this.properties = properties;
    }

    public boolean available() {
        return properties.acp().enabled() && lastFailure.get() == null;
    }

    public String reason() {
        if (!properties.acp().enabled()) {
            return "ACP отключён настройкой ACP_ENABLED=false.";
        }
        return lastFailure.get();
    }

    public void recordFailure(Throwable error) {
        String message = error == null ? null : error.getMessage();
        String normalized = message == null || message.isBlank()
                ? "неизвестная ошибка запуска"
                : message.replaceAll("\\s+", " ").strip();
        if (normalized.length() > MAX_REASON_LENGTH) {
            normalized = normalized.substring(0, MAX_REASON_LENGTH) + "…";
        }
        lastFailure.set("Не удалось запустить ACP-сессию: " + normalized);
    }

    public void recordSuccess() {
        lastFailure.set(null);
    }
}
```

- [ ] **Step 4: Connect the tracker to the gateway and status response**

Add `AcpAvailability availability` to the `AcpGateway` constructor. In the runtime catch block call `availability.recordFailure(error)`. Record the empty-response exception before throwing it. Call `availability.recordSuccess()` immediately before returning a non-empty answer. Add gateway accessors:

```java
public boolean available() {
    return availability.available();
}

public String unavailabilityReason() {
    return availability.reason();
}
```

Extend `ApiController.status()` and `SystemStatus` with the two new fields:

```java
acp.enabled(),
acp.available(),
properties.acp().fallbackEnabled(),
acp.unavailabilityReason(),
```

```java
public record SystemStatus(boolean acpEnabled, boolean acpAvailable,
                           boolean fallbackEnabled, String acpReason,
                           String agentCommand, List<String> models,
                           String defaultModel, String database, String curriculum) {
}
```

- [ ] **Step 5: Run focused and complete backend tests**

Run: `mvn -f backend/pom.xml -Dtest=AcpAvailabilityTest test`

Expected: PASS.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests PASS.

- [ ] **Step 6: Commit backend diagnostics**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/AcpAvailability.java backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java backend/src/main/java/ru/questionhacker/trainer/ApiController.java backend/src/test/java/ru/questionhacker/trainer/AcpAvailabilityTest.java
git commit -m "feat: expose ACP fallback reason"
```

### Task 3: Interactive lower ACP card and diagnostic dialog

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`

- [ ] **Step 1: Write the failing dialog regression test**

Append this test to `frontend/tests/thin-client.test.mjs`:

```javascript
test('lower ACP card opens an accessible diagnostic dialog', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(html, /<button[^>]*id="agent-status-card"[^>]*aria-haspopup="dialog"/);
  assert.match(html, /<dialog[^>]*id="acp-status-dialog"[^>]*aria-labelledby="acp-dialog-title"/);
  assert.match(html, /id="acp-dialog-reason"/);
  assert.match(app, /status\.acpAvailable/);
  assert.match(app, /#agent-status-card[\s\S]*showModal/);
});
```

- [ ] **Step 2: Run the focused frontend test and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because the lower card is a `div`, the dialog is absent, and `app.js` only uses `acpEnabled`.

- [ ] **Step 3: Add the accessible HTML controls**

Replace the lower card wrapper with:

```html
<button class="agent-card" id="agent-status-card" type="button" aria-haspopup="dialog" aria-controls="acp-status-dialog">
  <div class="agent-card-head"><span class="connection-dot" id="agent-dot"></span><strong>ACP agent</strong><span aria-hidden="true">↗</span></div>
  <p id="agent-status">Проверяем backend…</p><code id="agent-command">—</code>
</button>
```

Add before the toast:

```html
<dialog class="confirm-dialog acp-status-dialog" id="acp-status-dialog" aria-labelledby="acp-dialog-title" aria-describedby="acp-dialog-reason">
  <form method="dialog">
    <span class="confirm-dialog-mark" aria-hidden="true">?</span>
    <p class="eyebrow">СОСТОЯНИЕ ACP</p>
    <h2 id="acp-dialog-title">ACP agent</h2>
    <p><strong id="acp-dialog-state">Проверяем состояние…</strong></p>
    <p id="acp-dialog-reason">Диагностика ещё не загружена.</p>
    <code id="acp-dialog-command">—</code>
    <div class="confirm-dialog-actions"><button class="secondary-button" type="submit">Закрыть</button></div>
  </form>
</dialog>
```

- [ ] **Step 4: Bind status data and dialog behavior**

Add `let systemStatus = null;` and `let systemStatusError = null;` to `frontend/app.js`. In `loadSystemStatus()`, use `status.acpAvailable`, retain the response, and retain caught errors. Add:

```javascript
function openAcpStatusDialog() {
  const dialog = $('#acp-status-dialog');
  const available = systemStatus?.acpAvailable === true;
  $('#acp-dialog-state').textContent = available ? 'ACP-сессии доступны' : 'Работает серверный fallback';
  $('#acp-dialog-reason').textContent = available
    ? 'Последний запуск ACP завершился успешно; новых ошибок не зафиксировано.'
    : systemStatus?.acpReason || systemStatusError || 'ACP-сессия недоступна; подробная причина не получена.';
  $('#acp-dialog-command').textContent = systemStatus?.agentCommand || '—';
  dialog.showModal();
}
```

Bind `#agent-status-card` to `openAcpStatusDialog` during boot. Continue showing the compact fallback label using `acpAvailable`, while retaining `acpEnabled` only as configuration metadata.

- [ ] **Step 5: Style the interactive card and dialog**

Extend `frontend/styles.css`:

```css
.agent-card { width: 100%; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.agent-card:hover, .agent-card:focus-visible { border-color: rgba(204,255,41,.65); background: rgba(255,255,255,.07); }
.agent-card-head strong + span { margin-left: auto; color: var(--acid); }
.acp-status-dialog code { position: relative; display: block; padding: 12px 14px; border-radius: 12px; background: var(--ink); color: var(--acid); overflow-wrap: anywhere; }
```

- [ ] **Step 6: Run all frontend tests and verify GREEN**

Run: `node --test frontend/tests/*.test.mjs`

Expected: all frontend tests PASS.

- [ ] **Step 7: Commit the dialog**

```bash
git add frontend/index.html frontend/app.js frontend/styles.css frontend/tests/thin-client.test.mjs
git commit -m "feat: explain ACP fallback in status dialog"
```

### Task 4: Final verification and merge to main

**Files:**
- Verify all modified files.
- Merge branch history into `main` from `/Users/nick/IdeaProjects/question-trainer`.

- [ ] **Step 1: Run complete verification in the feature worktree**

Run: `node --test frontend/tests/*.test.mjs`

Expected: all frontend tests PASS.

Run: `mvn -f backend/pom.xml test`

Expected: all backend tests PASS with zero failures/errors.

Run: `git diff --check && git status -sb`

Expected: no whitespace errors and a clean `codex/backend-first-platform` worktree.

- [ ] **Step 2: Merge the feature branch into main**

From `/Users/nick/IdeaProjects/question-trainer`, run:

```bash
git merge --no-ff codex/backend-first-platform
```

Expected: merge completes without conflicts.

- [ ] **Step 3: Verify the merged main branch**

Run from `/Users/nick/IdeaProjects/question-trainer`:

```bash
node --test frontend/tests/*.test.mjs
mvn -f backend/pom.xml test
git status -sb
```

Expected: both test suites PASS and `main` is clean.

