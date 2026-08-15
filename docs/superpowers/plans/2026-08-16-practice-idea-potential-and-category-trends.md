# Practice Idea Potential and Category Trends Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a saved four-axis idea-potential radar to every verified practice attempt and an on-demand, deterministic comparison of completed seven-category cycles within one selected category.

**Architecture:** Extend the existing per-attempt model assessment to schema v3, validate and persist the four raw dimensions, and compute the overall score on the server. Persist an immutable per-owner assignment sequence so the backend can select the first `VERIFIED` attempt per case and return normalized category trend points without calling AI. Add a small dependency-free frontend visualization module for accessible radar and line-chart markup, then integrate it into the existing practice feedback, attempt timeline, and a dedicated dynamics dialog.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring JDBC, Flyway, H2, Jackson, MockMvc, JUnit 5, AssertJ, vanilla JavaScript, SVG, CSS, Node test runner.

---

## File map

### Backend data and sequencing

- Create `backend/src/main/resources/db/migration/V13__practice_idea_potential_and_cycles.sql`: add stable assignment sequence/cycle columns and nullable idea-potential assessment columns; backfill existing data deterministically.
- Modify `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java`: assert the new columns, constraints, and active prompt v3.
- Create `backend/src/test/java/ru/questionhacker/trainer/PracticeIdeaPotentialMigrationTest.java`: migrate a v12 database containing multiple owners and tied timestamps, then prove owner-local sequence/cycle backfill and legacy-null assessments.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java`: derive the next sequence and category while holding the existing owner lock.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`: persist and read `sequenceNumber`, `cycleNumber`, and `cyclePosition`.
- Modify `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java`: prove canonical category order and stable cycle metadata across 14 assignments.

### Backend assessment v3

- Create `backend/src/main/java/ru/questionhacker/trainer/practice/ModelAssessmentV3.java`: v3 response record with `ideaPotential.dimensions`.
- Create `backend/src/main/java/ru/questionhacker/trainer/practice/ModelAssessmentV3Parser.java`: strict schema and four-axis validation.
- Create `backend/src/test/java/ru/questionhacker/trainer/practice/ModelAssessmentV3ParserTest.java`: parser normalization and invalid-contract coverage.
- Create `backend/src/main/resources/prompts/practice-assessment-v3.md`: the complete prompt, scale anchors, evidence requirement, and feasibility insufficient-context union.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticePromptCatalog.java`: register v3 as active and preserve v1/v2 audit rows.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java`: use v3, compute overall deterministically, persist/return the radar, and keep pass/revise rules unchanged.
- Modify `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssessmentDecisionTest.java`: prove potential scores never affect the verdict.
- Modify `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java`: verify v3 audit metadata, complete/incomplete profiles, legacy-safe error paths, and response JSON.

### Backend progress aggregation

- Create `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeIdeaProgressService.java`: turn persisted first-verified rows into completed-cycle/category points and explicit gaps.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`: add the owner-scoped first-verified progress query.
- Modify `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java`: expose `GET /api/practice/idea-progress`.
- Create `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeIdeaProgressTest.java`: integration coverage for cycle availability, revision exclusion, gaps, and ownership.

### Frontend visualization and integration

- Create `frontend/idea-potential.js`: dependency-free UMD helper for normalized dimension metadata, radar SVG, and one-metric trend SVG.
- Create `frontend/tests/idea-potential.test.mjs`: numeric geometry, missing-axis behavior, gaps, escaping, and accessible labels.
- Modify `frontend/index.html`: load the helper, add “Динамика идей”, and add the dynamics dialog shell.
- Modify `frontend/app.js`: render per-attempt radars, lazy-load progress, manage category/metric selection, and open a point’s case details.
- Modify `frontend/styles.css`: responsive journal-style radar/trend layouts, focus states, non-color status cues, and reduced motion.
- Modify `frontend/tests/thin-client.test.mjs`: structural integration tests without changing the user’s existing truncation assertions.

## Task 1: Persist stable seven-case cycle coordinates

**Files:**

- Create: `backend/src/main/resources/db/migration/V13__practice_idea_potential_and_cycles.sql`
- Create: `backend/src/test/java/ru/questionhacker/trainer/PracticeIdeaPotentialMigrationTest.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java`

- [ ] **Step 1: Write the failing migration tests**

In `MigrationTest`, assert that `PRACTICE_ASSIGNMENT` has non-null `SEQUENCE_NUMBER`, `CYCLE_NUMBER`, `CYCLE_POSITION`, and `PRACTICE_ASSESSMENT` has nullable `IDEA_POTENTIAL_SCORE`, `IDEA_POTENTIAL_DIMENSIONS_JSON`.

In `PracticeIdeaPotentialMigrationTest`, migrate only through v12, insert two owners’ assignments with deliberately equal `created_at`, insert one legacy v2 assessment, then migrate through latest and assert:

```java
assertThat(rowsForAlice).extracting("SEQUENCE_NUMBER")
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
assertThat(rowsForAlice).extracting("CYCLE_NUMBER")
        .containsExactly(1, 1, 1, 1, 1, 1, 1, 2);
