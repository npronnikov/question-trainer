# Unverified Practice Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow a learner to edit the previously available practice fields and submit a fresh assessment after an `UNVERIFIED` result.

**Architecture:** Add an explicit retry command that creates an immutable child attempt and reuses the asynchronous assessment lifecycle. Derive editable fields from the attempt chain: all fields after a failed initial assessment, or the nearest verified `NEEDS_REVISION.fieldsToRevise` after a failed revision. Return the same field set to the cycle editor so retry, draft validation, and browser rendering have one server authority.

**Tech Stack:** Java 21, Spring Boot MVC/JDBC/Security, H2 integration tests, browser JavaScript, Node.js built-in test runner.

---

## File structure

- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java` — lifecycle, ownership, idempotency, edit-scope, and draft integration regressions.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java` — retry command and shared attempt-chain scope calculation.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeCycleService.java` — expose and enforce retry edit scope.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java` — authenticated retry endpoint.
- `frontend/tests/thin-client.test.mjs` — browser regression for the endpoint and controls.
- `frontend/app.js` — retry editor, submit routing, and error recovery.

### Task 1: Specify the backend retry lifecycle

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java`

- [ ] **Step 1: Write a failing initial-retry and idempotency test**

Chain the gateway from `IllegalStateException("stopped")` to a valid passing
assessment. Submit the initial attempt, await `UNVERIFIED`, change `question`,
and post the complete four-field body:

```java
post("/api/practice/attempts/{id}/retries", failed)
    .with(user("assessment-alice")).with(csrf())
    .contentType(MediaType.APPLICATION_JSON)
    .content(json.createObjectNode()
        .put("question", changedQuestion)
        .put("answer", originalAnswer)
        .put("reasoning", originalReasoning)
        .put("solution", originalSolution)
        .put("model", "gpt-5.6-terra[high]")
        .put("idempotencyKey", "retry-same-key").toString())
```

Assert `202`, `parentAttemptId == failed`, `attemptNumber == 2`, the changed
question, and final `PASSED`. Repeat with the same key and assert the same child
ID and exactly two attempts for the assignment.

- [ ] **Step 2: Write a failing inherited-scope test**

Make the initial response `NEEDS_REVISION` with
`fieldsToRevise=["question"]`. Submit a valid question revision whose assessment
throws, then assert the cycle returns:

```java
jsonPath("$.editor.baseAttemptId").value(failedRevision.toString())
jsonPath("$.editor.editableFields.length()").value(1)
jsonPath("$.editor.editableFields[0]").value("question")
```

Retry while changing `answer` and assert `400`. Retry while changing only
`question`, make that assessment fail again, and assert the next editor still
exposes only `question`. Retry the latest failure without changing any field and
assert `202`, proving a technical retry does not require a content change.

- [ ] **Step 3: Write failing ownership, status, freshness, and draft tests**

Add exact MockMvc assertions for these cases:

```text
Bob retries Alice's UNVERIFIED attempt                         -> 404
Alice retries a PASSED attempt                                -> 409
Alice retries a parent after its child exists with a new key  -> 409
Draft based on failed revision changes only question          -> 200
The same draft also changes answer                            -> 400
```

Use small `retryAs(...)` and `putDraft(...)` helpers that return `MvcResult`,
always send all four complete field values, and vary only the field under test.

- [ ] **Step 4: Run the focused test and verify RED**

Run: `cd backend && mvn -q -Dtest=PracticeAttemptLifecycleTest test`

Expected: FAIL because the retry route returns `404` and `UNVERIFIED` currently
has an empty `editableFields` array.

### Task 2: Implement retry as an immutable server command

**Files:**
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeCycleService.java`
- Test: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java`

- [ ] **Step 1: Add the controller contract**

Add `RetryRequest` with the same field `@Size` limits as `RevisionRequest` and:

```java
@PostMapping("/attempts/{attemptId}/retries")
@ResponseStatus(HttpStatus.ACCEPTED)
public PracticeAssessmentService.AttemptView retry(
        @PathVariable UUID attemptId,
        @Valid @RequestBody RetryRequest request) {
    return assessments.retry(auth.requireCurrentUser().id(), attemptId,
            new PracticeAssessmentService.Retry(
                    request.question(), request.answer(), request.reasoning(),
                    request.solution(), request.model(), request.idempotencyKey()));
}
```

- [ ] **Step 2: Add one shared edit-scope state machine**

In `PracticeAssessmentService`, add ordered `ALL_FIELDS` and package-visible
`editableFields(List<AttemptView> attempts)`. Use this exact state machine:

```java
if (attempts.isEmpty()) return ALL_FIELDS;
AttemptView latest = attempts.getLast();
if ("NEEDS_REVISION".equals(latest.status())) {
    return List.copyOf(latest.assessment().fieldsToRevise());
}
if (!"UNVERIFIED".equals(latest.status())) return List.of();
Map<UUID, AttemptView> byId = attempts.stream().collect(Collectors.toMap(
        AttemptView::attemptId, Function.identity()));
