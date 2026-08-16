package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.slf4j.LoggerFactory;

import ru.questionhacker.trainer.auth.UserAccountRepository;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:practice-attempt;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class PracticeAttemptLifecycleTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private PracticeAssessmentGateway gateway;

    @BeforeEach
    void resetUserData() {
        jdbc.update("DELETE FROM practice_draft");
        jdbc.update("DELETE FROM practice_assessment");
        jdbc.update("DELETE FROM practice_attempt");
        jdbc.update("DELETE FROM practice_assignment");
        jdbc.update("DELETE FROM moderation_action");
        jdbc.update("DELETE FROM scenario_candidate");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        users.create("assessment-alice", null, "$2a$alice", Set.of("USER"), false);
        users.create("assessment-bob", null, "$2a$bob", Set.of("USER"), false);
    }

    @Test
    void validModelAssessmentBecomesPassedAndPersistsAuditMetadata() throws Exception {
        when(gateway.assess(any(), anyString())).thenReturn(
                new PracticeAssessmentGateway.Result(validAssessment(2, 3, "MEDIUM", "PASSED"), "test-model"));
        UUID assignment = assignment("assessment-alice");
        assertThat(draftCount(assignment)).isEqualTo(1);

        UUID attempt = submit("assessment-alice", assignment, "key-pass");
        assertThat(draftCount(assignment)).isZero();
        JsonNode terminal = awaitTerminal("assessment-alice", attempt);

        assertThat(terminal.path("status").asText()).isEqualTo("PASSED");
        assertThat(terminal.path("assessment").path("categoryFitScore").asInt()).isEqualTo(2);
        assertThat(terminal.path("assessment").path("questionStrengthScore").asInt()).isEqualTo(3);
        assertThat(terminal.path("assessment").path("steps")).hasSize(3);
        assertThat(terminal.at("/assessment/ideaPotential/overallScore").decimalValue())
                .isEqualByComparingTo("3.00");
        assertThat(terminal.at("/assessment/ideaPotential/dimensions")).hasSize(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM practice_assessment
                WHERE attempt_id=? AND outcome='VERIFIED'
                  AND prompt_version=3 AND schema_version='practice-assessment-v3'
                  AND idea_potential_score=3.00
                  AND model_id='test-model'
                """, Integer.class, attempt)).isEqualTo(1);
    }

    @Test
    void insufficientFeasibilityKeepsKnownAxesWithoutInventingOverall() throws Exception {
        String incomplete = validAssessment(2, 3, "HIGH", null).replace(
                "{\"name\":\"feasibility\",\"status\":\"SCORED\",\"score\":3,\"evidence\":\"Предложен недельный тест\"}",
                "{\"name\":\"feasibility\",\"status\":\"INSUFFICIENT_CONTEXT\",\"score\":null,\"evidence\":\"Ресурсы кейса не заданы\"}");
        when(gateway.assess(any(), anyString())).thenReturn(
                new PracticeAssessmentGateway.Result(incomplete, "test-model"));
        UUID attempt = submit("assessment-alice", assignment("assessment-alice"), "key-incomplete");

        JsonNode terminal = awaitTerminal("assessment-alice", attempt);

        assertThat(terminal.path("status").asText()).isEqualTo("PASSED");
        assertThat(terminal.at("/assessment/ideaPotential/complete").asBoolean()).isFalse();
        assertThat(terminal.at("/assessment/ideaPotential/dimensions")).hasSize(4);
        assertThat(terminal.at("/assessment/ideaPotential/overallScore").isMissingNode()
                || terminal.at("/assessment/ideaPotential/overallScore").isNull()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT idea_potential_score IS NULL FROM practice_assessment WHERE attempt_id=?
                """, Boolean.class, attempt)).isTrue();
    }

    @Test
    void modelVerdictCannotOverrideBackendThresholds() throws Exception {
        when(gateway.assess(any(), anyString())).thenReturn(
                new PracticeAssessmentGateway.Result(validAssessment(1, 3, "HIGH", "PASSED"), "test-model"));
        UUID attempt = submit("assessment-alice", assignment("assessment-alice"), "key-revise");

        JsonNode terminal = awaitTerminal("assessment-alice", attempt);

        assertThat(terminal.path("status").asText()).isEqualTo("NEEDS_REVISION");
        assertThat(terminal.path("assessment").path("fieldsToRevise").isArray()).isTrue();
    }

    @Test
    void gatewayFailureIsUnverifiedAndNeverGetsSemanticScores() throws Exception {
        when(gateway.assess(any(), anyString())).thenThrow(new IllegalStateException("offline"));
        UUID attempt = submit("assessment-alice", assignment("assessment-alice"), "key-offline");

        JsonNode terminal = awaitTerminal("assessment-alice", attempt);

        assertThat(terminal.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(terminal.path("assessment").has("categoryFitScore")).isFalse();
        assertThat(terminal.path("assessment").has("questionStrengthScore")).isFalse();
        assertThat(terminal.path("assessment").path("feedback").asText())
                .contains("семантическая оценка недоступна");
    }

    @Test
    void lowConfidenceIsUnverifiedButKeepsModelAuditMetadata() throws Exception {
        when(gateway.assess(any(), anyString())).thenReturn(
                new PracticeAssessmentGateway.Result(
                        validAssessment(3, 4, "LOW", null), "low-confidence-model"));
        UUID attempt = submit("assessment-alice", assignment("assessment-alice"), "key-low-confidence");

        JsonNode terminal = awaitTerminal("assessment-alice", attempt);

        assertThat(terminal.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(terminal.path("assessment").has("categoryFitScore")).isFalse();
        assertThat(terminal.path("assessment").path("modelId").asText())
                .isEqualTo("low-confidence-model");
        assertThat(jdbc.queryForObject("""
                SELECT failure_reason FROM practice_assessment WHERE attempt_id=?
                """, String.class, attempt)).isEqualTo("LOW_MODEL_CONFIDENCE");
    }

    @Test
    void unverifiedInitialAttemptCanBeEditedAndRetriedIdempotently() throws Exception {
        when(gateway.assess(any(), anyString()))
                .thenThrow(new IllegalStateException("stopped"))
                .thenReturn(new PracticeAssessmentGateway.Result(
                        validAssessment(2, 3, "HIGH", null), "retry-model"));
        UUID assignment = assignment("assessment-alice");
        UUID failed = submit("assessment-alice", assignment, "initial-unverified");
        assertThat(awaitTerminal("assessment-alice", failed).path("status").asText())
                .isEqualTo("UNVERIFIED");

        mvc.perform(post("/api/practice/attempts/{id}/retries", failed)
                        .with(user("assessment-bob")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(4), rationale(), solution(), "foreign-retry")))
                .andExpect(status().isNotFound());

        String accepted = mvc.perform(post("/api/practice/attempts/{id}/retries", failed)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(4), rationale(), solution(), "retry-same-key")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.parentAttemptId").value(failed.toString()))
                .andExpect(jsonPath("$.attemptNumber").value(2))
                .andExpect(jsonPath("$.question").value(question(4)))
                .andReturn().getResponse().getContentAsString();
        UUID retried = UUID.fromString(json.readTree(accepted).path("attemptId").asText());

        mvc.perform(post("/api/practice/attempts/{id}/retries", failed)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(4), rationale(), solution(), "retry-same-key")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptId").value(retried.toString()));
        mvc.perform(post("/api/practice/attempts/{id}/retries", failed)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(5), rationale(), solution(), "retry-same-key")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/practice/attempts/{id}/retries", failed)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"retry-same-key\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/practice/attempts/{id}/retries", UUID.randomUUID())
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(4), rationale(), solution(), "retry-same-key")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/practice/attempts/{id}/retries", retried)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(4), rationale(), solution(), "retry-same-key")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/practice/attempts/{id}/retries", failed)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(4), rationale(), solution(), "stale-parent")))
                .andExpect(status().isConflict());

        assertThat(awaitTerminal("assessment-alice", retried).path("status").asText())
                .isEqualTo("PASSED");
        mvc.perform(post("/api/practice/attempts/{id}/retries", retried)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(4), rationale(), solution(), "passed-retry")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM practice_attempt WHERE assignment_id=?",
                Integer.class, assignment)).isEqualTo(2);
    }

    @Test
    void failedRevisionKeepsItsEditableFieldsAcrossRetriesAndDrafts() throws Exception {
        when(gateway.assess(any(), anyString()))
                .thenReturn(new PracticeAssessmentGateway.Result(
                        validAssessment(1, 3, "HIGH", null), "test-model"))
                .thenThrow(new IllegalStateException("revision stopped"))
                .thenThrow(new IllegalStateException("retry stopped"))
                .thenReturn(new PracticeAssessmentGateway.Result(
                        validAssessment(2, 3, "HIGH", null), "retry-model"));
        UUID assignment = assignment("assessment-alice");
        UUID original = submit("assessment-alice", assignment, "scope-original");
        assertThat(awaitTerminal("assessment-alice", original).path("status").asText())
                .isEqualTo("NEEDS_REVISION");

        String revisionResponse = mvc.perform(post("/api/practice/attempts/{id}/revisions", original)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("question", question(4))
                                .put("idempotencyKey", "scope-revision").toString()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID failedRevision = UUID.fromString(
                json.readTree(revisionResponse).path("attemptId").asText());
        assertThat(awaitTerminal("assessment-alice", failedRevision).path("status").asText())
                .isEqualTo("UNVERIFIED");
        assertEditableQuestionOnly(assignment, failedRevision);

        mvc.perform(put("/api/practice/cycles/{id}/draft", assignment)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(failedRevision, question(5), rationale())))
                .andExpect(status().isOk());
        mvc.perform(put("/api/practice/cycles/{id}/draft", assignment)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody(failedRevision, question(5), rationale() + " Изменение.")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/practice/attempts/{id}/retries", failedRevision)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(5), rationale() + " Изменение.", solution(),
                                "blocked-field")))
                .andExpect(status().isBadRequest());

        String retryResponse = mvc.perform(post("/api/practice/attempts/{id}/retries", failedRevision)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(5), rationale(), solution(), "scope-retry")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID failedRetry = UUID.fromString(json.readTree(retryResponse).path("attemptId").asText());
        assertThat(awaitTerminal("assessment-alice", failedRetry).path("status").asText())
                .isEqualTo("UNVERIFIED");
        assertEditableQuestionOnly(assignment, failedRetry);

        String unchangedResponse = mvc.perform(post("/api/practice/attempts/{id}/retries", failedRetry)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody(question(5), rationale(), solution(), "unchanged-retry")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID unchangedRetry = UUID.fromString(
                json.readTree(unchangedResponse).path("attemptId").asText());
        assertThat(awaitTerminal("assessment-alice", unchangedRetry).path("status").asText())
                .isEqualTo("PASSED");
    }

    @Test
    void invalidModelAssessmentLogsValidationReasonAndStackTrace() throws Exception {
        when(gateway.assess(any(), anyString())).thenReturn(
                new PracticeAssessmentGateway.Result(
                        validAssessment(1, 3, "HIGH", null)
                                .replace("\"feedback\"", "\"fieldsToRevise\":[],\"feedback\""),
                        "test-model"));
        Logger logger = (Logger) LoggerFactory.getLogger(PracticeAssessmentService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            UUID attempt = submit("assessment-alice", assignment("assessment-alice"), "key-invalid-log");

            assertThat(awaitTerminal("assessment-alice", attempt).path("status").asText())
                    .isEqualTo("UNVERIFIED");
            ILoggingEvent warning = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains(attempt.toString()))
                    .findFirst().orElseThrow();
            assertThat(warning.getFormattedMessage()).contains("Invalid assessment JSON");
            assertThat(warning.getThrowableProxy()).isNotNull();
            assertThat(warning.getThrowableProxy().getMessage()).isEqualTo("Invalid assessment JSON");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void deterministicInputFilterRejectsDuplicatedStepsBeforeCreatingAttempt() throws Exception {
        UUID assignment = assignment("assessment-alice");
        String duplicate = "Одинаковый текст шага достаточно длинный для базовой проверки формы.";
        String body = json.createObjectNode()
                .put("assignmentId", assignment.toString())
                .put("question", duplicate).put("rationale", duplicate)
                .put("solution", duplicate)
                .put("idempotencyKey", "key-duplicate").toString();

        mvc.perform(post("/api/practice/attempts")
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_attempt", Integer.class)).isZero();
    }

    @Test
    void legacyFourStepAssessmentIsExposedAsThreeFieldHistory() throws Exception {
        UUID assignment = assignment("assessment-alice");
        UUID owner = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username='assessment-alice'", UUID.class);
        UUID attempt = UUID.randomUUID();
        jdbc.update("DELETE FROM practice_draft WHERE assignment_id=?", assignment);
        jdbc.update("""
                INSERT INTO practice_attempt(
                  id, assignment_id, owner_id, parent_attempt_id, attempt_number,
                  question_text, rationale_text, solution_text, revised_fields_json,
                  status, requested_model, idempotency_key, created_at, completed_at
                ) VALUES (?, ?, ?, NULL, 1, 'Вопрос', 'Обоснование', 'Решение', '[]',
                          'NEEDS_REVISION', 'legacy-model', 'legacy-attempt',
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attempt, assignment, owner);
        jdbc.update("""
                INSERT INTO practice_assessment(
                  id, attempt_id, outcome, completeness_status, step_results_json,
                  category_fit_score, category_fit_evidence, confused_with,
                  question_strength_score, strength_dimensions_json, confidence,
                  strengths_json, correction_what, correction_why, correction_example,
                  fields_to_revise_json, feedback_text, prompt_key, prompt_version,
                  schema_version, model_id, latency_ms, failure_reason, created_at
                ) VALUES (?, ?, 'VERIFIED', 'FAIL', ?, 2, 'fits', NULL,
                          3, '[]', 'HIGH', '[]', 'Исправить', 'Причина', 'Пример',
                          '["reasoning"]', 'Legacy feedback', 'practice-assessment', 1,
                          'practice-assessment-v1', 'legacy-model', 1, NULL, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), attempt, """
                [{"field":"question","status":"PASS","evidence":"q"},
                 {"field":"answer","status":"PASS","evidence":"a"},
                 {"field":"reasoning","status":"FAIL","evidence":"r"},
                 {"field":"solution","status":"PASS","evidence":"s"}]
                """);

        mvc.perform(get("/api/practice/attempts/{id}", attempt)
                        .with(user("assessment-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessment.steps.length()").value(3))
                .andExpect(jsonPath("$.assessment.steps[1].field").value("rationale"))
                .andExpect(jsonPath("$.assessment.steps[1].status").value("CONTRADICTS"))
                .andExpect(jsonPath("$.assessment.fieldsToRevise[0]").value("rationale"));
    }

    @Test
    void pollingIsOwnerOnlyAndIdempotencyKeyReturnsSameAttempt() throws Exception {
        when(gateway.assess(any(), anyString())).thenReturn(
                new PracticeAssessmentGateway.Result(validAssessment(2, 3, "HIGH", null), "test-model"));
        UUID assignment = assignment("assessment-alice");
        UUID first = submit("assessment-alice", assignment, "same-key");
        UUID second = submit("assessment-alice", assignment, "same-key");
        assertThat(second).isEqualTo(first);

        mvc.perform(get("/api/practice/attempts/{id}", first).with(user("assessment-bob")))
                .andExpect(status().isNotFound());
        awaitTerminal("assessment-alice", first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_attempt", Integer.class)).isEqualTo(1);
    }

    @Test
    void revisionMayChangeOnlyFieldsListedByServerAndKeepsHistory() throws Exception {
        when(gateway.assess(any(), anyString()))
                .thenReturn(new PracticeAssessmentGateway.Result(
                        validAssessment(1, 3, "HIGH", "PASSED"), "test-model"))
                .thenReturn(new PracticeAssessmentGateway.Result(
                        validAssessment(2, 3, "HIGH", null), "test-model"));
        UUID assignment = assignment("assessment-alice");
        UUID original = submit("assessment-alice", assignment, "revision-original");
        awaitTerminal("assessment-alice", original);

        mvc.perform(post("/api/practice/attempts/{id}/revisions", original)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rationale\":\"Нельзя незаметно менять сильный шаг, который сервер не отметил для исправления.\",\"idempotencyKey\":\"bad-revision\"}"))
                .andExpect(status().isBadRequest());

        String revisedQuestion = "Какие три наблюдаемых действия гарантированно приведут запуск к провалу за неделю?";
        String draft = json.createObjectNode()
                .put("baseAttemptId", original.toString())
                .put("question", revisedQuestion)
                .put("rationale", rationale())
                .put("solution", "Назначить владельца и провести недельный тест с реестром трёх главных рисков.")
                .toString();
        mvc.perform(put("/api/practice/cycles/{id}/draft", assignment)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk());
        assertThat(draftCount(assignment)).isEqualTo(1);

        String response = mvc.perform(post("/api/practice/attempts/{id}/revisions", original)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("question", revisedQuestion)
                                .put("idempotencyKey", "good-revision").toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.parentAttemptId").value(original.toString()))
                .andExpect(jsonPath("$.attemptNumber").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(draftCount(assignment)).isZero();
        UUID revision = UUID.fromString(json.readTree(response).path("attemptId").asText());
        mvc.perform(post("/api/practice/attempts/{id}/revisions", original)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("question", question(5))
                                .put("idempotencyKey", "good-revision").toString()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/practice/attempts/{id}/revisions", original)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"good-revision\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/practice/attempts/{id}/revisions", original)
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("question", question(5))
                                .put("idempotencyKey", "stale-revision-parent").toString()))
                .andExpect(status().isConflict());
        assertThat(awaitTerminal("assessment-alice", revision).path("status").asText())
                .isEqualTo("PASSED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM practice_attempt WHERE assignment_id=(SELECT assignment_id FROM practice_attempt WHERE id=?)",
                Integer.class, original)).isEqualTo(2);
        mvc.perform(get("/api/practice/cycles").with(user("assessment-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].assignmentId").value(assignment.toString()))
                .andExpect(jsonPath("$[0].attemptCount").value(2))
                .andExpect(jsonPath("$[0].status").value("PASSED"));
    }

    @Test
    void ssePublishesSameTerminalAttemptAndIsOwnerProtected() throws Exception {
        when(gateway.assess(any(), anyString())).thenReturn(
                new PracticeAssessmentGateway.Result(validAssessment(2, 3, "HIGH", null), "test-model"));
        UUID attempt = submit("assessment-alice", assignment("assessment-alice"), "sse-key");
        awaitTerminal("assessment-alice", attempt);

        MvcResult stream = mvc.perform(get("/api/practice/attempts/{id}/events", attempt)
                        .with(user("assessment-alice")))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PASSED")));

        mvc.perform(get("/api/practice/attempts/{id}/events", attempt)
                        .with(user("assessment-bob")))
                .andExpect(status().isNotFound());
    }

    private UUID assignment(String username) throws Exception {
        publishForPractice("INVERSION", 0);
        String response = mvc.perform(post("/api/practice/assignments")
                        .with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetCategory.code").value("INVERSION"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).path("assignmentId").asText());
    }

    private void publishForPractice(String category, int offset) {
        UUID scenarioId = jdbc.queryForObject("""
                SELECT id FROM scenario WHERE category_code=? AND published=TRUE
                ORDER BY external_key LIMIT 1 OFFSET ?
                """, UUID.class, category, offset);
        jdbc.update("""
                UPDATE scenario SET content_target='PRACTICE',
                  hint_text='Посмотрите на ситуацию под другим углом, не называя готовую технику.'
                WHERE id=?
                """, scenarioId);
        jdbc.update("""
                INSERT INTO scenario_candidate(
                  id, status, content_target, version_number, category_code,
                  rejection_reasons_json, warnings_json, published_scenario_id,
                  created_at, updated_at
                ) VALUES (?, 'PUBLISHED', 'PRACTICE', 1, ?, '[]', '[]', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), category, scenarioId);
    }

    private UUID submit(String username, UUID assignment, String key) throws Exception {
        String body = json.createObjectNode()
                .put("assignmentId", assignment.toString())
                .put("question", "Какие три действия гарантированно приведут этот запуск к провалу?")
                .put("rationale", rationale())
                .put("solution", "Назначить владельца и провести недельный тест с реестром трёх главных рисков.")
                .put("model", "gpt-5.6-terra[high]")
                .put("idempotencyKey", key).toString();
        String response = mvc.perform(post("/api/practice/attempts")
                        .with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).path("attemptId").asText());
    }

    private JsonNode awaitTerminal(String username, UUID attempt) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(4));
        JsonNode result;
        do {
            String response = mvc.perform(get("/api/practice/attempts/{id}", attempt)
                            .with(user(username)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            result = json.readTree(response);
            if (!"EVALUATING".equals(result.path("status").asText())) return result;
            Thread.sleep(10);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Assessment did not complete: " + result);
    }

    private int draftCount(UUID assignment) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM practice_draft WHERE assignment_id=?",
                Integer.class, assignment);
    }

    private void assertEditableQuestionOnly(UUID assignment, UUID baseAttempt) throws Exception {
        mvc.perform(get("/api/practice/cycles/{id}", assignment)
                        .with(user("assessment-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editor.baseAttemptId").value(baseAttempt.toString()))
                .andExpect(jsonPath("$.editor.editableFields.length()").value(1))
                .andExpect(jsonPath("$.editor.editableFields[0]").value("question"));
    }

    private String retryBody(String question, String rationale, String solution, String key) {
        return json.createObjectNode()
                .put("question", question).put("rationale", rationale)
                .put("solution", solution)
                .put("model", "gpt-5.6-terra[high]")
                .put("idempotencyKey", key).toString();
    }

    private String draftBody(UUID baseAttempt, String question, String rationale) {
        return json.createObjectNode()
                .put("baseAttemptId", baseAttempt.toString())
                .put("question", question).put("rationale", rationale)
                .put("solution", solution()).toString();
    }

    private String question(int actions) {
        return "Какие " + actions + " наблюдаемых действия гарантированно приведут запуск к провалу за неделю?";
    }

    private String rationale() {
        return "Провал создадут размытый владелец результата, поздняя проверка и скрытые риски. "
                + "Если обратить причины провала, получаем раннего владельца, быстрый тест и открытый реестр рисков.";
    }

    private String solution() {
        return "Назначить владельца и провести недельный тест с реестром трёх главных рисков.";
    }

    private String validAssessment(int fit, int strength, String confidence, String verdict) {
        return """
                {
                  "schemaVersion":"practice-assessment-v3",
                  "chain":{"steps":[
                    {"field":"question","status":"PASS","evidence":"Названы три действия провала"},
                    {"field":"rationale","status":"SUPPORTS","evidence":"Причины обращены в профилактику"},
                    {"field":"solution","status":"PASS","evidence":"Предложен недельный тест"}
                  ]},
                  "categoryFit":{"score":%d,"evidence":"Вопрос ищет способы провала и обращает их","confusedWith":null},
                  "questionStrength":{"score":%d,"dimensions":[
                    {"name":"specificity","met":true,"evidence":"Три действия и запуск"},
                    {"name":"depth","met":true,"evidence":"Ищутся причины"},
                    {"name":"unexpectedness","met":true,"evidence":"Цель перевёрнута"},
                    {"name":"productivity","met":%s,"evidence":"Можно вывести профилактику"}
                  ]},
                  "ideaPotential":{"dimensions":[
                    {"name":"impact","status":"SCORED","score":4,"evidence":"Меняется способ запуска"},
                    {"name":"questionAlignment","status":"SCORED","score":3,"evidence":"Решение отвечает на вопрос"},
                    {"name":"disruption","status":"SCORED","score":2,"evidence":"Есть переворот привычной цели"},
                    {"name":"feasibility","status":"SCORED","score":3,"evidence":"Предложен недельный тест"}
                  ]},
                  "confidence":"%s","strengths":["Ясная инверсия"],
                  "priorityCorrection":{"what":"Уточнить измеримость","why":"Так проверка станет точнее","example":"Какие три наблюдаемых действия?"},
                  "feedback":"Цепочка оценена по трём независимым проверкам."
                }
                """.formatted(fit, strength, strength == 4, confidence);
    }
}