assertThat(rowsForAlice).extracting("CYCLE_POSITION")
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 1);
assertThat(legacyPotentialScore).isNull();
```

Also attempt a duplicate `(owner_id, sequence_number)` and expect a data-integrity error.

- [ ] **Step 2: Run the migration tests and confirm they fail**

Run: `cd backend && mvn -q -Dtest=MigrationTest,PracticeIdeaPotentialMigrationTest test`

Expected: failure because v13 and the new columns do not exist.

- [ ] **Step 3: Add the v13 migration**

The migration must:

1. Add the three assignment columns as nullable.
2. Build a temporary backfill table with `ROW_NUMBER() OVER (PARTITION BY owner_id ORDER BY created_at, id)`.
3. Set:

```sql
sequence_number = row_number,
cycle_number = FLOOR((row_number - 1) / 7.0) + 1,
cycle_position = MOD(row_number - 1, 7) + 1
```

4. Drop the temporary table and make all three columns non-null.
5. Add `UNIQUE(owner_id, sequence_number)` plus checks for positive sequence/cycle, position `1..7`, and consistency between the three values.
6. Add nullable `idea_potential_score DECIMAL(3,2)` and `idea_potential_dimensions_json CLOB` with a score check of `0..4`.

Do not backfill legacy assessments with invented values.

- [ ] **Step 4: Run the migration tests and confirm they pass**

Run: `cd backend && mvn -q -Dtest=MigrationTest,PracticeIdeaPotentialMigrationTest test`

Expected: both test classes pass.

- [ ] **Step 5: Commit the migration slice**

```bash
git add backend/src/main/resources/db/migration/V13__practice_idea_potential_and_cycles.sql backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java backend/src/test/java/ru/questionhacker/trainer/PracticeIdeaPotentialMigrationTest.java
git commit -m "feat: persist practice cycle coordinates"
```

## Task 2: Assign sequence, cycle, and canonical category atomically

**Files:**

- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java`

- [ ] **Step 1: Add a failing 14-assignment test**

Publish at least two practice scenarios for every category, create 14 assignments for one user, and assert this exact category order twice:

```java
List.of("INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
        "PROVOCATION", "REFRAMING", "SIMPLIFICATION")
```

Assert `sequenceNumber=1..14`, `cycleNumber=1` then `2`, and `cyclePosition=1..7` in both API and database. Create one assignment for a second user and assert its sequence starts at 1.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `cd backend && mvn -q -Dtest=PracticeAssignmentTest test`

Expected: new cycle fields are absent from persistence/API.

- [ ] **Step 3: Implement atomic assignment metadata**

Keep `practice.lockOwner(ownerId)` as the serialization boundary. Replace the ambiguous count-only API with a repository method that returns `nextSequence = max(sequence_number) + 1` under that lock. Compute:

```java
long sequence = practice.nextAssignmentSequence(ownerId);
int cycleNumber = Math.toIntExact(((sequence - 1) / 7) + 1);
int cyclePosition = Math.toIntExact(((sequence - 1) % 7) + 1);
String category = categories.get(cyclePosition - 1);
```

Pass all three fields to `createAssignment`, select them in `findAssignment` and `listCycles`, and expose them in `AssignmentView`. Do not recompute historical rows from current row counts during reads.

- [ ] **Step 4: Run assignment and history tests**

Run: `cd backend && mvn -q -Dtest=PracticeAssignmentTest,PracticeCycleHistoryTest test`

Expected: pass with existing history behavior unchanged.

