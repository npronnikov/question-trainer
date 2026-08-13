# Targeted Moderation Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split moderation generation into one-at-a-time practice and trainer candidates, route published scenarios only to their intended mode, and expose a hidden generated hint in practice.

**Architecture:** Add an explicit `PRACTICE`/`TRAINER` target to candidates and scenarios, backfill all existing data as `TRAINER`, and count category cycles independently by target. Keep the existing full trainer gateway while adding a strict practice gateway that receives the server-selected category in its prompt and returns only domain, situation, and hint. Reuse one synchronized model-picker implementation across learning and moderation views.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring JDBC, Flyway, H2, JUnit 5, MockMvc, Mockito, vanilla HTML/CSS/JavaScript, Node test runner.

---

## File map

- `backend/src/main/resources/db/migration/V9__targeted_scenarios_and_practice_hints.sql` — target backfill, nullable practice fields, scenario/assignment hint storage.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioTarget.java` — two-value target enum and request parsing.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/PracticeScenarioDraft.java` — strict ACP practice payload.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioGenerationGateway.java` — trainer and practice generation ports.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGateway.java` — model validation, two prompts, strict parsing and retry.
- `backend/src/main/resources/prompts/practice-scenario-candidate-v1.md` — category-aware situation/hint generation prompt.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/ModerationRepository.java` — target-aware candidate persistence, counting, and publishing.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationService.java` — one-item target-specific generation, screening, editing, and views.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationController.java` — `{target, model}` generation request.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java` — practice-only catalog filtering and hint snapshots.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java` — assignment hint response.
- `backend/src/main/java/ru/questionhacker/trainer/trainer/TrainerRepository.java` — trainer-only scenario selection.
- `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java` — migration/backfill contract.
- `backend/src/test/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGatewayTest.java` — category-aware practice prompt and schema.
- `backend/src/test/java/ru/questionhacker/trainer/moderation/ScenarioModerationTest.java` — target cycles, screening, publication, and API contract.
- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java` — practice-only selection and hint snapshot.
- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeCycleHistoryTest.java` — update manually published practice fixtures for the target column.
- `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java` — update manually published practice fixtures for the target column.
- `backend/src/test/java/ru/questionhacker/trainer/trainer/TrainerIssuanceTest.java` — ensure practice scenarios never reach trainer issuance.
- `frontend/index.html` — second model picker, two generation buttons, no count input, hidden practice hint control.
- `frontend/app.js` — reusable picker behavior, target-aware moderation forms/generation, 50-character titles, editor clearing, hint disclosure.
- `frontend/styles.css` — responsive moderation controls, target badges, and hint disclosure styling.
- `frontend/tests/thin-client.test.mjs` — static UI and interaction-contract regressions.

### Task 1: Add target and hint persistence

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__targeted_scenarios_and_practice_hints.sql`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java`

- [ ] **Step 1: Write the failing migration contract test**

Add a test that queries `INFORMATION_SCHEMA.COLUMNS` for `CONTENT_TARGET` on `SCENARIO` and `SCENARIO_CANDIDATE`, `HINT_TEXT` on `SCENARIO` and `PRACTICE_ASSIGNMENT`, and asserts:

```java
assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM scenario WHERE content_target <> 'TRAINER'", Integer.class))
        .isZero();
assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM scenario_candidate WHERE content_target <> 'TRAINER'", Integer.class))
        .isZero();
```

- [ ] **Step 2: Run the migration test and verify RED**

Run: `cd backend && mvn -q -Dtest=MigrationTest test`

Expected: FAIL because `CONTENT_TARGET` and the new hint columns do not exist.

- [ ] **Step 3: Create the migration**

Use explicit two-value checks and keep defaults so legacy/manual inserts remain trainer material:

```sql
ALTER TABLE scenario_candidate
  ADD COLUMN content_target VARCHAR(16) NOT NULL DEFAULT 'TRAINER';
ALTER TABLE scenario_candidate
  ADD CONSTRAINT chk_candidate_target
  CHECK (content_target IN ('PRACTICE', 'TRAINER'));

