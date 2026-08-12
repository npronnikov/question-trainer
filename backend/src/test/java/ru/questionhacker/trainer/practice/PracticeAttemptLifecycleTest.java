package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

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
        jdbc.update("DELETE FROM practice_assessment");
        jdbc.update("DELETE FROM practice_attempt");
        jdbc.update("DELETE FROM practice_assignment");
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

        UUID attempt = submit("assessment-alice", assignment, "key-pass");
        JsonNode terminal = awaitTerminal("assessment-alice", attempt);

        assertThat(terminal.path("status").asText()).isEqualTo("PASSED");
        assertThat(terminal.path("assessment").path("categoryFitScore").asInt()).isEqualTo(2);
        assertThat(terminal.path("assessment").path("questionStrengthScore").asInt()).isEqualTo(3);
        assertThat(terminal.path("assessment").path("steps")).hasSize(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM practice_assessment
                WHERE attempt_id=? AND outcome='VERIFIED'
                  AND prompt_version=1 AND schema_version='practice-assessment-v1'
                  AND model_id='test-model'
                """, Integer.class, attempt)).isEqualTo(1);
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
    void deterministicInputFilterRejectsDuplicatedStepsBeforeCreatingAttempt() throws Exception {
        UUID assignment = assignment("assessment-alice");
        String duplicate = "Одинаковый текст шага достаточно длинный для базовой проверки формы.";
        String body = json.createObjectNode()
                .put("assignmentId", assignment.toString())
                .put("question", duplicate).put("answer", duplicate)
                .put("reasoning", duplicate).put("solution", duplicate)
                .put("idempotencyKey", "key-duplicate").toString();

        mvc.perform(post("/api/practice/attempts")
                        .with(user("assessment-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_attempt", Integer.class)).isZero();
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

    private UUID assignment(String username) throws Exception {
        String response = mvc.perform(post("/api/practice/assignments")
                        .with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetCategory\":\"INVERSION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).path("assignmentId").asText());
    }

    private UUID submit(String username, UUID assignment, String key) throws Exception {
        String body = json.createObjectNode()
                .put("assignmentId", assignment.toString())
                .put("question", "Какие три действия гарантированно приведут этот запуск к провалу?")
                .put("answer", "Провал создадут размытый владелец результата, поздняя проверка и скрытые риски.")
                .put("reasoning", "Если обратить причины провала, получаем раннего владельца, быстрый тест и открытый реестр рисков.")
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

    private String validAssessment(int fit, int strength, String confidence, String verdict) {
        String fields = fit >= 2 && strength >= 3 && !"LOW".equals(confidence)
                ? "[]" : "[\"question\"]";
        String prefix = verdict == null ? "" : "\"verdict\":\"" + verdict + "\",";
        return """
                {%s
                  "schemaVersion":"practice-assessment-v1",
                  "completeness":{"status":"PASS","steps":[
                    {"field":"question","status":"PASS","evidence":"Названы три действия провала"},
                    {"field":"answer","status":"PASS","evidence":"Перечислены причины провала"},
                    {"field":"reasoning","status":"PASS","evidence":"Причины обращены в профилактику"},
                    {"field":"solution","status":"PASS","evidence":"Предложен недельный тест"}
                  ]},
                  "categoryFit":{"score":%d,"evidence":"Вопрос ищет способы провала и обращает их","confusedWith":null},
                  "questionStrength":{"score":%d,"dimensions":[
                    {"name":"specificity","met":true,"evidence":"Три действия и запуск"},
                    {"name":"depth","met":true,"evidence":"Ищутся причины"},
                    {"name":"unexpectedness","met":true,"evidence":"Цель перевёрнута"},
                    {"name":"productivity","met":%s,"evidence":"Можно вывести профилактику"}
                  ]},
                  "confidence":"%s","strengths":["Ясная инверсия"],
                  "priorityCorrection":{"what":"Уточнить измеримость","why":"Так проверка станет точнее","example":"Какие три наблюдаемых действия?"},
                  "fieldsToRevise":%s,"feedback":"Цепочка оценена по трём независимым проверкам."
                }
                """.formatted(prefix, fit, strength, strength == 4, confidence, fields);
    }
}