- [ ] **Step 5: Commit the sequencing slice**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java
git commit -m "feat: assign canonical practice cycles"
```

## Task 3: Define and validate assessment schema v3

**Files:**

- Create: `backend/src/main/java/ru/questionhacker/trainer/practice/ModelAssessmentV3.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/practice/ModelAssessmentV3Parser.java`
- Create: `backend/src/test/java/ru/questionhacker/trainer/practice/ModelAssessmentV3ParserTest.java`

- [ ] **Step 1: Write failing parser tests**

Cover a complete profile and the only allowed missing-score form:

```json
{"name":"feasibility","status":"INSUFFICIENT_CONTEXT","score":null,"evidence":"В кейсе не заданы ресурсы и ограничения"}
```

Add parameterized invalid cases for:

- schema other than `practice-assessment-v3`;
- missing, duplicate, or unknown dimension;
- `SCORED` with null or out-of-range/non-integer score;
- `INSUFFICIENT_CONTEXT` on a dimension other than `feasibility`;
- `INSUFFICIENT_CONTEXT` with a non-null score;
- blank evidence;
- any unknown JSON property.

Retain the existing chain, category-fit, question-strength, confidence, bounded-list, and correction validations from v2.

- [ ] **Step 2: Run the parser test and confirm it fails**

Run: `cd backend && mvn -q -Dtest=ModelAssessmentV3ParserTest test`

Expected: compilation failure because v3 types do not exist.

- [ ] **Step 3: Implement the v3 record and strict parser**

Use the existing v2 nested records for unchanged fields and add:

```java
public record IdeaPotential(List<IdeaDimension> dimensions) {}

public record IdeaDimension(
        String name,
        String status,
        Integer score,
        String evidence) {}
```

The parser constant is `practice-assessment-v3`. Require exactly the names `impact`, `questionAlignment`, `disruption`, `feasibility`; status is `SCORED` except the feasibility union above. Continue deriving `questionStrength.score` from its boolean dimensions so the model cannot forge the total.

- [ ] **Step 4: Run v2 and v3 parser tests**

Run: `cd backend && mvn -q -Dtest=ModelAssessmentV2ParserTest,ModelAssessmentV3ParserTest test`

Expected: both pass; legacy parser remains available for old-contract regression tests.

- [ ] **Step 5: Commit the schema slice**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice/ModelAssessmentV3.java backend/src/main/java/ru/questionhacker/trainer/practice/ModelAssessmentV3Parser.java backend/src/test/java/ru/questionhacker/trainer/practice/ModelAssessmentV3ParserTest.java
git commit -m "feat: validate practice idea potential"
```

## Task 4: Activate the v3 per-attempt prompt

**Files:**

- Create: `backend/src/main/resources/prompts/practice-assessment-v3.md`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticePromptCatalog.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java`
- Create: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticePromptCatalogTest.java`

- [ ] **Step 1: Write failing prompt-catalog tests**

Assert versions 1, 2, and 3 are present, only v3 is active, and `render()` contains the supplied situation/category/guidance/question/rationale/solution plus every axis name and `INSUFFICIENT_CONTEXT`. Assert the prompt explicitly says the model must not return `overallScore` and that idea potential does not decide `PASSED`/`NEEDS_REVISION`.

- [ ] **Step 2: Run and confirm the tests fail**

Run: `cd backend && mvn -q -Dtest=MigrationTest,PracticePromptCatalogTest test`

Expected: prompt version 2 is still active and v3 is missing.

- [ ] **Step 3: Write the complete v3 prompt**

Copy the unchanged semantic checks from v2, remove the obsolete instruction that objective idea value must not be assessed, and add the approved Russian definitions and anchors `0..4` for all four axes. Require evidence from the submitted text; prohibit external assumptions and comparisons with other users. Permit missing context only for feasibility. Include one exact JSON object example matching `ModelAssessmentV3` and no markdown fences in the model response.

Update `PracticePromptCatalog.PROMPT_VERSION` to `3`, read all three resources, merge v1/v2 as inactive and v3 as active, and use `ModelAssessmentV3Parser.SCHEMA_VERSION` for v3.

- [ ] **Step 4: Run prompt and migration tests**

Run: `cd backend && mvn -q -Dtest=MigrationTest,PracticePromptCatalogTest test`

Expected: pass with exactly one active prompt row.

- [ ] **Step 5: Commit the prompt slice**

```bash
git add backend/src/main/resources/prompts/practice-assessment-v3.md backend/src/main/java/ru/questionhacker/trainer/practice/PracticePromptCatalog.java backend/src/test/java/ru/questionhacker/trainer/MigrationTest.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticePromptCatalogTest.java
git commit -m "feat: activate practice assessment v3"
```