ALTER TABLE scenario
  ADD COLUMN content_target VARCHAR(16) NOT NULL DEFAULT 'TRAINER';
ALTER TABLE scenario
  ADD COLUMN hint_text CLOB;
ALTER TABLE scenario ALTER COLUMN difficulty DROP NOT NULL;
ALTER TABLE scenario ALTER COLUMN question_text DROP NOT NULL;
ALTER TABLE scenario ALTER COLUMN explanation_text DROP NOT NULL;
ALTER TABLE scenario
  ADD CONSTRAINT chk_scenario_target
  CHECK (content_target IN ('PRACTICE', 'TRAINER'));

ALTER TABLE practice_assignment ADD COLUMN hint_text CLOB;
```

- [ ] **Step 4: Run the migration test and verify GREEN**

Run: `cd backend && mvn -q -Dtest=MigrationTest test`

Expected: PASS.

- [ ] **Step 5: Commit persistence changes**

```bash
git add backend/src/main/resources/db/migration/V9__targeted_scenarios_and_practice_hints.sql backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java
git commit -m "feat: add targeted scenario persistence"
```

### Task 2: Add the practice ACP contract

**Files:**
- Create: `backend/src/main/java/ru/questionhacker/trainer/moderation/PracticeScenarioDraft.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioGenerationGateway.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGateway.java`
- Create: `backend/src/main/resources/prompts/practice-scenario-candidate-v1.md`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGatewayTest.java`

- [ ] **Step 1: Write failing gateway tests**

Add tests that stub one strict object response and capture the prompt:

```java
when(acp.ask(anyString(), eq("test-model"), any())).thenReturn("""
        {"domain":"ПРОДУКТ","situation":"Команда готовит запуск и должна самостоятельно найти новый ход в достаточно подробной безопасной рабочей ситуации.","hint":"Исследуйте противоположное направление цели, не называя технику."}
        """);

PracticeScenarioDraft result = gateway.generatePractice("INVERSION", "test-model");

assertThat(result.domain()).isEqualTo("ПРОДУКТ");
assertThat(result.hint()).isNotBlank();
verify(acp).ask(prompt.capture(), eq("test-model"), any());
assertThat(prompt.getValue()).contains("INVERSION").doesNotContain("{{category}}");
```

Add a second test returning an extra `category` property and expect a `BAD_GATEWAY` `ResponseStatusException`. Keep every existing trainer-array repair/retry test.

- [ ] **Step 2: Run the gateway test and verify RED**

Run: `cd backend && mvn -q -Dtest=AcpScenarioGenerationGatewayTest test`

Expected: compilation failure because `generatePractice` and `PracticeScenarioDraft` do not exist.

- [ ] **Step 3: Add the practice payload and gateway port**

```java
public record PracticeScenarioDraft(String domain, String situation, String hint) {
}
```

```java
public interface ScenarioGenerationGateway {
    List<ScenarioDraft> generate(List<String> categories, String requestedModel);
    PracticeScenarioDraft generatePractice(String category, String requestedModel);
}
```

- [ ] **Step 4: Add the category-aware prompt and strict parser**

The new prompt must contain `{{category}}`, describe the operation represented by each canonical code, require a realistic situation suitable for the selected technique, prohibit a ready-made question/reasoning/solution/category, and require exactly `domain`, `situation`, and `hint` in one JSON object.

In `AcpScenarioGenerationGateway`, load both prompt resources. Implement:

```java
@Override
public PracticeScenarioDraft generatePractice(String category, String requestedModel) {
    String model = validatedModel(requestedModel);
    String rendered = practicePrompt.replace("{{category}}", category);
    return requestPracticeDraft(rendered, model);
}
```

`requestPracticeDraft` must retry once with an object-specific JSON instruction. Before `treeToValue`, compare the parsed field-name set to `Set.of("domain", "situation", "hint")`; any missing or extra field becomes the existing `502` upstream failure. Reuse local stray-empty-member repair without weakening the existing trainer array parser.