AttemptView cursor = latest;
while (cursor.parentAttemptId() != null) {
    cursor = byId.get(cursor.parentAttemptId());
    if (cursor == null) conflictRetryScope();
    if ("NEEDS_REVISION".equals(cursor.status())) {
        return List.copyOf(cursor.assessment().fieldsToRevise());
    }
    if (!"UNVERIFIED".equals(cursor.status())) conflictRetryScope();
}
return ALL_FIELDS;
```

`conflictRetryScope()` throws `409` with
`Не удалось восстановить область повторной проверки`. Import `Map`, `Function`,
and `Collectors`.

- [ ] **Step 3: Implement the transactional retry command**

Add a `Retry` record matching the request. In `retry(ownerId, parentAttemptId,
input)`, execute in order:

```java
practice.lockOwner(ownerId);
String key = normalize(input.idempotencyKey());
var existing = practice.findAttemptByIdempotency(ownerId, key);
if (existing.isPresent()) return view(existing.get());
var parent = practice.findAttempt(ownerId, parentAttemptId)
        .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Попытка не найдена"));
if (!"UNVERIFIED".equals(parent.status())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Повторять можно только непроверенную попытку");
}
List<AttemptView> attempts = practice.listAttempts(ownerId, parent.assignmentId())
        .stream().map(this::view).toList();
if (!attempts.getLast().attemptId().equals(parent.id())) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Повторять можно только последнюю попытку");
}
```

Resolve each nullable input field from its parent. Permit a changed value only
when `editableFields(attempts)` contains the field; otherwise return `400` with
`Поле <field> недоступно для повторной проверки`. Run existing full submission
validation. Create a child with `parent.id()`, a sorted JSON list of actually
changed fields, inherited model when the request model is null, and the key.
Delete its draft and schedule evaluation after commit. Unlike `revise`, do not
reject an empty changed-field list.

- [ ] **Step 4: Use the same scope in cycle rendering and drafts**

In `PracticeCycleService`, delegate through:

```java
private List<String> editableFields(
        List<PracticeAssessmentService.AttemptView> attempts) {
    return assessments.editableFields(attempts);
}
```

For the latest attempt, `editor(...)` returns this list for both
`NEEDS_REVISION` and `UNVERIFIED`. A draft base must equal the latest attempt ID,
whose status must be one of those states. Validate changes against
`Set.copyOf(editableFields(attempts))`; keep `400` for a blocked field and `409`
for a stale or terminal base.

- [ ] **Step 5: Run focused and related backend tests**

Run: `cd backend && mvn -q -Dtest=PracticeAttemptLifecycleTest test`

Expected: PASS.

Run: `cd backend && mvn -q -Dtest=PracticeAttemptLifecycleTest,PracticeCycleHistoryTest,PracticeAssignmentTest test`

Expected: PASS with zero failures.

- [ ] **Step 6: Commit the backend slice**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeCycleService.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java
git commit -m "feat: retry unverified practice assessments"
```

