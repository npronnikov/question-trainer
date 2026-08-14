# ACP Observability and Practice Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Log complete ACP interactions, clear existing practice history, add an admin cleanup control, expose a clear revision workflow, and allow multiple unfinished practice cycles.

**Architecture:** Keep ACP diagnostics at the single `AcpGateway` boundary through a focused logger component. Keep destructive practice cleanup in a transactional service behind an admin-only endpoint and a one-time Flyway migration. Reuse the existing practice editor and feedback model, but reorder and label them so revision fields are unmistakable; remove unfinished-cycle gating from both server and browser.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring JDBC/Security, Flyway, JUnit 5/MockMvc, vanilla JavaScript/HTML/CSS, Node test runner.

---

## File map

- Create `backend/src/main/java/ru/questionhacker/trainer/AcpInteractionLogger.java`: correlation IDs, durations, and request/response/error log records.
- Modify `backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java`: wrap every real ACP call with the logger.
- Create `backend/src/test/java/ru/questionhacker/trainer/AcpInteractionLoggerTest.java`: capture Logback events and verify content/correlation/error metadata.
- Create `backend/src/main/resources/db/migration/V10__clear_existing_practice_cycles.sql`: one-time removal of all existing practice history.
- Create `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAdministrationService.java`: transactional repeatable cleanup.
- Create `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAdminController.java`: admin endpoint and response contract.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`: ordered deletion queries.
- Create `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAdministrationTest.java`: authorization, complete cleanup, preservation, and idempotency.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java`: remove unfinished-cycle rejection.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`: remove obsolete unfinished lookup.
- Modify `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java`: assert issuance alongside each unfinished state.
- Modify `frontend/index.html`: admin cleanup button/dialog, feedback-before-form order, and revision heading.
- Modify `frontend/app.js`: cleanup request, unrestricted assignment button, and explicit revision editor state.
- Modify `frontend/styles.css`: destructive admin action and revision panel styling.
- Modify `frontend/tests/thin-client.test.mjs`: new frontend behavior assertions.
- Modify `frontend/sw.js`: invalidate the offline shell after changing cached frontend assets.

### Task 1: Structured ACP interaction logs

**Files:**
- Create: `backend/src/main/java/ru/questionhacker/trainer/AcpInteractionLogger.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java`
- Test: `backend/src/test/java/ru/questionhacker/trainer/AcpInteractionLoggerTest.java`

- [ ] **Step 1: Write the failing logger tests**

Create a Logback `ListAppender<ILoggingEvent>` around `AcpInteractionLogger` and assert that one interaction produces request and response messages containing the same UUID, full multiline prompt/answer, model, and a non-negative `durationMs`. Add a second test that calls `failure` and asserts `Level.ERROR` plus a non-null throwable proxy.

```java
@Test
void requestAndResponseShareInteractionIdAndKeepFullPayloads() {
    var interaction = subject.begin("full\nprompt", "gpt-test");
    subject.success(interaction, "full\nanswer");

    assertThat(events).hasSize(2);
    assertThat(events.get(0).getFormattedMessage()).contains(
            "ACP request", interaction.id().toString(), "gpt-test", "full\nprompt");
    assertThat(events.get(1).getFormattedMessage()).contains(
            "ACP response", interaction.id().toString(), "full\nanswer", "durationMs=");
}