- [ ] **Step 5: Run the gateway test and verify GREEN**

Run: `cd backend && mvn -q -Dtest=AcpScenarioGenerationGatewayTest test`

Expected: PASS.

- [ ] **Step 6: Commit the ACP contract**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/moderation/PracticeScenarioDraft.java backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioGenerationGateway.java backend/src/main/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGateway.java backend/src/main/resources/prompts/practice-scenario-candidate-v1.md backend/src/test/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGatewayTest.java
git commit -m "feat: generate category-aware practice situations"
```

### Task 3: Make moderation target-aware and one-at-a-time

**Files:**
- Create: `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioTarget.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationController.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/ModerationRepository.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/moderation/ScenarioModerationTest.java`

- [ ] **Step 1: Rewrite generation tests for the new API**

Use `{ "target": "TRAINER", "model": "...", "count": 20 }` and assert a one-element response. Add `PRACTICE` tests using:

```java
when(generator.generatePractice(anyString(), anyString())).thenReturn(
        new PracticeScenarioDraft(
                "ПРОДУКТ",
                "Команда готовит новый рабочий процесс, но привычные решения скрывают ограничение и откладывают проверку результата.",
                "Рассмотрите противоположное направление цели, не называя саму технику."));
```

Assert `target == PRACTICE`, category is `INVERSION`, trainer-only fields do not exist in JSON, and a leaking hint produces `AUTO_REJECTED`. Generate interleaved `PRACTICE`, `TRAINER`, `PRACTICE` requests and capture calls to prove categories are `INVERSION`, `INVERSION`, `HYPERBOLE` respectively. Add bad/absent target `400` coverage.

- [ ] **Step 2: Run the moderation test and verify RED**

Run: `cd backend && mvn -q -Dtest=ScenarioModerationTest test`

Expected: FAIL because the controller still requires `count` and candidates have no target.

- [ ] **Step 3: Add target parsing and request contract**

```java
public enum ScenarioTarget {
    PRACTICE, TRAINER;

