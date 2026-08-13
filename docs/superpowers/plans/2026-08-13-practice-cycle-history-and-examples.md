# Practice Cycle History and Server Examples Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add owner-only practice-cycle history, recoverable server drafts, and one server-hosted full-cycle example per methodology to `#practice`.

**Architecture:** Keep `practice_assignment` as the cycle aggregate root and immutable attempts/assessments as its timeline. Add focused draft/example tables and a `PracticeCycleService` that composes list/detail/editor views from repository rows; the existing assessment service clears drafts transactionally when it accepts a submission. The frontend remains a thin server-driven client that loads the random example and history, renders a selected cycle, and debounces draft writes.

**Tech Stack:** Java 21, Spring Boot MVC/JDBC/Security/Validation, Flyway/H2, vanilla HTML/CSS/JavaScript, Node test runner.

---

## File structure

- Create `backend/src/main/resources/db/migration/V7__practice_history_and_examples.sql`: draft/example schema and seven category examples.
- Create `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeCycleService.java`: owner-only list/detail/draft/example screen models.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`: cycle, draft, attempt-list and random-example queries.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java`: history/detail/draft/example endpoints.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java`: create the initial empty draft with a new assignment.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java`: transactionally remove a draft after accepting an attempt/revision and expose attempt mapping to the cycle service.
- Create `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeCycleHistoryTest.java`: API ownership, grouping, ordering, draft and example behavior.
- Modify `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java`: draft cleanup during submit/revision.
- Modify `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java`: schema/seed cardinality.
- Modify `frontend/index.html`: practice history region, random example and timeline containers, autosave live-region.
- Modify `frontend/app.js`: practice route hydration, selection, timeline/editor restoration, autosave and stale-response guards.
- Modify `frontend/styles.css`: cycle list, example/timeline cards and responsive practice layout.
- Modify `frontend/tests/thin-client.test.mjs`: structural and server-delegation regressions.
- Modify `frontend/sw.js`: invalidate the offline shell after markup/script/style changes.

### Task 1: Add draft and example persistence

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__practice_history_and_examples.sql`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java`

- [ ] **Step 1: Write the failing migration assertions**

Add a test that expects both tables and exactly seven category-unique seed rows:

```java
@Test
void flywayCreatesPracticeDraftsAndOneExamplePerCategory() {
    Integer tables = jdbc.queryForObject("""
            SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
            WHERE UPPER(TABLE_NAME) IN ('PRACTICE_DRAFT', 'PRACTICE_EXAMPLE')
            """, Integer.class);
    Integer examples = jdbc.queryForObject("SELECT COUNT(*) FROM practice_example", Integer.class);
    Integer categories = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT category_code) FROM practice_example", Integer.class);
    assertThat(tables).isEqualTo(2);
    assertThat(examples).isEqualTo(7);
    assertThat(categories).isEqualTo(7);
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd backend && ./mvnw -Dtest=MigrationTest#flywayCreatesPracticeDraftsAndOneExamplePerCategory test`

Expected: FAIL because `PRACTICE_DRAFT` and `PRACTICE_EXAMPLE` do not exist.

- [ ] **Step 3: Add V7 schema and seed content**

Create `practice_draft` with owner/assignment/base-attempt foreign keys and CLOB fields, plus `practice_example` with a unique category foreign key, published flag, and all full-cycle texts. Insert one meaningful Russian example for each of:

```text
INVERSION, HYPERBOLE, CROSS_DISCIPLINE, BACKCASTING,
PROVOCATION, REFRAMING, SIMPLIFICATION
```

Each example must contain a distinct situation, question, answer, reasoning, concrete solution and model-style recommendation that demonstrates the named method.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `cd backend && ./mvnw -Dtest=MigrationTest test`

Expected: all `MigrationTest` methods PASS.

- [ ] **Step 5: Commit the persistence slice**

```bash
git add backend/src/main/resources/db/migration/V7__practice_history_and_examples.sql backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java
git commit -m "feat: persist practice drafts and examples"
```

### Task 2: Expose owner-only cycle history, detail, draft and example APIs

