# Moderated Practice Catalog Cycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make admin ACP generation follow the seven-category cycle and make each user receive only moderated, non-repeating scenarios in the same personal cycle after passing the current assignment.

**Architecture:** The moderation service owns a database-locked global generation position and passes an exact category list to a dedicated ACP prompt. The practice service locks the current user, derives the personal category from persisted assignments, rejects unfinished cycles, and selects only scenarios linked to published moderation candidates. The frontend mirrors backend availability but treats backend domain codes as authoritative.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring JDBC transactions, H2/Flyway, JUnit 5, Mockito, MockMvc, vanilla JavaScript, Node.js test runner.

---

## File structure

- `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioGenerationGateway.java` — category-aware ACP generation contract.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGateway.java` — prompt rendering, ACP invocation, JSON/category validation.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationService.java` — global category allocation and candidate screening.
- `backend/src/main/java/ru/questionhacker/trainer/moderation/ModerationRepository.java` — generation-sequence lock, candidate count, ordered category query.
- `backend/src/main/resources/prompts/scenario-candidates-cycled-v1.md` — exact ordered-category prompt.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentUnavailableException.java` — machine-readable assignment failure.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java` — personal cycle and unfinished-cycle rule.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java` — owner lock and moderated, unconsumed source selection.
- `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java` — server-owned category API contract.
- `backend/src/main/java/ru/questionhacker/trainer/ApiExceptionHandler.java` — Problem Detail `code` mapping.
- `frontend/index.html`, `frontend/app.js`, `frontend/styles.css` — disabled controls and catalog-exhausted feedback.
- Backend and frontend test files listed in each task — regression coverage before implementation.

### Task 1: Allocate canonical categories for administrative generation

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/moderation/ScenarioModerationTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioGenerationGateway.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/ModerationRepository.java`

- [ ] **Step 1: Write a failing category-cycle test**

Add Mockito imports for `ArgumentCaptor`, `anyList`, `times`, and `verify`. Replace existing `generate(anyInt(), anyString())` stubs with category-aware stubs, then add this test:

```java
@Test
void generationContinuesCanonicalCategoryCycleAcrossSavedCandidates() throws Exception {
    when(generator.generate(anyList(), anyString())).thenAnswer(invocation -> {
        List<String> categories = invocation.getArgument(0);
        return categories.stream().map(this::goodDraftForCategory).toList();
    });

    mvc.perform(post("/api/admin/scenario-candidates/generate")
                    .with(user("queue-admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"count\":8,\"model\":\"gpt-5.6-terra[high]\"}"))
            .andExpect(status().isAccepted());
    jdbc.update("UPDATE scenario_candidate SET status='REJECTED' WHERE category_code='INVERSION'");

    mvc.perform(post("/api/admin/scenario-candidates/generate")
                    .with(user("queue-admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"count\":1,\"model\":\"gpt-5.6-terra[high]\"}"))
            .andExpect(status().isAccepted());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> categories = ArgumentCaptor.forClass(List.class);
    verify(generator, times(2)).generate(categories.capture(), anyString());
    assertThat(categories.getAllValues().get(0)).containsExactly(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION", "INVERSION");
    assertThat(categories.getAllValues().get(1)).containsExactly("HYPERBOLE");
}
```

Add a category-aware helper whose `options` always include the correct category:

```java
private ScenarioDraft goodDraftForCategory(String category) {
    List<String> options = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(category),
                    java.util.stream.Stream.of("INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE",
                            "BACKCASTING", "PROVOCATION", "REFRAMING", "SIMPLIFICATION")
                            .filter(item -> !item.equals(category)))
            .limit(4).toList();
    return new ScenarioDraft(category, null, "L2", "ПРОДУКТ",
            "Команда готовит новый рабочий процесс, но привычные решения скрывают главное ограничение и откладывают проверку результата.",
            "Какой вопрос поможет изменить рамку и обнаружить новый проверяемый ход?",
            "Ищите одну основную мыслительную операцию, не называя её в подсказке.",
            options, category,
            "Вопрос применяет одну заданную операцию и открывает новый класс проверяемых решений.",
            null, null);
}
```