## Task 5: Persist and expose every attempt radar without changing verdicts

**Files:**

- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssessmentDecisionTest.java`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java`

- [ ] **Step 1: Convert lifecycle fixtures to v3 and add failing assertions**

Make `validAssessment(...)` include four dimensions. In the passing test assert:

```java
assertThat(terminal.at("/assessment/ideaPotential/overallScore").decimalValue())
        .isEqualByComparingTo("3.00");
assertThat(terminal.at("/assessment/ideaPotential/dimensions")).hasSize(4);
```

Assert database JSON retains all evidence and `idea_potential_score` equals the server mean. Add a feasibility-insufficient test asserting `complete=false`, `overallScore=null`, and the other three scores remain visible. Assert `UNVERIFIED` and legacy v1/v2 attempts have `ideaPotential=null`.

In `PracticeAssessmentDecisionTest`, create two otherwise identical v3 assessments with all idea scores 0 and all 4; assert both produce the same decision.

- [ ] **Step 2: Run focused tests and confirm they fail**

Run: `cd backend && mvn -q -Dtest=PracticeAssessmentDecisionTest,PracticeAttemptLifecycleTest test`

Expected: service still expects v2 and no potential is persisted or returned.

- [ ] **Step 3: Switch evaluation to v3 and compute the deterministic mean**

Inject `ModelAssessmentV3Parser`, parse v3, and keep `decide()` based only on chain/category fit/question strength/confidence. Compute the score only when all four dimensions are `SCORED`:

```java
BigDecimal overall = scores.size() == 4
        ? BigDecimal.valueOf(scores.stream().mapToInt(Integer::intValue).sum())
            .divide(BigDecimal.valueOf(4), 2, RoundingMode.UNNECESSARY)
        : null;
```

Persist the original normalized dimension JSON and the computed score in `AssessmentRow`. Extend `queryAttempts`, `AttemptRow`, and `AssessmentView` with:

```java
public record IdeaPotentialView(
        List<ModelAssessmentV3.IdeaDimension> dimensions,
        BigDecimal overallScore,
        boolean complete) {}
```

Return `null` for legacy schema rows or missing persisted JSON. Save `null` values on both unverified paths. Do not round dimension scores or create zero for missing feasibility.

- [ ] **Step 4: Run assessment, retry, and history tests**

Run: `cd backend && mvn -q -Dtest=PracticeAssessmentDecisionTest,PracticeAttemptLifecycleTest,PracticeCycleHistoryTest test`

Expected: pass; every verified revision has its own immutable radar and existing revision behavior is unchanged.

