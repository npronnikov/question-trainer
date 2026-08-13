# Theory Worked Examples Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show one complete worked example for every theory technique and restore the researched templates, exercises, experiments, historical cases, sources, and interpretation limits that the curriculum build currently discards.

**Architecture:** Keep `backend/src/main/resources/curriculum/categories.json` as the server-owned curriculum artifact. Enrich it from the reviewed research with an idempotent build script, persist the new nested content as JSON/text columns on `category`, expose typed records from the existing category endpoint, and render the worked example inline with native disclosures for historical cases.

**Tech Stack:** Node.js curriculum tooling and structural tests, Java 21, Spring Boot 3.5, JDBC, Flyway, H2, Jackson, vanilla JavaScript, HTML, and CSS.

---

## File map

- `scripts/build-curriculum.mjs` — idempotently merges reviewed research and seven editorial worked examples into the canonical curriculum.
- `scripts/tests/curriculum-content.test.mjs` — validates rich content counts and method-specific educational invariants.
- `backend/src/main/resources/curriculum/categories.json` — generated canonical curriculum containing the rich theory fields.
- `backend/src/main/resources/db/migration/V7__theory_worked_examples.sql` — adds category persistence columns.
- `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumImporter.java` — validates and imports rich theory JSON.
- `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumRepository.java` — reads rich category columns and the evidence source registry.
- `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumService.java` — parses typed rich content and resolves case sources.
- `backend/src/test/java/ru/questionhacker/trainer/curriculum/CurriculumImporterTest.java` — verifies persistence and idempotency.
- `backend/src/test/java/ru/questionhacker/trainer/curriculum/CurriculumControllerTest.java` — verifies the public rich theory response.
- `frontend/app.js` — renders inline worked examples, templates, exercises, cases, and readable evidence labels.
- `frontend/styles.css` — supplies responsive hierarchy for the selected inline design.
- `frontend/tests/thin-client.test.mjs` — locks down the educational structure and disclosure semantics.

### Task 1: Generate complete curriculum content

**Files:**
- Create: `scripts/tests/curriculum-content.test.mjs`
- Modify: `scripts/build-curriculum.mjs`
- Modify: `backend/src/main/resources/curriculum/categories.json`

- [ ] **Step 1: Write the failing curriculum-content test**

Create a Node test that reads `categories.json` and asserts, for every category:

```js
assert.equal(category.workedExample.reasoningSteps.length >= 3, true);
assert.equal(category.workedExample.reasoningSteps.length <= 5, true);
assert.equal(category.questionTemplates.length, 5);
assert.ok(category.quickExercise.trim());
assert.ok(category.experiment.trim());
assert.equal(category.cases.length, 3);
assert.ok(category.cases.every(item => item.sourceIds.length >= 1));
```

Add method-specific assertions using the exact reasoning labels:

```js
assert.deepEqual(labels('INVERSION'), ['Нежелательный исход', 'Причины', 'Существующий сигнал', 'Защитное действие']);
assert.deepEqual(labels('HYPERBOLE'), ['Изменённый параметр', 'Что сломалось', 'Новый механизм', 'Реальные ограничения']);
assert.deepEqual(labels('CROSS_DISCIPLINE'), ['Элемент источника', 'Эквивалент в задаче', 'Существенное различие', 'Проверяемый прогноз']);
assert.deepEqual(labels('BACKCASTING'), ['2030', '2028', '2027', 'Сегодня']);
assert.deepEqual(labels('PROVOCATION'), ['Отменённое правило', 'Защищаемая функция', 'Инварианты', 'Альтернативный механизм', 'Пилот и kill switch']);
assert.deepEqual(labels('REFRAMING'), ['Исходный симптом', 'Новый outcome', 'Новое evidence', 'Новые решения']);
assert.deepEqual(labels('SIMPLIFICATION'), ['Проверенный факт', 'Ограничение', 'Защитный механизм', 'Историческая привычка', 'Минимальная пересборка']);
```

- [ ] **Step 2: Run the test and verify RED**

Run: `node --test scripts/tests/curriculum-content.test.mjs`

Expected: FAIL because `workedExample`, `questionTemplates`, `quickExercise`, `experiment`, and `cases` are absent.

- [ ] **Step 3: Replace the stale curriculum builder with an idempotent enrichment pass**

Read the current canonical category and scenario documents plus `docs/research/theory-expansion.json`. Convert research `FUTURISM` to canonical `BACKCASTING`, copy all five templates, the exercise, experiment, and all three cases, and merge a `workedExamples` constant keyed by canonical category code.

Use this merge shape:

```js
const categories = canonical.categories.map(category => {
  const researchId = category.code === 'BACKCASTING' ? 'FUTURISM' : category.code;
  const detail = researchById.get(researchId);
  return {
    ...category,
    workedExample: workedExamples[category.code],
    questionTemplates: detail.questionTemplates,
    quickExercise: detail.quickExercise,
    experiment: detail.experiment,
    cases: detail.cases
  };
});
```