- [ ] **Step 2: Run the moderation test and verify RED**

Run from `backend/`:

```bash
mvn -Dtest=ScenarioModerationTest test
```

Expected: test compilation fails because `ScenarioGenerationGateway` still accepts `int`, proving the new category-aware contract is absent.

- [ ] **Step 3: Implement the category-aware contract and database sequence lock**

Change the gateway contract to:

```java
public interface ScenarioGenerationGateway {
    List<ScenarioDraft> generate(List<String> categories, String requestedModel);
}
```

Add these repository methods:

```java
public void lockGenerationSequence() {
    jdbc.queryForObject("""
            SELECT code FROM category
            ORDER BY sort_order
            LIMIT 1 FOR UPDATE
            """, String.class);
}

public List<String> categoryCodes() {
    return jdbc.queryForList("SELECT code FROM category ORDER BY sort_order", String.class);
}

public long candidateCount() {
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM scenario_candidate", Long.class);
    return count == null ? 0L : count;
}
```

At the start of `ScenarioModerationService.generate`, after validating `count`, lock the sequence and allocate categories before calling ACP:

```java
moderation.lockGenerationSequence();
List<String> categoryOrder = moderation.categoryCodes();
if (categoryOrder.isEmpty()) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Категории программы не загружены");
}
long start = moderation.candidateCount();
List<String> requestedCategories = java.util.stream.IntStream.range(0, count)
        .mapToObj(index -> categoryOrder.get((int) ((start + index) % categoryOrder.size())))
        .toList();
List<ScenarioDraft> drafts = generator.generate(requestedCategories, model);
```

Keep the existing size check, automatic screening, inserts, and moderation audit. Because the category row is selected `FOR UPDATE` inside the existing transaction, another generator waits until candidate inserts commit.

- [ ] **Step 4: Run the moderation test and verify the service portion is GREEN**

Run:

```bash
mvn -Dtest=ScenarioModerationTest test
```

Expected: compilation now reaches `AcpScenarioGenerationGateway`, which still has the old method signature. Use this bridge implementation so the service tests compile while Task 2 can still expose the missing ordered prompt:

```java
@Override
public List<ScenarioDraft> generate(List<String> categories, String requestedModel) {
    String model = requestedModel == null || requestedModel.isBlank()
            ? properties.acp().defaultModel() : requestedModel.strip();
    if (model != null && !model.isBlank() && !properties.acp().models().contains(model)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестная модель: " + model);
    }
    try {
        String rendered = prompt.replace("{{count}}", Integer.toString(categories.size()));
        String raw = acp.ask(rendered, model, ignored -> { });
        if (!raw.strip().startsWith("[") || !raw.strip().endsWith("]")) {
            throw new IllegalArgumentException("Generator must return one JSON array");
        }
        List<ScenarioDraft> drafts = json.readValue(raw.strip(), new TypeReference<>() { });
        if (drafts.size() != categories.size()) {
            throw new IllegalArgumentException("Generator returned wrong count");
        }
        return drafts;
    } catch (IOException error) {
        throw new IllegalArgumentException("Invalid generator JSON", error);
    }
}
```

Rerun and expect all `ScenarioModerationTest` methods to pass.

- [ ] **Step 5: Commit the category allocation**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioGenerationGateway.java backend/src/main/java/ru/questionhacker/trainer/moderation/ScenarioModerationService.java backend/src/main/java/ru/questionhacker/trainer/moderation/ModerationRepository.java backend/src/main/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGateway.java backend/src/test/java/ru/questionhacker/trainer/moderation/ScenarioModerationTest.java
git commit -m "feat: cycle moderation generation categories"
```

### Task 2: Render and validate the ordered ACP prompt

**Files:**
- Create: `backend/src/test/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGatewayTest.java`
- Create: `backend/src/main/resources/prompts/scenario-candidates-cycled-v1.md`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGateway.java`