@Test
void failureIncludesCorrelationAndStackTrace() {
    var interaction = subject.begin("prompt", "gpt-test");
    subject.failure(interaction, new IllegalStateException("agent failed"));

    var failure = events.getLast();
    assertThat(failure.getLevel()).isEqualTo(Level.ERROR);
    assertThat(failure.getFormattedMessage()).contains(interaction.id().toString(), "durationMs=");
    assertThat(failure.getThrowableProxy().getMessage()).isEqualTo("agent failed");
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `cd backend && ./mvnw -q -Dtest=AcpInteractionLoggerTest test`

Expected: compilation failure because `AcpInteractionLogger` does not exist.

- [ ] **Step 3: Implement the logger**

Create a Spring component with an immutable interaction handle:

```java
@Component
public class AcpInteractionLogger {
    private static final Logger log = LoggerFactory.getLogger("ru.questionhacker.trainer.acp.interaction");

    public Interaction begin(String prompt, String model) {
        var interaction = new Interaction(UUID.randomUUID(), System.nanoTime());
        log.info("ACP request interactionId={} model={} prompt=\n{}",
                interaction.id(), model, prompt);
        return interaction;
    }

    public void success(Interaction interaction, String response) {
        log.info("ACP response interactionId={} durationMs={} response=\n{}",
                interaction.id(), interaction.durationMs(), response);
    }

    public void failure(Interaction interaction, RuntimeException error) {
        log.error("ACP error interactionId={} durationMs={}",
                interaction.id(), interaction.durationMs(), error);
    }

    public record Interaction(UUID id, long startedAtNanos) {
        long durationMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        }
    }
}
```

Inject it into `AcpGateway`. Start logging only after the `ACP_ENABLED` check. Call `success` once after validating that the accumulated response is non-empty. Call `failure` in the existing runtime-exception handler and on the empty-response path; replace the old warning so errors are not logged twice.

- [ ] **Step 4: Run the focused and availability tests**

Run: `cd backend && ./mvnw -q -Dtest=AcpInteractionLoggerTest,AcpAvailabilityTest test`

Expected: PASS with no duplicate failure log assertion.

- [ ] **Step 5: Commit ACP logging**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/AcpInteractionLogger.java backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java backend/src/test/java/ru/questionhacker/trainer/AcpInteractionLoggerTest.java
git commit -m "feat: log ACP interactions"
```

### Task 2: One-time and admin-triggered practice cleanup

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__clear_existing_practice_cycles.sql`
- Create: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAdministrationService.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAdminController.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`
- Create: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAdministrationTest.java`

- [ ] **Step 1: Write failing admin cleanup tests**

Seed two assignments with drafts/attempts/assessments and remember the scenario count. Test the endpoint as a normal user and an admin:

```java
@Test
void ordinaryUserCannotClearPracticeCycles() throws Exception {
    mvc.perform(delete("/api/admin/practice/cycles")
            .with(user("ordinary").roles("USER")).with(csrf()))
            .andExpect(status().isForbidden());
}

@Test
void adminClearsAllPracticeHistoryWithoutDeletingCatalog() throws Exception {
    int scenariosBefore = count("scenario");

    mvc.perform(delete("/api/admin/practice/cycles")
            .with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedCycles").value(2));

    assertThat(count("practice_assignment")).isZero();
    assertThat(count("practice_draft")).isZero();
    assertThat(count("practice_attempt")).isZero();
    assertThat(count("practice_assessment")).isZero();
    assertThat(count("scenario")).isEqualTo(scenariosBefore);
}
```

Call the endpoint a second time and expect `{"deletedCycles":0}`.

- [ ] **Step 2: Run the tests and verify RED**

Run: `cd backend && ./mvnw -q -Dtest=PracticeAdministrationTest test`

Expected: `404` because the endpoint does not exist.

- [ ] **Step 3: Implement ordered cleanup and endpoint**

Add repository deletion in foreign-key order and return the assignment count captured first:

```java
public int deleteAllCycles() {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM practice_assignment", Integer.class);
    jdbc.update("DELETE FROM practice_assessment WHERE attempt_id IN (SELECT id FROM practice_attempt)");
    jdbc.update("DELETE FROM practice_attempt");
    jdbc.update("DELETE FROM practice_draft");
    jdbc.update("DELETE FROM practice_assignment");
    return count == null ? 0 : count;
}
```

Wrap it in a transactional service and expose the response:

```java
@Service
public class PracticeAdministrationService {
    private final PracticeRepository practice;

    @Transactional
    public int clearAllCycles() {
        return practice.deleteAllCycles();
    }
}

@RestController
@RequestMapping("/api/admin/practice")
public class PracticeAdminController {
    @DeleteMapping("/cycles")
    public ClearResult clearCycles() {
        return new ClearResult(administration.clearAllCycles());
    }

    public record ClearResult(int deletedCycles) { }
}
```

Create `V10__clear_existing_practice_cycles.sql` with the same four `DELETE` statements in the same order.

- [ ] **Step 4: Run cleanup and migration tests**

Run: `cd backend && ./mvnw -q -Dtest=PracticeAdministrationTest,MigrationTest test`

Expected: PASS; catalog count unchanged and second cleanup returns zero.

- [ ] **Step 5: Commit cleanup backend**

```bash
git add backend/src/main/resources/db/migration/V10__clear_existing_practice_cycles.sql backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAdministrationService.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAdminController.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAdministrationTest.java
git commit -m "feat: add admin practice cleanup"
```

### Task 3: Allow multiple unfinished practice cycles

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`

- [ ] **Step 1: Replace blocking tests with failing issuance tests**

Change the draft test to create one INVERSION assignment and assert that the next request creates HYPERBOLE. Parameterize the latest attempt statuses and make the same assertion:

```java
@ParameterizedTest
@ValueSource(strings = {"EVALUATING", "NEEDS_REVISION", "UNVERIFIED"})
void unfinishedLatestStatusDoesNotBlockAnotherAssignment(String status) throws Exception {
    publishForPractice("INVERSION", 0);
    publishForPractice("HYPERBOLE", 0);
    UUID first = assignment("practice-alice");
    replaceDraftWithAttempt(first, alice.id(), status);

    UUID second = assignment("practice-alice");
    assertThat(category(second)).isEqualTo("HYPERBOLE");
}
```

- [ ] **Step 2: Run the assignment tests and verify RED**

Run: `cd backend && ./mvnw -q -Dtest=PracticeAssignmentTest test`

Expected: the new tests receive `409 PRACTICE_ASSIGNMENT_INCOMPLETE`.

- [ ] **Step 3: Remove unfinished gating**

Delete this block from `PracticeAssignmentService.create`:

```java
if (practice.hasUnfinishedAssignment(ownerId)) {
    throw PracticeAssignmentUnavailableException.incomplete();
}
```

Delete `PracticeRepository.hasUnfinishedAssignment`. Keep `lockOwner`, `assignmentCount`, category rotation, and scenario exclusion unchanged.

- [ ] **Step 4: Run assignment tests**

Run: `cd backend && ./mvnw -q -Dtest=PracticeAssignmentTest test`

Expected: PASS, including category rotation and no scenario reuse.

- [ ] **Step 5: Commit unrestricted issuance**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java
git commit -m "feat: allow parallel practice cycles"
```

### Task 4: Admin cleanup control and explicit revision form

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Modify: `frontend/sw.js`

- [ ] **Step 1: Write failing thin-client assertions**

Add tests that require:

```javascript
assert.match(html, /id="clear-practice-cycles"/);
assert.match(html, /id="clear-practice-dialog"/);
assert.match(app, /api\('\/admin\/practice\/cycles', \{ method: 'DELETE' \}\)/);
assert.match(app, /Удалено циклов практики:/);
assert.doesNotMatch(app, /practiceCycles\.find\(cycle => cycle\.status !== 'PASSED'\)/);
assert.doesNotMatch(app, /code === PRACTICE_ASSIGNMENT_INCOMPLETE/);
assert.match(html, /id="practice-feedback"[\s\S]*id="practice-form"/);
assert.match(html, /id="practice-revision-intro"[^>]*hidden/);
assert.match(app, /Исправление попытки/);
assert.match(app, /scrollIntoView\(\{ behavior: 'smooth', block: 'start' \}\)/);
```

Keep the existing hint disclosure assertions because the data cleanup makes all new assignments use moderated hints.

- [ ] **Step 2: Run frontend tests and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: failures for missing cleanup UI, remaining unfinished gating, current form order, and missing revision intro.

- [ ] **Step 3: Add admin cleanup UI**

Add `Удалить все циклы практики` as a danger-styled button in the moderation header and add a dedicated confirmation dialog. Bind it with:

```javascript
function requestClearPracticeCycles() {
  $('#clear-practice-dialog').showModal();
}

async function confirmClearPracticeCycles(event) {
  event.preventDefault();
  const button = $('#clear-practice-confirm');
  setBusy(button, true, 'Удаляем…');
  try {
    const result = await api('/admin/practice/cycles', { method: 'DELETE' });
    $('#clear-practice-dialog').close();
    resetPracticeAfterAdminClear();
    showToast(`Удалено циклов практики: ${result.deletedCycles}`);
  } catch (error) {
    showToast(error.message);
  } finally {
    setBusy(button, false, 'Удалить все циклы');
  }
}
```

`resetPracticeAfterAdminClear` clears practice globals/list markup, hides the workspace, reveals the empty state, and sets `practiceInitialized = false` so a later visit reloads server state.

- [ ] **Step 4: Remove client-side unfinished gating**

Replace `syncPracticeAvailability` with button state based only on `practiceSubmitting`; remove `PRACTICE_ASSIGNMENT_INCOMPLETE`, its message branch, and the disabled-button precondition that treats a prior incomplete cycle as a blocker. Preserve `PRACTICE_CATALOG_EXHAUSTED` handling.

```javascript
function syncPracticeAvailability() {
  [$('#start-practice'), $('#new-practice')].forEach(button => {
    if (!button) return;
    button.disabled = practiceSubmitting;
    button.title = '';
  });
}
```

- [ ] **Step 5: Reorder and label revision editing**

Move `#practice-feedback` before `#practice-form`. Add this first child to the form:

```html
<div class="practice-revision-intro" id="practice-revision-intro" hidden>
  <span>ИСПРАВЛЕНИЕ</span>
  <h3 id="practice-revision-title"></h3>
  <p id="practice-revision-fields"></p>
</div>
```

Update `setRevisionFields` so `NEEDS_REVISION` with any non-empty field list shows the intro, lists Russian field labels, disables fields outside `fieldsToRevise`, and sets the submit label to `Отправить исправление →`. For other states, hide the intro and retain the existing assessment label. Rename the feedback action to `Перейти к исправлению` and make `focusFirstRevision` scroll the form into view before focusing the first editable textarea.

- [ ] **Step 6: Style and invalidate offline cache**

Style `.practice-revision-intro` as an orange-tinted bordered panel and keep mobile layout one-column. Increment the cache key in `frontend/sw.js` from `question-hacker-v15` to `question-hacker-v16`.

- [ ] **Step 7: Run frontend tests**

Run: `node --test frontend/tests/*.test.mjs`

Expected: all frontend tests PASS, including the existing hint disclosure test and the 100-character cycle preview test.

- [ ] **Step 8: Commit frontend controls**

```bash
git add frontend/index.html frontend/app.js frontend/styles.css frontend/sw.js frontend/tests/thin-client.test.mjs
git commit -m "feat: improve practice administration and revisions"
```

### Task 5: Full verification

**Files:**
- Verify only; fix failures in the owning task files before continuing.

- [ ] **Step 1: Run the complete backend suite**

Run: `cd backend && ./mvnw test`

Expected: `BUILD SUCCESS`, zero failures and zero errors.

- [ ] **Step 2: Run the complete frontend suite**

Run: `node --test frontend/tests/*.test.mjs`

Expected: all tests PASS with zero failures.

- [ ] **Step 3: Build the backend artifact**

Run: `cd backend && ./mvnw -DskipTests package`

Expected: `BUILD SUCCESS` and a bootable JAR under `backend/target/`.

- [ ] **Step 4: Inspect final scope**

Run: `git status --short && git diff --check`

Expected: only intentional pre-existing working-tree changes remain; no whitespace errors.