Validate seven categories, 98 scenarios, three-to-five reasoning steps, five templates, and three cases before writing. Keep all existing category summaries, sections, contrasts, sources, and scenarios unchanged.

Author the seven examples exactly as specified in `docs/superpowers/specs/2026-08-13-theory-worked-examples-design.md`, using the exact labels asserted above. Every `confusion.otherCategory` must be canonical and different from the current category.

- [ ] **Step 4: Generate the canonical artifact**

Run: `node scripts/build-curriculum.mjs`

Expected: `Generated 7 enriched categories, 55 sources and preserved 98 scenarios.`

- [ ] **Step 5: Run the curriculum-content test and verify GREEN**

Run: `node --test scripts/tests/curriculum-content.test.mjs`

Expected: PASS.

### Task 2: Persist and validate rich theory

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__theory_worked_examples.sql`
- Modify: `backend/src/test/java/ru/questionhacker/trainer/curriculum/CurriculumImporterTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumImporter.java`

- [ ] **Step 1: Add failing importer assertions**

Assert that all seven rows contain rich content and that re-import remains idempotent:

```java
assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM category
        WHERE worked_example_json IS NOT NULL
          AND question_templates_json IS NOT NULL
          AND quick_exercise_text IS NOT NULL
          AND experiment_text IS NOT NULL
          AND historical_cases_json IS NOT NULL
        """, Integer.class)).isEqualTo(7);
```

- [ ] **Step 2: Run the importer test and verify RED**

Run: `cd backend && ./mvnw -q -Dtest=CurriculumImporterTest test` if `mvnw` exists; otherwise run `cd backend && mvn -q -Dtest=CurriculumImporterTest test`.

Expected: FAIL because the columns do not exist.

- [ ] **Step 3: Add the additive Flyway migration**

Create five nullable columns; the startup importer fills them transactionally:

```sql
ALTER TABLE category ADD COLUMN worked_example_json CLOB;
ALTER TABLE category ADD COLUMN question_templates_json CLOB;
ALTER TABLE category ADD COLUMN quick_exercise_text CLOB;
ALTER TABLE category ADD COLUMN experiment_text CLOB;
ALTER TABLE category ADD COLUMN historical_cases_json CLOB;
```

- [ ] **Step 4: Add rich curriculum validation**

Before any import, require:

- one non-empty worked example;
- three to five reasoning steps with non-empty `label` and `text`;
- at least two templates with non-empty `domain` and `question`;
- non-empty exercise and experiment;
- exactly three cases with all display fields populated;
- one or more source IDs per case, all present in the document source registry;
- a canonical, different confusion category.

Use small helpers such as `requireArraySize`, `requireArrayRange`, and the existing `required` method so validation errors name the category and field.

- [ ] **Step 5: Import the new fields**

Extend the category `MERGE` statement with the five columns and values:

```java
json.writeValueAsString(category.path("workedExample")),
json.writeValueAsString(category.path("questionTemplates")),
category.path("quickExercise").asText(),
category.path("experiment").asText(),
json.writeValueAsString(category.path("cases"))
```

- [ ] **Step 6: Run the importer test and verify GREEN**

Run the same targeted Maven test.

Expected: PASS.

### Task 3: Expose typed worked examples and resolved sources

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/curriculum/CurriculumControllerTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumRepository.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/curriculum/CurriculumService.java`

- [ ] **Step 1: Write failing API expectations**

Extend the inversion response test with:

```java
.andExpect(jsonPath("$.workedExample.title").isNotEmpty())
.andExpect(jsonPath("$.workedExample.reasoningSteps.length()", greaterThanOrEqualTo(3)))
.andExpect(jsonPath("$.workedExample.solution").isNotEmpty())
.andExpect(jsonPath("$.workedExample.confusion.otherCategory").value("BACKCASTING"))
.andExpect(jsonPath("$.questionTemplates.length()").value(5))
.andExpect(jsonPath("$.quickExercise").isNotEmpty())
.andExpect(jsonPath("$.experiment").isNotEmpty())
.andExpect(jsonPath("$.cases.length()").value(3))
.andExpect(jsonPath("$.cases[0].sources[0].url").isNotEmpty());
```

Add a Backcasting test asserting the labels `2030`, `2028`, `2027`, `Сегодня`.

- [ ] **Step 2: Run the controller test and verify RED**

Run the targeted `CurriculumControllerTest`.

Expected: FAIL because the response has no rich fields.

- [ ] **Step 3: Read the new category columns and source registry**

Extend `CategoryRow` with the five rich fields. Add `listEvidenceSources()` returning existing source metadata ordered by `source_key`.

- [ ] **Step 4: Add typed API records and parsing**

Add these records to `CurriculumService`:

```java
public record WorkedExample(String title, String situation, String ordinaryQuestion,
        String hackerQuestion, List<ReasoningStep> reasoningSteps, String solution,
        String whyItFits, WorkedExampleConfusion confusion) {}
public record ReasoningStep(String label, String text) {}
public record WorkedExampleConfusion(String otherCategory, String explanation) {}
public record QuestionTemplate(String domain, String question) {}
public record HistoricalCase(String slug, String title, String actor, String period,
        String originalFrame, String frameShift, String action, String outcome,
        String whyItFits, String limitations, String classification,
        List<EvidenceSource> sources) {}
```

Parse raw historical cases with a private record containing `List<String> sourceIds`, resolve every ID through a source map, and preserve source order. Add the rich fields to `CategoryDetail` after `examples` and before the legacy mistake/cue fields.

- [ ] **Step 5: Run the controller and importer tests and verify GREEN**

Run both targeted curriculum test classes.

Expected: PASS.

### Task 4: Render the selected inline lesson design

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`

- [ ] **Step 1: Add failing structural UI assertions**

Require:

```js
assert.match(app, /class="worked-example"/);
assert.match(app, /ordinaryQuestion/);
assert.match(app, /hackerQuestion/);
assert.match(app, /reasoningSteps\.map/);
assert.match(app, /questionTemplates\.slice\(0, 2\)/);
assert.match(app, /class="exercise-pair"/);
assert.match(app, /<details class="case-card"/);
assert.match(app, /item\.sources\.map/);
assert.match(css, /\.worked-questions\s*\{[^}]*grid-template-columns:\s*1fr 1fr/);
assert.match(css, /@media \(max-width:\s*720px\)[\s\S]*\.worked-questions[^{]*\{[^}]*grid-template-columns:\s*1fr/);
```

- [ ] **Step 2: Run the frontend test and verify RED**

Run: `node --test frontend/tests/thin-client.test.mjs`

Expected: FAIL because the new markup is absent.

- [ ] **Step 3: Add focused render helpers**

Extract `renderWorkedExample(category)`, `renderExercises(category)`, and `renderHistoricalCases(category)` from `renderTheoryDetail`.

`renderWorkedExample` must output, in order:

1. situation;
2. ordinary and hacker questions;
3. all labeled reasoning steps;
4. solution;
5. why-it-fits and confusion explanations;
6. exactly two templates via `category.questionTemplates.slice(0, 2)`.

`renderHistoricalCases` uses closed native `<details>` elements and outputs original frame, frame shift, action, result, why it fits, limitations, readable classification text, and every resolved source link.

Add a grade label map:

```js
const EVIDENCE_LABELS = {
  RESEARCH_SUPPORTED: 'Подтверждено исследованием',
  PRACTITIONER_METHOD: 'Практический метод',
  HEURISTIC: 'Эвристическая рекомендация'
};
```

Use it instead of exposing raw enum labels in evidence cards.

- [ ] **Step 4: Add CSS for the approved visual hierarchy**

Use existing tokens only. Add styles for:

- `.worked-example` with the three-pixel graphite top separator;
- neutral `.worked-situation` surface;
- two-column `.worked-questions` and paired `.worked-outcomes`;
- dark hacker question with acid metadata;
- adaptive `.reasoning-chain` using `repeat(auto-fit, minmax(150px, 1fr))`;
- acid solution and warning-tinted classification surfaces;
- `.question-template-list` and existing disclosure styles;
- narrow-screen single-column questions and outcomes.

Do not introduce a new accent color, shadow strategy, route, modal, or custom disclosure control.

- [ ] **Step 5: Run frontend tests and verify GREEN**

Run: `node --test frontend/tests/*.test.mjs`

Expected: all frontend tests PASS.

### Task 5: Full verification and review

**Files:**
- Inspect all modified files.

- [ ] **Step 1: Regenerate and verify curriculum determinism**

Run `node scripts/build-curriculum.mjs`, record the checksum of `categories.json`, run the builder again, and verify that the checksum is unchanged.

- [ ] **Step 2: Run all frontend tests**

Run: `node --test frontend/tests/*.test.mjs`

Expected: all tests PASS with zero failures.

- [ ] **Step 3: Run all backend tests**

Run: `cd backend && mvn test` using the available Maven wrapper only if present.

Expected: BUILD SUCCESS with zero failures and errors.

- [ ] **Step 4: Run repository hygiene checks**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended curriculum, migration, Java, frontend, test, and plan files changed.

- [ ] **Step 5: Review against all eight requested fixes**

Confirm from the final diff and API response that:

- examples show the full reasoning path and an action;
- researched material survives generation/import;
- one inline microcase exists per category;
- Backcasting is a desired-state reverse chain;
- cross-discipline has an explicit mechanism mapping;
- provocation has function, invariants, alternative, pilot, and kill switch;
- simplification classifies facts, constraints, protection, and habit;
- inversion and hyperbole both finish their method rather than stopping at the question.