- [ ] **Step 1: Write failing prompt and mismatch tests**

Create a unit test that mocks `AcpGateway`, uses an `AppProperties` instance with the requested model, and captures the prompt:

```java
class AcpScenarioGenerationGatewayTest {
    private final AcpGateway acp = org.mockito.Mockito.mock(AcpGateway.class);
    private final AppProperties properties = new AppProperties(
            new AppProperties.Acp(false, false, "codex", List.of(), ".",
                    java.time.Duration.ofSeconds(30), 1024, List.of(),
                    List.of("test-model"), "test-model"),
            new AppProperties.Chat(12000, 24),
            new AppProperties.Admin("", "", ""));
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void promptContainsExactOrderedCategories() {
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn("""
                [{"category":"INVERSION","secondaryCategory":null,"difficulty":"L2","domain":"ПРОДУКТ","situation":"Команда обсуждает реалистичную рабочую ситуацию достаточной длины и ищет новое безопасное решение без упоминания брендов.","question":"Какие действия гарантированно приведут процесс к провалу?","hint":"Найдите причинные механизмы нежелательного исхода.","options":["INVERSION","HYPERBOLE","REFRAMING","SIMPLIFICATION"],"correctCategory":"INVERSION","explanation":"Вопрос переворачивает цель и исследует конкретные причины возможного провала.","confusedWith":null,"contrast":null}]
                """);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        gateway.generate(List.of("INVERSION"), "test-model");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(acp).ask(prompt.capture(), eq("test-model"), any());
        assertThat(prompt.getValue()).contains("[\"INVERSION\"]");
        assertThat(prompt.getValue()).doesNotContain("{{categories}}", "{{count}}");
    }

    @Test
    void rejectsAcpCategoryThatDoesNotMatchRequestedPosition() {
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn("""
                [{"category":"HYPERBOLE","secondaryCategory":null,"difficulty":"L2","domain":"ПРОДУКТ","situation":"Команда обсуждает реалистичную рабочую ситуацию достаточной длины и ищет новое безопасное решение без упоминания брендов.","question":"Что изменится, если увеличить ограничение в десять раз?","hint":"Измените масштаб одного параметра.","options":["HYPERBOLE","INVERSION","REFRAMING","SIMPLIFICATION"],"correctCategory":"HYPERBOLE","explanation":"Вопрос доводит один параметр до экстремального значения и меняет механику решения.","confusedWith":null,"contrast":null}]
                """);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThatThrownBy(() -> gateway.generate(List.of("INVERSION"), "test-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category at position 0");
    }
}
```

- [ ] **Step 2: Run the gateway test and verify RED**

Run:

```bash
mvn -Dtest=AcpScenarioGenerationGatewayTest test
```

Expected: FAIL because the old prompt has no `{{categories}}` marker and the gateway does not enforce positional category equality.

- [ ] **Step 3: Add the dedicated prompt and positional validation**

Create `scenario-candidates-cycled-v1.md` with this complete contract:

```markdown
Создай ровно {{count}} учебных карточек для распознавания семи техник нестандартного мышления. Верни только один JSON-массив без Markdown.

Категории объектов по порядку: {{categories}}. Не меняй порядок. Для каждого объекта значения category и correctCategory обязаны совпадать с категорией в соответствующей позиции.

Допустимые canonical категории: INVERSION, HYPERBOLE, CROSS_DISCIPLINE, BACKCASTING, PROVOCATION, REFRAMING, SIMPLIFICATION. Сложность: L1, L2 или L3.

Схема каждого объекта:
{"category":"INVERSION","secondaryCategory":null,"difficulty":"L2","domain":"ПРОДУКТ","situation":"80–900 символов","question":"вопрос-взломщик","hint":"направление без правильного ответа","options":["INVERSION","HYPERBOLE","REFRAMING","SIMPLIFICATION"],"correctCategory":"INVERSION","explanation":"основная операция","confusedWith":null,"contrast":null}

Требования: одна основная техника; четыре уникальных варианта с правильным; hint не называет ответ; реалистичная безопасная ситуация без брендов; L3 обязательно содержит confusedWith и конкретный contrast. Не копируй известные, встроенные или ранее сгенерированные учебные примеры.
```