    public static ScenarioTarget parse(String value) {
        try {
            return value == null ? null : valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
```

Replace `GenerateRequest` with:

```java
public record GenerateRequest(
        @NotBlank @Size(max = 16) String target,
        @Size(max = 120) String model) {
}
```

Controller calls `moderation.generate(actorId, request.target(), request.model())`.

- [ ] **Step 4: Persist and publish targets**

Change `candidateCount()` to `candidateCount(String target)`. Add `target` to `CandidateRow`, every insert/map/snapshot/view path, and `content_target` to `publishScenario`. Publish `hint_text` with the scenario. `updateDraft` must not update `content_target`.

- [ ] **Step 5: Implement target-specific generation and screening**

`generate` parses the target, locks the sequence, computes one category from `candidateCount(target.name())`, and branches:

```java
ScenarioDraft draft = target == ScenarioTarget.PRACTICE
        ? practiceDraft(category, generator.generatePractice(category, model))
        : requireSingleTrainerDraft(category, generator.generate(List.of(category), model));
Screened screened = screen(target, draft, moderation.existingTexts());
```

`practiceDraft` sets only server category, domain, situation, and hint; trainer-only fields and `correctCategory` remain null. `screenPractice` checks category, domain/situation/hint lengths, hint leakage, unsafe content, and duplicates. `screenTrainer` preserves the current checks. Practice editing reconstructs a draft with the original server category and ignores trainer-only fields. Approval publishes no options for practice.

- [ ] **Step 6: Run moderation tests and verify GREEN**

Run: `cd backend && mvn -q -Dtest=ScenarioModerationTest,AcpScenarioGenerationGatewayTest test`

Expected: PASS.

- [ ] **Step 7: Commit moderation behavior**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioTarget.java backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationController.java backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationService.java backend/src/main/java/ru/questionhacker/trainer/moderation/ModerationRepository.java backend/src/test/java/ru/questionhacker/trainer/moderation/ScenarioModerationTest.java
git commit -m "feat: split moderation candidates by target"
```

### Task 4: Route scenarios and snapshot practice hints

**Files:**
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/trainer/TrainerRepository.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeCycleHistoryTest.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/trainer/TrainerIssuanceTest.java`

- [ ] **Step 1: Write failing catalog-boundary and hint tests**

In practice fixtures, mark intended scenarios/candidates `PRACTICE`, set `scenario.hint_text`, create an assignment, then assert `$.hint` and persisted `practice_assignment.hint_text`. Add a published trainer-only scenario for the same category and assert it is never selected by practice.

In `TrainerIssuanceTest`, insert a published `PRACTICE` scenario with no question/options and assert `/api/trainer/next` still returns a complete `TRAINER` card.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `cd backend && mvn -q -Dtest=PracticeAssignmentTest,TrainerIssuanceTest test`

Expected: FAIL because repositories do not filter targets and assignment responses have no hint.

- [ ] **Step 3: Implement practice selection and snapshots**

Add `AND s.content_target='PRACTICE'` to `selectAssignmentSource`. Select `s.hint_text`, add `hint` to `AssignmentSource` and `AssignmentRow`, and insert it into `practice_assignment.hint_text`. Select the snapshot in `findAssignment`.

Return it from the service:

```java
public record AssignmentView(
        UUID assignmentId,
        String domain,
        String situation,
        String hint,
        TargetCategory targetCategory,
        OffsetDateTime createdAt) {
}
```

- [ ] **Step 4: Implement trainer target filters**

Add `s.content_target='TRAINER'` to `selectForIssuance`, `selectWeak`, `selectReview`, and `selectCategoryCard`. Do not filter issued historical rows during answer processing.

- [ ] **Step 5: Update practice test fixtures**

Every helper that manually publishes a scenario for practice must set both `scenario.content_target` and `scenario_candidate.content_target` to `PRACTICE`. Trainer fixtures may rely on the migration default.

- [ ] **Step 6: Run all affected backend tests and verify GREEN**

Run: `cd backend && mvn -q -Dtest=PracticeAssignmentTest,PracticeCycleHistoryTest,PracticeAttemptLifecycleTest,TrainerIssuanceTest test`

Expected: PASS.

- [ ] **Step 7: Commit routing and snapshots**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java backend/src/main/java/ru/questionhacker/trainer/trainer/TrainerRepository.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeCycleHistoryTest.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java backend/src/test/java/ru/questionhacker/trainer/trainer/TrainerIssuanceTest.java
git commit -m "feat: route targeted scenarios to learning modes"
```

### Task 5: Update the moderation interface and reuse the model picker

**Files:**
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Modify: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Write failing moderation UI contract tests**

Assert the count input is absent, both button IDs and `data-generation-target` values exist, the moderation picker uses `.model-picker`, and app code sends `{ target, model: selectedModel }`. Assert a Unicode-safe helper uses `Array.from(value).slice(0, 50).join('')`, target-specific form branches exist, and successful approve calls `renderCandidateDetail()` after `selectedCandidate = null`.

- [ ] **Step 2: Run the frontend test and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL on the old count input and missing controls.

- [ ] **Step 3: Add moderation controls**

Replace the count label/button with a second instance of the existing picker markup plus:

```html
<button class="secondary-button moderation-generation-button"
        data-generation-target="PRACTICE" type="button">Для практики</button>
<button class="primary-button moderation-generation-button"
        data-generation-target="TRAINER" type="button">Для тренажёра <span>→</span></button>
```

Use class-based picker roles (`.model-trigger`, `.model-popover`, `.model-select`, `.model-mark`, `.model-name`, `.model-detail`) while retaining the original IDs needed by existing accessibility tests.

- [ ] **Step 4: Generalize the model-picker implementation**

Change picker helpers to accept a `.model-picker` root and render/bind every root. `selectedModel` remains shared, so choosing a configured model/effort in either view synchronizes all picker instances and hidden selects. `setRoute` closes all popovers; `loadSystemStatus` populates all pickers.

- [ ] **Step 5: Implement target-aware moderation rendering**

Add:

```javascript
function firstCharacters(value, limit = 50) {
  return Array.from(String(value || '')).slice(0, limit).join('');
}
```

Rows show the target and `firstCharacters(item.situation || 'Некорректный кандидат')`. Practice detail renders immutable category plus domain, situation, and hint only. `candidateDraft` preserves the selected practice category and returns null/empty trainer fields. Trainer detail keeps the full existing editor.

`generateCandidates(target)` disables both generation buttons, sends `{ target, model: selectedModel }`, restores both labels, switches to `PENDING_REVIEW`, and reloads. `loadModeration` must always call both `renderCandidateList()` and `renderCandidateDetail()`, so approve clears the editor deterministically.

- [ ] **Step 6: Add responsive styles**

Let `.moderation-generate` wrap a picker and two buttons at desktop widths and stack them below the title on narrow screens. Add a compact target label without changing the existing queue layout.

- [ ] **Step 7: Run the frontend test and verify GREEN**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: PASS.

- [ ] **Step 8: Commit moderation UI**

```bash
git add frontend/index.html frontend/app.js frontend/styles.css frontend/tests/thin-client.test.mjs
git commit -m "feat: add targeted moderation controls"
```

### Task 6: Add the hidden practice hint disclosure

**Files:**
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Modify: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Write the failing disclosure test**

Assert `practice-hint-toggle` has `aria-expanded="false"` and controls a hidden `practice-hint`; app code resets hidden state inside `renderPracticeCycle`, copies `practiceAssignment.hint`, toggles `hidden` and `aria-expanded`, and swaps `Показать подсказку`/`Скрыть подсказку`.

- [ ] **Step 2: Run the frontend test and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because the hint disclosure is absent.

- [ ] **Step 3: Add accessible hint markup**

Inside `.practice-situation`, add:

```html
<div class="practice-hint-disclosure">
  <button class="text-button" id="practice-hint-toggle" type="button"
          aria-expanded="false" aria-controls="practice-hint" hidden>Показать подсказку</button>
  <p id="practice-hint" hidden></p>
</div>
```

- [ ] **Step 4: Implement reset and toggle behavior**

`renderPracticeCycle` sets the hint text, hides both control and content when no hint exists, and otherwise shows the collapsed control. Every cycle render resets `hidden=true`, `aria-expanded=false`, and the button label. The click handler only toggles local disclosure state; it performs no mutation and does not persist visibility.

- [ ] **Step 5: Style and verify the disclosure**

Use the existing dark situation card palette, a visible focus state, and spacing that does not compete with the category guidance.

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: PASS.

- [ ] **Step 6: Commit the hint disclosure**

```bash
git add frontend/index.html frontend/app.js frontend/styles.css frontend/tests/thin-client.test.mjs
git commit -m "feat: reveal practice hints on demand"
```

### Task 7: Full verification

**Files:**
- Modify only if a scoped regression is found in files already listed above.

- [ ] **Step 1: Run all backend tests**

Run: `cd backend && mvn test`

Expected: `BUILD SUCCESS` with zero failures and errors.

- [ ] **Step 2: Run all frontend and script tests**

Run: `node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs`

Expected: all tests pass. Existing user changes in `scripts/run-local.sh` and `scripts/tests/run-local.test.mjs` remain untouched; if their test is already failing, record that separately instead of overwriting the files.

- [ ] **Step 3: Build and validate configuration**

Run: `cd backend && mvn -q -DskipTests package`

Expected: exit 0 and a bootable jar under `backend/target/`.

Run: `docker compose config`

Expected: exit 0.

- [ ] **Step 4: Inspect the final diff**

Run: `git diff --check`

Expected: no whitespace errors. `git status --short` must show only the intended feature files plus the user's pre-existing `scripts/` changes.

- [ ] **Step 5: Commit any verification-only corrections**

If verification required a correction, stage only the affected feature files and commit:

```bash
git commit -m "fix: complete targeted moderation flow"
```

If no correction was needed, do not create an empty commit.