- [ ] **Step 5: Commit the assessment slice**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssessmentService.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssessmentDecisionTest.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java
git commit -m "feat: save practice attempt radars"
```

## Task 6: Aggregate first-verified category points with explicit gaps

**Files:**

- Create: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeIdeaProgressService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java`
- Create: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeIdeaProgressTest.java`

- [ ] **Step 1: Write failing owner-scoped endpoint tests**

Seed two full cycles for Alice and one for Bob. In Alice’s first inversion case create attempt 1 as `NEEDS_REVISION/VERIFIED` and attempt 2 as `PASSED/VERIFIED` with higher scores. Assert the cycle-1 inversion point uses attempt 1. Assert Bob’s IDs never appear.

Cover these endpoint states:

- zero assignments: `lastStartedCycle=0`, `lastAnsweredCycle=0`, comparison unavailable;
- seven assignments but one without a verified attempt: cycle 1 is not answered and all its chart points are `CYCLE_INCOMPLETE`;
- seven verified assignments: cycle 1 stored, comparison unavailable;
- fourteen verified assignments: cycles 1 and 2 returned, comparison available;
- first verified assessment has legacy schema: `LEGACY_SCHEMA` gap;
- v3 feasibility missing: `INCOMPLETE_PROFILE` gap for overall but its scored axes remain in point details;
- unverified-only case: `NOT_VERIFIED` and no fabricated score.

- [ ] **Step 2: Run the endpoint test and confirm it fails**

Run: `cd backend && mvn -q -Dtest=PracticeIdeaProgressTest test`

Expected: 404 because `/api/practice/idea-progress` does not exist.

- [ ] **Step 3: Add the repository projection**

Query only assignments owned by the authenticated user. For every assignment, left join the earliest attempt whose persisted assessment has `outcome='VERIFIED'`, ordered by `attempt_number`, without requiring attempt status `PASSED`. Return assignment cycle metadata, category metadata, first verified attempt identifiers/date, schema version, dimensions JSON, overall score, prompt version, and model ID.

Do not use the latest attempt and do not invoke `PracticeAssessmentGateway`.

- [ ] **Step 4: Implement deterministic aggregation**

`PracticeIdeaProgressService.get(ownerId)` must:

1. Determine `lastStartedCycle` from assignments.
2. Mark a cycle answered only when positions `1..7` each have a first verified attempt.
3. Set `lastAnsweredCycle` to the highest contiguous answered cycle.
4. Set `comparisonAvailable = lastAnsweredCycle >= 2`.
5. Return seven categories in repository canonical order.
6. Emit one slot per started cycle/category, carrying exact identifiers and dimensions when available plus one of `CYCLE_INCOMPLETE`, `NOT_STARTED`, `NOT_VERIFIED`, `LEGACY_SCHEMA`, `INCOMPLETE_PROFILE`, or no gap.
7. Never connect or interpolate missing points.

Add `GET /api/practice/idea-progress` to `PracticeController`; obtain `ownerId` only from `auth.requireCurrentUser()` and accept no owner query parameter.

- [ ] **Step 5: Run progress and controller regressions**

Run: `cd backend && mvn -q -Dtest=PracticeIdeaProgressTest,PracticeCycleHistoryTest,PracticeAttemptLifecycleTest test`

Expected: pass; repeated GET requests leave assessment counts unchanged.

- [ ] **Step 6: Commit the aggregation slice**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/practice/PracticeIdeaProgressService.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeIdeaProgressTest.java
git commit -m "feat: expose category idea trends"
```

## Task 7: Build tested accessible radar and trend SVG helpers

**Files:**

- Create: `frontend/idea-potential.js`
- Create: `frontend/tests/idea-potential.test.mjs`

- [ ] **Step 1: Write failing pure-module tests**

Use the same UMD/CommonJS pattern as `practice-retry.js`. Import and test:

```js
const {
  DIMENSIONS,
  radarMarkup,
  trendMarkup,
  metricValue
} = require('../idea-potential.js');
```

Assert:

- `DIMENSIONS` is ordered as impact, questionAlignment, disruption, feasibility with Russian labels;
- radar points for scores `0..4` remain inside the viewBox and include text values;
- missing feasibility creates no zero-valued vertex and says “Недостаточно данных”;
- a complete radar announces its overall score; an incomplete radar does not;
- `metricValue(point, 'overall')` and each dimension use only server values;
- trend markup produces breaks between gaps rather than one continuous path;
- point groups are keyboard-focusable and have category/cycle/value/date accessible names;
- supplied labels/evidence cannot inject HTML/SVG.

- [ ] **Step 2: Run and confirm the module test fails**

Run: `node --test frontend/tests/idea-potential.test.mjs`

Expected: module-not-found failure.

- [ ] **Step 3: Implement the dependency-free renderer**

Expose frozen metadata plus pure functions. Use a fixed `viewBox`, four radial axes, five grid levels (`0..4`), visible labels, and an offscreen/text alternative. Build the trend as separate path segments split at every absent value. Render point `<g>` elements with `tabindex="0"`, `role="button"`, and `data-assignment-id`/`data-attempt-id`; support activation in `app.js` via click, Enter, and Space.

All dynamic text must pass through a local XML escape function. The helper must never fetch data, select the first revision, calculate cycle completion, or infer an overall score.

- [ ] **Step 4: Run helper tests and syntax check**

Run: `node --test frontend/tests/idea-potential.test.mjs && node --check frontend/idea-potential.js`

Expected: pass.

- [ ] **Step 5: Commit the visualization helper**

```bash
git add frontend/idea-potential.js frontend/tests/idea-potential.test.mjs
git commit -m "feat: render accessible idea charts"
```

## Task 8: Show a radar for the current and historical attempts

**Files:**

- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Modify: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Add failing frontend integration assertions**

Append tests without rewriting the existing `firstCharacters()`/moderation truncation fixture. Assert:

- `index.html` loads `idea-potential.js` before `app.js`;
- `app.js` calls the helper only when `assessment.ideaPotential` exists;
- the current feedback contains the radar and four evidence rows;
- each verified timeline attempt has an accessible disclosure for its saved radar;
- `EVALUATING` and `UNVERIFIED` do not render numeric potential;
- legacy attempts show “Оценка потенциала ещё не выполнялась”.

- [ ] **Step 2: Run and confirm the structural test fails**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: missing script and radar integration assertions fail.

- [ ] **Step 3: Integrate per-attempt radars**

Load `idea-potential.js` before `app.js` and bind `const ideaPotential = window.QH_IDEA_POTENTIAL`. Extend `renderPracticeFeedback()` with a `<section class="idea-radar-panel">` containing the SVG, nullable overall label, completeness message, and four evidence rows.

Extend `renderPracticeTimeline()` so every terminal `VERIFIED` assessment gets a `<details>` disclosure labeled `Паутинка попытки N`; current/latest can be open and older attempts collapsed. Keep all previous attempt scores in the DOM/history response—never replace them with the latest score.

For missing states:

- `UNVERIFIED`: retain the current “server did not invent a score” copy and no radar;
- legacy verified: neutral no-score note;
- missing feasibility: show the three known numbers, explicit gap, and no overall.

- [ ] **Step 4: Add responsive and accessible styling**

Use the existing paper/ink/accent tokens. Keep numeric labels visible in addition to color, give disclosures and SVG point groups visible `:focus-visible`, stack evidence below the chart under the existing mobile breakpoint, and disable chart transition/animation under `prefers-reduced-motion: reduce`.

- [ ] **Step 5: Run frontend tests and syntax checks**

Run: `node --test frontend/tests/idea-potential.test.mjs frontend/tests/thin-client.test.mjs && node --check frontend/app.js && node --check frontend/idea-potential.js`

Expected: pass.

- [ ] **Step 6: Commit the attempt-radar UI**

Before staging, run `git diff -- frontend/app.js frontend/tests/thin-client.test.mjs` and preserve the user’s pre-existing truncation edits. Stage only this feature’s hunks if necessary.

```bash
git add frontend/index.html frontend/styles.css frontend/idea-potential.js frontend/tests/idea-potential.test.mjs
git add -p frontend/app.js frontend/tests/thin-client.test.mjs
git commit -m "feat: show idea radar per practice attempt"
```

## Task 9: Add the on-demand category dynamics dialog

**Files:**

- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/tests/idea-potential.test.mjs`

- [ ] **Step 1: Add failing behavior-contract tests**

Assert the UI has:

- an always-available `Динамика идей` button with `aria-haspopup="dialog"`;
- lazy `api('/practice/idea-progress')` loading only when opened or explicitly retried;
- seven category controls and one metric selection at a time (`overall` plus four axes);
- the three availability messages: remaining categories before cycle 1, cycle 1 saved/waiting for cycle 2, comparison available from cycle 2;
- local retry after endpoint failure without hiding practice history/form;
- click/Enter/Space point activation that loads the matching `/practice/cycles/{assignmentId}` and opens the exact first-verified attempt’s radar/evidence;
- no frontend code that averages scores, chooses the latest revision, or calls an AI endpoint for trends.

- [ ] **Step 2: Run and confirm the integration tests fail**

Run: `node --test frontend/tests/idea-potential.test.mjs frontend/tests/thin-client.test.mjs`

Expected: dialog controls and lazy progress load are absent.

- [ ] **Step 3: Add the dialog shell**

Place `Динамика идей` next to `Новая ситуация`. Add a labeled `<dialog id="idea-progress-dialog">` with status/empty/error region, category tabs, a native metric `<select>`, chart region, point detail region, retry, and close controls. Keep the practice workspace outside the dialog and unaffected by dialog loading errors.

- [ ] **Step 4: Implement dialog state and rendering**

Add frontend state for cached progress, loading/error, selected category, selected metric, and selected point. On open, fetch once; on retry, refetch. Render only the chosen category and metric. Use backend `comparisonAvailable` and gap reasons verbatim through a Russian label map.

When a point is activated, render its stored situation summary, exact radar, evidence, cycle/date, and an action to open the owning case with `selectPracticeCycle(point.assignmentId)`. Match `point.attemptId` in the returned case; do not substitute `attempts.at(-1)` for point details.

After a newly terminal verified attempt, invalidate the cached progress so reopening reads the new server state.

- [ ] **Step 5: Style and verify all states**

Keep the graph Y-axis fixed at `0..4`, X-axis as explicit cycle numbers, and break the line at gaps. Ensure no horizontal page scroll at 360px, dialog focus remains trapped by the native element, point details are readable without hover, and close restores focus to `Динамика идей`.

Run: `node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs && node --check frontend/app.js && node --check frontend/idea-potential.js`

Expected: all frontend and content tests pass.

- [ ] **Step 6: Commit the dynamics UI**

```bash
git add frontend/index.html frontend/styles.css frontend/idea-potential.js frontend/tests/idea-potential.test.mjs
git add -p frontend/app.js frontend/tests/thin-client.test.mjs
git commit -m "feat: compare idea potential by category"
```

## Task 10: Full verification and visual QA

**Files:**

- Modify only if a scoped defect is found: files listed above
- Include in final feature commit if not already committed: `docs/superpowers/specs/2026-08-16-practice-idea-potential-and-category-trends-design.md`, `docs/superpowers/plans/2026-08-16-practice-idea-potential-and-category-trends.md`

- [ ] **Step 1: Run the complete automated suite**

```bash
cd backend && mvn test
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
node --check frontend/app.js
node --check frontend/idea-potential.js
git diff --check
```

Expected: every command exits 0 and no whitespace errors are reported.

- [ ] **Step 2: Run targeted database invariants**

Using the test suite or local database, confirm:

- one owner cannot have duplicate sequence numbers;
- assignments 1/8 have category `INVERSION`, cycle positions 1, and cycles 1/2;
- prompt v3 is the only active version;
- opening progress repeatedly creates no attempts or assessments;
- the first verified attempt ID remains the progress point after a later revision;
- incomplete feasibility remains null overall in DB and API.

- [ ] **Step 3: Perform browser QA at desktop and mobile widths**

Test these flows in the local app:

1. New attempt → evaluating → verified complete radar.
2. `NEEDS_REVISION` → revision → both radars remain available in timeline.
3. Feasibility insufficient → visible missing-data gap and no overall.
4. Legacy/unverified attempt → no fabricated chart.
5. 0, 6, 7, 13, 14, and 21 answered cases → correct availability copy.
6. Category and metric switching → only one line, fixed `0..4` scale.
7. Gap point → broken line and reason.
8. Mouse and keyboard point activation → exact attempt details.
9. Endpoint error → local retry while practice form/history still work.
10. 360px width and reduced motion → readable labels, no page overflow or moving transitions.

- [ ] **Step 4: Review scope and preserve unrelated work**

Run `git status --short` and inspect all diffs. Confirm the existing user-owned `firstCharacters()` truncation change and its test are preserved and not accidentally included in a feature commit unless the user requests it. Confirm there is no cycle-level AI prompt, no automatic historical re-evaluation, no leaderboard/streak, and no verdict dependency on idea potential.

- [ ] **Step 5: Commit any final scoped fixes and documentation**

```bash
git add docs/superpowers/plans/2026-08-16-practice-idea-potential-and-category-trends.md
git add <only-scoped-fix-files>
git commit -m "test: verify practice idea trends"
```

Skip the final commit when there are no uncommitted scoped changes.

## Acceptance checklist

- [ ] Every new `VERIFIED` attempt has exactly four persisted idea dimensions with evidence.
- [ ] Only feasibility can be `INSUFFICIENT_CONTEXT`; it remains null rather than zero.
- [ ] Overall is the server-computed arithmetic mean and is null for any incomplete profile.
- [ ] Idea potential does not change `PASSED`, `NEEDS_REVISION`, revision fields, or retry behavior.
- [ ] All revision radars remain visible within the case timeline.
- [ ] Assignment sequence/cycle/category is stable, owner-local, atomic, and canonical.
- [ ] A cycle is answered only after all seven cases have a `VERIFIED` attempt.
- [ ] Long-term points use the first `VERIFIED` attempt, even when it is `NEEDS_REVISION`.
- [ ] Cycle 1 can be inspected but comparison becomes available only from cycle 2.
- [ ] Dynamics is grouped by selected category and shows overall or one selected axis, never five concurrent lines.
- [ ] Gaps, legacy data, unverified data, and incomplete feasibility are explicit and never interpolated.
- [ ] Progress reads stored data only and makes no AI call.
- [ ] Charts have text values, keyboard access, focus states, mobile layout, and reduced-motion behavior.
- [ ] Existing unrelated user changes remain intact.