Render both markers and validate the parsed list:

```java
String categoriesJson = json.writeValueAsString(categories);
String rendered = prompt
        .replace("{{count}}", Integer.toString(categories.size()))
        .replace("{{categories}}", categoriesJson);
String raw = acp.ask(rendered, model, ignored -> { });
List<ScenarioDraft> drafts = json.readValue(raw.strip(), new TypeReference<>() { });
if (drafts.size() != categories.size()) {
    throw new IllegalArgumentException("Generator returned wrong count");
}
for (int index = 0; index < categories.size(); index++) {
    ScenarioDraft draft = drafts.get(index);
    String expected = categories.get(index);
    if (draft == null || !expected.equals(draft.category())
            || !expected.equals(draft.correctCategory())) {
        throw new IllegalArgumentException("Generator returned wrong category at position " + index);
    }
}
```

Load `prompts/scenario-candidates-cycled-v1.md` in `readPrompt()`.

- [ ] **Step 4: Run gateway and moderation tests and verify GREEN**

Run:

```bash
mvn -Dtest=AcpScenarioGenerationGatewayTest,ScenarioModerationTest test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit the prompt contract**

```bash
git add backend/src/main/resources/prompts/scenario-candidates-cycled-v1.md backend/src/main/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGateway.java backend/src/test/java/ru/questionhacker/trainer/moderation/AcpScenarioGenerationGatewayTest.java
git commit -m "feat: constrain ACP scenario categories"
```

### Task 3: Enforce personal issuance order and catalog exhaustion

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentUnavailableException.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ApiExceptionHandler.java`

- [ ] **Step 1: Write failing integration tests for empty catalog and active-cycle blocking**

Update test cleanup to delete `practice_draft` first and delete `scenario_candidate` after assignments so every test starts with an empty moderated catalog:

```java
jdbc.update("DELETE FROM practice_draft");
jdbc.update("DELETE FROM practice_assessment");
jdbc.update("DELETE FROM practice_attempt");
jdbc.update("DELETE FROM practice_assignment");
jdbc.update("DELETE FROM scenario_candidate");
```

Replace the old requested-category test with:

```java
@Test
void builtInScenariosAreNotPracticeCatalog() throws Exception {
    mvc.perform(post("/api/practice/assignments")
                    .with(user("practice-alice")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PRACTICE_CATALOG_EXHAUSTED"))
            .andExpect(jsonPath("$.detail").value(
                    "Вы прошли все доступные ситуации. Дождитесь, пока администратор добавит новые."));
    verifyNoInteractions(generator);
}

@Test
void clientCannotChooseItsNextCategory() throws Exception {
    mvc.perform(post("/api/practice/assignments")
                    .with(user("practice-alice")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetCategory\":\"SIMPLIFICATION\"}"))
            .andExpect(status().isBadRequest());
}

@Test
void unfinishedAssignmentBlocksAnotherAssignment() throws Exception {
    publishForPractice("INVERSION", 0);
    publishForPractice("HYPERBOLE", 0);
    assignment("practice-alice");

    mvc.perform(post("/api/practice/assignments")
                    .with(user("practice-alice")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PRACTICE_ASSIGNMENT_INCOMPLETE"));
}
```

Declare `@MockitoBean ScenarioGenerationGateway generator` in `PracticeAssignmentTest` and statically import `verifyNoInteractions`; this assertion proves that a user assignment request never crosses the administrative ACP boundary.

Add parameterized coverage for persisted terminal states that are not a pass:

```java
@ParameterizedTest
@ValueSource(strings = {"EVALUATING", "NEEDS_REVISION", "UNVERIFIED"})
void everyNonPassedLatestStatusBlocksAnotherAssignment(String attemptStatus) throws Exception {
    publishForPractice("INVERSION", 0);
    publishForPractice("HYPERBOLE", 0);
    UUID assignmentId = assignment("practice-alice");
    insertAttempt(assignmentId, alice.id(), attemptStatus);

    mvc.perform(post("/api/practice/assignments")
                    .with(user("practice-alice")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PRACTICE_ASSIGNMENT_INCOMPLETE"));
}
```

Use these helpers to expose existing scenarios through the moderated catalog and to complete a cycle without invoking ACP assessment:

```java
private UUID publishForPractice(String category, int offset) {
    UUID scenarioId = jdbc.queryForObject("""
            SELECT id FROM scenario WHERE category_code=? AND published=TRUE
            ORDER BY external_key LIMIT 1 OFFSET ?
            """, UUID.class, category, offset);
    UUID candidateId = UUID.randomUUID();
    jdbc.update("""
            INSERT INTO scenario_candidate(
              id, status, version_number, category_code, rejection_reasons_json,
              warnings_json, published_scenario_id, created_at, updated_at
            ) VALUES (?, 'PUBLISHED', 1, ?, '[]', '[]', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, candidateId, category, scenarioId);
    return scenarioId;
}

private UUID assignment(String username) throws Exception {
    String response = mvc.perform(post("/api/practice/assignments")
                    .with(user(username)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    return UUID.fromString(new ObjectMapper().readTree(response).path("assignmentId").asText());
}

private void pass(UUID assignmentId, UUID ownerId) {
    jdbc.update("DELETE FROM practice_draft WHERE assignment_id=?", assignmentId);
    insertAttempt(assignmentId, ownerId, "PASSED");
}

private void insertAttempt(UUID assignmentId, UUID ownerId, String status) {
    jdbc.update("""
            INSERT INTO practice_attempt(
              id, assignment_id, owner_id, parent_attempt_id, attempt_number,
              question_text, answer_text, reasoning_text, solution_text,
              revised_fields_json, status, created_at, completed_at
            ) VALUES (?, ?, ?, NULL, 1, 'question', 'answer', 'reasoning',
                      'solution', '[]', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), assignmentId, ownerId, status);
}
```

- [ ] **Step 2: Write the failing personal `1…7,1` and no-repeat test**

```java
@Test
void eachUserCyclesCategoriesAndNeverRepeatsAScenario() throws Exception {
    List<String> categories = List.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");
    categories.forEach(category -> publishForPractice(category, 0));
    publishForPractice("INVERSION", 1);

    List<String> issued = new ArrayList<>();
    Set<UUID> scenarios = new HashSet<>();
    for (int index = 0; index < 8; index++) {
        UUID assignmentId = assignment("practice-alice");
        issued.add(jdbc.queryForObject(
                "SELECT target_category_code FROM practice_assignment WHERE id=?",
                String.class, assignmentId));
        scenarios.add(jdbc.queryForObject(
                "SELECT scenario_id FROM practice_assignment WHERE id=?",
                UUID.class, assignmentId));
        pass(assignmentId, alice.id());
    }

    assertThat(issued).containsExactly(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION", "INVERSION");
    assertThat(scenarios).hasSize(8);
}
```

Add a second-user assertion after publishing one `INVERSION` scenario: Alice and Bob both receive `INVERSION`, proving their positions are independent.

- [ ] **Step 3: Run the assignment tests and verify RED**

Run:

```bash
mvn -Dtest=PracticeAssignmentTest test
```

Expected: built-in scenarios are still issued, active cycles are not rejected, and categories follow the request/selector rather than the persisted personal sequence.

- [ ] **Step 4: Implement machine-readable assignment errors**

Create:

```java
package ru.questionhacker.trainer.practice;

import org.springframework.http.HttpStatus;

public final class PracticeAssignmentUnavailableException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private PracticeAssignmentUnavailableException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static PracticeAssignmentUnavailableException incomplete() {
        return new PracticeAssignmentUnavailableException(HttpStatus.CONFLICT,
                "PRACTICE_ASSIGNMENT_INCOMPLETE",
                "Сначала завершите текущую ситуацию и получите зачёт.");
    }

    public static PracticeAssignmentUnavailableException exhausted() {
        return new PracticeAssignmentUnavailableException(HttpStatus.NOT_FOUND,
                "PRACTICE_CATALOG_EXHAUSTED",
                "Вы прошли все доступные ситуации. Дождитесь, пока администратор добавит новые.");
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
```

Map it in `ApiExceptionHandler`:

```java
@ExceptionHandler(PracticeAssignmentUnavailableException.class)
ProblemDetail practiceUnavailable(PracticeAssignmentUnavailableException error) {
    ProblemDetail result = problem(error.status().value(), error.getMessage());
    result.setProperty("code", error.code());
    return result;
}
```

- [ ] **Step 5: Implement locked personal sequencing and moderated selection**

Add repository operations:

```java
public void lockOwner(UUID ownerId) {
    jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", UUID.class, ownerId);
}

public List<String> categoryCodes() {
    return jdbc.queryForList("SELECT code FROM category ORDER BY sort_order", String.class);
}

public long assignmentCount(UUID ownerId) {
    Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM practice_assignment WHERE owner_id=?", Long.class, ownerId);
    return count == null ? 0L : count;
}

public boolean hasUnfinishedAssignment(UUID ownerId) {
    Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM practice_assignment assignment
            WHERE assignment.owner_id=?
              AND COALESCE((
                SELECT attempt.status FROM practice_attempt attempt
                WHERE attempt.assignment_id=assignment.id
                ORDER BY attempt.attempt_number DESC
                LIMIT 1
              ), 'DRAFT') <> 'PASSED'
            """, Integer.class, ownerId);
    return count != null && count > 0;
}
```

Replace `selectAssignmentSource` with a required-category query that excludes built-ins and repeats:

```java
public Optional<AssignmentSource> selectAssignmentSource(UUID ownerId, String targetCategory) {
    return jdbc.query("""
            SELECT s.id AS scenario_id, s.category_code, c.name,
                   c.operation_text, c.cue_text, s.domain_text, s.situation_text
            FROM scenario s
            JOIN category c ON c.code=s.category_code
            WHERE s.published=TRUE AND s.category_code=?
              AND EXISTS (
                SELECT 1 FROM scenario_candidate candidate
                WHERE candidate.status='PUBLISHED'
                  AND candidate.published_scenario_id=s.id
              )
              AND NOT EXISTS (
                SELECT 1 FROM practice_assignment assignment
                WHERE assignment.owner_id=? AND assignment.scenario_id=s.id
              )
            ORDER BY CASE s.difficulty WHEN 'L2' THEN 1 WHEN 'L3' THEN 2 ELSE 3 END,
                     s.external_key
            LIMIT 1
            """, this::source, targetCategory, ownerId).stream().findFirst();
}
```

Replace `PracticeAssignmentService.create` with:

```java
@Transactional
public AssignmentView create(UUID ownerId) {
    practice.lockOwner(ownerId);
    if (practice.hasUnfinishedAssignment(ownerId)) {
        throw PracticeAssignmentUnavailableException.incomplete();
    }
    List<String> categories = practice.categoryCodes();
    if (categories.isEmpty()) {
        throw PracticeAssignmentUnavailableException.exhausted();
    }
    long position = practice.assignmentCount(ownerId);
    String category = categories.get((int) (position % categories.size()));
    var source = practice.selectAssignmentSource(ownerId, category)
            .orElseThrow(PracticeAssignmentUnavailableException::exhausted);
    String guidance = source.operation() + " Контрольный ориентир: " + source.cue();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var assignment = practice.createAssignment(ownerId, source, guidance, now);
    practice.createEmptyDraft(ownerId, assignment.id(), now);
    return view(assignment);
}
```

Remove the category normalization set. In `PracticeController`, call `assignments.create(user.id())` and reject any client-owned category with validation:

```java
public record AssignmentRequest(@jakarta.validation.constraints.Null String targetCategory) { }
```

- [ ] **Step 6: Run practice backend tests and verify GREEN**

Run:

```bash
mvn -Dtest=PracticeAssignmentTest,PracticeCycleHistoryTest,PracticeAttemptLifecycleTest test
```

In `PracticeCycleHistoryTest` and `PracticeAttemptLifecycleTest`, add the same `publishForPractice` SQL helper from Step 1. Change their assignment helpers from a client-selected category request to a server-selected request:

```java
private UUID assignment(String username, String expectedCategory) throws Exception {
    publishForPractice(expectedCategory, 0);
    String response = mvc.perform(post("/api/practice/assignments")
                    .with(user(username)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.targetCategory.code").value(expectedCategory))
            .andReturn().getResponse().getContentAsString();
    return UUID.fromString(json.readTree(response).path("assignmentId").asText());
}
```

Ensure their cleanup deletes `scenario_candidate` after deleting assignments. Expected: all selected tests pass, including owner-only history and assessment lifecycle.

- [ ] **Step 7: Commit personal issuance**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/ApiExceptionHandler.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentUnavailableException.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeAssignmentService.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeController.java backend/src/main/java/ru/questionhacker/trainer/practice/PracticeRepository.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAssignmentTest.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeCycleHistoryTest.java backend/src/test/java/ru/questionhacker/trainer/practice/PracticeAttemptLifecycleTest.java
git commit -m "feat: gate sequential practice assignments"
```

### Task 4: Mirror practice availability in the frontend

**Files:**
- Modify: `frontend/tests/thin-client.test.mjs`
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/styles.css`
- Modify: `frontend/sw.js`

- [ ] **Step 1: Write failing thin-client assertions**

Add:

```javascript
test('practice blocks new assignments until pass and explains catalog exhaustion', async () => {
  const html = await fs.readFile(new URL('index.html', frontend), 'utf8');
  const app = await fs.readFile(new URL('app.js', frontend), 'utf8');

  assert.match(html, /id="practice-availability"[^>]*role="status"/);
  assert.match(app, /cycle\.status !== 'PASSED'/);
  assert.match(app, /PRACTICE_ASSIGNMENT_INCOMPLETE/);
  assert.match(app, /PRACTICE_CATALOG_EXHAUSTED/);
  assert.match(app, /Вы прошли все доступные ситуации\. Дождитесь, пока администратор добавит новые\./);
  assert.match(app, /syncPracticeAvailability/);
  const startPractice = app.match(/async function startPractice\(\)[\s\S]*?async function selectPracticeCycle/)?.[0] || '';
  assert.match(startPractice, /api\('\/practice\/assignments'/);
  assert.doesNotMatch(startPractice, /scenario-candidates|generate/);
});
```

- [ ] **Step 2: Run frontend tests and verify RED**

Run from repository root:

```bash
node --test frontend/tests/thin-client.test.mjs
```

Expected: FAIL because there is no availability status element or domain-code handling.

- [ ] **Step 3: Add availability state and messages**

Place this below `#new-practice` in `index.html`:

```html
<p class="practice-availability" id="practice-availability" role="status" aria-live="polite"></p>
```

Add frontend constants and helpers:

```javascript
const PRACTICE_ASSIGNMENT_INCOMPLETE = 'PRACTICE_ASSIGNMENT_INCOMPLETE';
const PRACTICE_CATALOG_EXHAUSTED = 'PRACTICE_CATALOG_EXHAUSTED';
const PRACTICE_EXHAUSTED_MESSAGE = 'Вы прошли все доступные ситуации. Дождитесь, пока администратор добавит новые.';

function unfinishedPracticeCycle() {
  return practiceCycles.find(cycle => cycle.status !== 'PASSED') || null;
}

function syncPracticeAvailability() {
  const unfinished = unfinishedPracticeCycle();
  const message = $('#practice-availability');
  for (const button of [$('#new-practice'), $('#start-practice')]) {
    button.disabled = Boolean(unfinished) || practiceSubmitting;
    button.title = unfinished ? 'Сначала завершите текущую ситуацию и получите зачёт.' : '';
  }
  if (unfinished) {
    message.textContent = 'Сначала завершите текущую ситуацию и получите зачёт.';
  } else if (message.dataset.code === PRACTICE_ASSIGNMENT_INCOMPLETE) {
    message.textContent = '';
    delete message.dataset.code;
  }
}
```