**Files:**
- Create: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeCycleHistoryTest.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeCycleService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java`

- [ ] **Step 1: Write failing API lifecycle tests**

Cover these exact calls:

```java
mvc.perform(get("/api/practice/cycles").with(user("practice-alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("DRAFT"));

mvc.perform(put("/api/practice/cycles/{id}/draft", assignment)
        .with(user("practice-alice")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(draftJson))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.question").value(question));

mvc.perform(get("/api/practice/cycles/{id}", assignment)
        .with(user("practice-alice")))
        .andExpect(jsonPath("$.draft.question").value(question))
        .andExpect(jsonPath("$.attempts").isEmpty());

mvc.perform(get("/api/practice/examples/random").with(user("practice-alice")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetCategory.code").isNotEmpty())
        .andExpect(jsonPath("$.recommendation").isNotEmpty());
```

Also assert Bob receives `404` for Alice's detail/draft, two attempts remain one cycle with `attemptCount=2`, and a draft update moves that cycle to the first history row.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `cd backend && ./mvnw -Dtest=PracticeCycleHistoryTest test`

Expected: FAIL with 404/405 because the new endpoints are absent.

- [ ] **Step 3: Add repository rows and queries**

Add focused records:

```java
public record CycleSummaryRow(UUID assignmentId, String categoryCode, String categoryName,
        String domain, String situation, String latestStatus, int attemptCount,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

public record DraftRow(UUID assignmentId, UUID ownerId, UUID baseAttemptId,
        String question, String answer, String reasoning, String solution,
        OffsetDateTime updatedAt) {}

public record ExampleRow(UUID id, String categoryCode, String categoryName,
        String domain, String situation, String question, String answer,
        String reasoning, String solution, String recommendation) {}
```

Implement `listCycles(ownerId)`, `listAttempts(ownerId, assignmentId)`, `findDraft`, `saveDraft`, `deleteDraft`, `createEmptyDraft`, and `randomExample`. Use aggregate subqueries so each assignment produces one history row and order by `updated_at DESC`.

- [ ] **Step 4: Add the composing service**

`PracticeCycleService` must produce:

```java
public record CycleView(AssignmentView assignment, List<AttemptView> attempts,
        DraftView draft, EditorView editor) {}
public record EditorView(UUID baseAttemptId, String question, String answer,
        String reasoning, String solution, List<String> editableFields) {}
```

For a saved draft use its values. Without a draft, use blank fields for a new cycle or the latest attempt values plus `fieldsToRevise` for `NEEDS_REVISION`. A passed/unverified cycle gets an empty editable field list.

- [ ] **Step 5: Add controller contracts and assignment draft creation**

Add:

```java
@GetMapping("/cycles")
@GetMapping("/cycles/{assignmentId}")
@PutMapping("/cycles/{assignmentId}/draft")
@GetMapping("/examples/random")
```

The draft request has optional `baseAttemptId` plus four non-null strings with the same maximum sizes as the form. Create an empty draft in the same transaction as assignment creation.

- [ ] **Step 6: Run the focused tests and verify GREEN**

Run: `cd backend && ./mvnw -Dtest=PracticeCycleHistoryTest,PracticeAssignmentTest test`

Expected: all selected tests PASS.

- [ ] **Step 7: Commit the API slice**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice backend/src/test/java/ru/questionhacker/trainer/practice
git commit -m "feat: expose practice cycle history"
```

### Task 3: Clear drafts when attempts are accepted

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java`

- [ ] **Step 1: Write failing cleanup tests**

Before submit and revision, save a draft through the new endpoint. Immediately after the accepted response assert:

```java
assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM practice_draft WHERE assignment_id=?",
        Integer.class, assignment)).isZero();
```

Cover first submission and accepted revision separately.

- [ ] **Step 2: Run the focused lifecycle test and verify RED**

Run: `cd backend && ./mvnw -Dtest=PracticeAttemptLifecycleTest test`

Expected: the new assertions FAIL because accepted attempts leave drafts behind.

- [ ] **Step 3: Make submit/revise transactional and delete the draft**

Annotate `submit` and `revise` with `@Transactional`. Call `practice.deleteDraft(ownerId, assignmentId)` only after `createAttempt` returns. Do not delete on validation, authorization, conflict or persistence failures.

- [ ] **Step 4: Run practice backend tests and verify GREEN**

Run: `cd backend && ./mvnw -Dtest='ru.questionhacker.trainer.practice.*' test`

Expected: all practice package tests PASS.

- [ ] **Step 5: Commit lifecycle cleanup**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java
git commit -m "fix: clear accepted practice drafts"
```

### Task 4: Add practice history and example structure to the thin client

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/index.html`
- Modify: `frontend/styles.css`

- [ ] **Step 1: Write failing structural tests**

Assert stable accessible hooks and no client persistence authority:

```javascript
assert.match(html, /id="practice-cycle-list"[^>]*role="list"/);
assert.match(html, /id="practice-example"/);
assert.match(html, /id="practice-timeline"/);
assert.match(html, /id="practice-save-status"[^>]*aria-live="polite"/);
assert.doesNotMatch(app, /localStorage|indexedDB/);
```

- [ ] **Step 2: Run frontend tests and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because the new regions do not exist.

- [ ] **Step 3: Add semantic markup**

Replace the practice-only sidebar copy with a visible `practice-history-tools` region containing the home button, new-cycle button, status and cycle list. Keep coach `session-tools` independent. Add random-example content inside the existing empty state, an immutable timeline before the form, and autosave status beside the attempt label.

- [ ] **Step 4: Add the visual treatment**

Use the existing ink/paper/acid/violet/orange tokens. The cycle list uses compact paper-on-ink rows; active is acid-on-ink. The example uses the four-step thought rail as its signature, and timeline attempts use restrained bordered sections with the model recommendation visually paired below each attempt. At `max-width: 720px`, history becomes a capped scroll region above the content without hiding keyboard access.

- [ ] **Step 5: Run frontend tests and verify GREEN**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: all selected tests PASS.

- [ ] **Step 6: Commit the interface skeleton**

```bash
git add frontend/index.html frontend/styles.css frontend/tests/thin-client.test.mjs
git commit -m "feat: add practice cycle interface"
```

### Task 5: Hydrate history, examples, timelines and drafts

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/app.js`
- Modify: `frontend/sw.js`

- [ ] **Step 1: Write failing client behavior contracts**

Assert the server calls and state guards:

```javascript
for (const endpoint of [
  '/practice/cycles', '/practice/examples/random',
  '/practice/cycles/${assignmentId}', '/draft'
]) assert.match(app, new RegExp(endpoint.replaceAll('/', '\\/')));

assert.match(app, /practiceLoadSequence/);
assert.match(app, /practiceDraftTimer/);
assert.match(app, /window\.setTimeout\([^,]+,\s*650\)/);
assert.match(app, /aria-current/);
```

Also assert the service-worker cache version advances from `question-hacker-v10`.

- [ ] **Step 2: Run frontend tests and verify RED**

Run: `node --test frontend/tests/*.test.mjs`

Expected: the new behavior test FAILS on missing endpoints/guards.

- [ ] **Step 3: Implement route hydration and selection**

Add state for cycle summaries, selected assignment, sequence token, draft timer/promise and one-time practice initialization. Entering `#practice` loads history and random example in parallel. Rendering history creates buttons with `aria-current`; selecting one fetches detail and ignores stale responses.

- [ ] **Step 4: Render random examples and full timelines**

Map server example fields into the five stable example regions. Map each attempt into four labelled user steps followed by its saved assessment feedback, strengths and priority correction. Escape every server string with `escapeHtml`.

- [ ] **Step 5: Restore the editor and autosave**

Populate the form from `detail.editor`, apply `editableFields`, and set `practiceAttempt` to the latest attempt when present. On input, update progress and schedule a 650 ms full-field `PUT` to `/practice/cycles/{assignmentId}/draft`; show saving/saved/error states. Cancel and await pending draft work before submit/revision. Refresh detail and history after accepted attempts reach terminal state.

- [ ] **Step 6: Advance the offline cache and verify GREEN**

Change `question-hacker-v10` to `question-hacker-v11`, then run:

```bash
node --check frontend/app.js
node --test frontend/tests/*.test.mjs
```

Expected: syntax check exits 0 and all frontend tests PASS.

- [ ] **Step 7: Commit the client behavior**

```bash
git add frontend/app.js frontend/sw.js frontend/tests/thin-client.test.mjs
git commit -m "feat: restore practice cycles and drafts"
```

### Task 6: Full verification and browser QA

**Files:**
- Modify only files required by failures found in this task.

- [ ] **Step 1: Run the complete automated suite**

```bash
node --check frontend/api.js
node --check frontend/auth.js
node --check frontend/app.js
node --check frontend/sw.js
node --test frontend/tests/*.test.mjs
cd backend && ./mvnw test
```

Expected: every command exits 0 with no failed tests.

- [ ] **Step 2: Run the local application and inspect `#practice`**

Use the repository local runner, sign in with the seeded development account, and verify desktop plus narrow viewport:

- a server example with all four steps and recommendation appears at startup;
- creating a situation immediately adds one sidebar cycle;
- typing shows saving then saved, and reload restores the draft;
- submitting adds an immutable attempt/recommendation pair;
- `NEEDS_REVISION` opens directly with only server-selected fields editable;
- switching cycles cannot show a stale previous detail;
- coach route session controls remain unchanged.

- [ ] **Step 3: Review the final diff against the design spec**

Run:

```bash
git diff HEAD~5 --check
git status --short
```

Expected: no whitespace errors; only intentional implementation files remain changed.