### Task 3: Expose retry in the browser

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/app.js`

- [ ] **Step 1: Write the failing browser regression**

Append:

```javascript
test('unverified practice enables an editable server retry', async () => {
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');
  const submit = app.match(/async function submitPractice\(event\)[\s\S]*?async function followAttempt/)?.[0] || '';
  const feedback = app.match(/function renderPracticeFeedback\(attempt, focus = true\)[\s\S]*?function setRevisionFields/)?.[0] || '';

  assert.match(app, /practiceEditableFields = cycle\.editor\.editableFields/);
  assert.match(app, /const retry = attempt\?\.status === 'UNVERIFIED'/);
  assert.match(submit, /`\/practice\/attempts\/\$\{attempt\.attemptId\}\/retries`/);
  assert.match(submit, /idempotencyKey\('retry'\)/);
  assert.match(app, /Повторная проверка попытки/);
  assert.match(app, /Повторить проверку →/);
  assert.match(feedback, /practice-retry[\s\S]*focusFirstRevision\(practiceEditableFields\)/);
  assert.doesNotMatch(feedback, /disabled = passed \|\| unverified/);
});
```

- [ ] **Step 2: Run the browser test and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because retry routing and active retry controls are absent.

- [ ] **Step 3: Track fields and route retry submits**

Declare `let practiceEditableFields = [];`. In `renderPracticeCycle`, assign
`practiceEditableFields = cycle.editor.editableFields` before field locks. In
`submitPractice`, use:

```javascript
const revision = attempt?.status === 'NEEDS_REVISION';
const retry = attempt?.status === 'UNVERIFIED';
const path = revision
  ? `/practice/attempts/${attempt.attemptId}/revisions`
  : retry ? `/practice/attempts/${attempt.attemptId}/retries` : '/practice/attempts';
const body = revision
  ? Object.fromEntries([...fieldsToRevise.map(field => [field, values[field]]),
      ['model', selectedModel], ['idempotencyKey', idempotencyKey('revision')]])
  : retry
    ? { ...values, model: selectedModel, idempotencyKey: idempotencyKey('retry') }
    : { assignmentId: assignment.assignmentId, ...values, model: selectedModel,
        idempotencyKey: idempotencyKey('attempt') };
```

On failure restore `practiceEditableFields` and label retry
`Повторить проверку →`; preserve primary and revision labels.

- [ ] **Step 4: Render retry as an editable mode**

Extend `setRevisionFields`:

```javascript
const retry = !locked && practiceAttempt?.status === 'UNVERIFIED' && fields.length > 0;
intro.hidden = !revision && !retry;
form.classList.toggle('is-revision', revision || retry);
if (retry) {
  $('#practice-revision-title').textContent = `Повторная проверка попытки ${practiceAttempt.attemptNumber}`;
  $('#practice-revision-fields').textContent = `Можно изменить: ${fields.map(field => FIELD_LABELS[field] || field).join(', ')}.`;
  setBusy($('#submit-practice'), false, 'Повторить проверку →');
}
```

In either mode enable only fields present in `fields`. Keep `needs-revision`
highlighting exclusive to `NEEDS_REVISION`. In feedback, label the submit and
panel action `Повторить проверку`, disable submit only for `PASSED`, and bind
`practice-retry` to `focusFirstRevision(practiceEditableFields)`.

- [ ] **Step 5: Run frontend tests**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: PASS.

Run: `node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs`

Expected: all tests pass with zero failures.

- [ ] **Step 6: Commit the frontend slice**

```bash
git add frontend/app.js frontend/tests/thin-client.test.mjs
git commit -m "fix: enable unverified practice retries"
```

### Task 4: Verify the complete change

**Files:**
- Verify: all files listed above.

- [ ] **Step 1: Run the full backend suite**

Run: `cd backend && mvn test`

Expected: `BUILD SUCCESS` with zero failures.

- [ ] **Step 2: Run all Node.js tests**

Run: `node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs`

Expected: all tests pass with zero failures.

- [ ] **Step 3: Build the backend artifact**

Run: `cd backend && mvn -DskipTests package`

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Inspect final state**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors and only intentionally uncommitted files, if any.