Call `syncPracticeAvailability()` after `renderPracticeCycles()`, after terminal assessment refresh, and at the end of `startPractice`. In `startPractice` handle backend codes before the generic toast:

```javascript
} catch (error) {
  const code = error.problem?.code;
  if (code === PRACTICE_CATALOG_EXHAUSTED || code === PRACTICE_ASSIGNMENT_INCOMPLETE) {
    const message = $('#practice-availability');
    message.dataset.code = code;
    message.textContent = code === PRACTICE_CATALOG_EXHAUSTED
      ? PRACTICE_EXHAUSTED_MESSAGE : error.message;
  } else {
    showToast(error.message);
  }
} finally {
  buttons.forEach(button => setBusy(button, false));
  $('#new-practice').textContent = '＋ Новая ситуация';
  $('#start-practice').innerHTML = 'Получить ситуацию <span>→</span>';
  syncPracticeAvailability();
}
```

Style `.practice-availability` as compact sidebar text with sufficient contrast and a nonzero minimum height. Increment the service-worker cache key so deployed clients receive the new shell.

- [ ] **Step 4: Run all frontend tests and verify GREEN**

Run:

```bash
node --test frontend/tests/*.test.mjs
```

Expected: all frontend tests pass.

- [ ] **Step 5: Commit the frontend state**

```bash
git add frontend/tests/thin-client.test.mjs frontend/index.html frontend/app.js frontend/styles.css frontend/sw.js
git commit -m "feat: show practice assignment availability"
```

### Task 5: Update operator documentation and run full verification

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Update the documented contracts**

In `README.md`, document that:

```markdown
- ACP generates scenario candidates only through the administrator moderation endpoint.
- Practice assignments use only published moderation candidates and never call ACP.
- Each user follows category `1…7`, may hold only one unfinished assignment, and sees an exhaustion message when the next category has no unseen published scenario.
```

Replace the prompt inventory entry with `backend/src/main/resources/prompts/scenario-candidates-cycled-v1.md`. In `docs/ARCHITECTURE.md`, show the flow `admin → ACP → scenario_candidate → moderation → scenario → practice_assignment` and state that built-in curriculum scenarios remain trainer-only.

- [ ] **Step 2: Run formatting and placeholder checks**

Run:

```bash
git diff --check
rg -n "scenario-candidates-v1" README.md docs/ARCHITECTURE.md backend/src/main/java
```

Expected: `git diff --check` exits 0. The search returns no stale prompt reference in active code or operator documentation.

- [ ] **Step 3: Run the complete backend suite**

Run from `backend/`:

```bash
mvn test
```

Expected: Maven exits 0 with zero failures and zero errors.

- [ ] **Step 4: Run all JavaScript and content tests**

Run from repository root:

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
```

Expected: Node exits 0 and reports zero failed tests.

- [ ] **Step 5: Build the backend artifact**

Run from `backend/`:

```bash
mvn -DskipTests package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Review requirement coverage against the design**

Check the final diff against `docs/superpowers/specs/2026-08-13-practice-moderated-catalog-cycle-design.md` and explicitly confirm:

```text
admin-only ACP generation
global candidate category cycle
manual moderation before Practice
personal category cycle
no scenario repeat per user
no new assignment before PASSED
catalog exhaustion domain code and message
built-in trainer scenarios excluded from Practice
```

- [ ] **Step 7: Commit documentation**

```bash
git add README.md docs/ARCHITECTURE.md
git commit -m "docs: describe moderated practice flow"
```
