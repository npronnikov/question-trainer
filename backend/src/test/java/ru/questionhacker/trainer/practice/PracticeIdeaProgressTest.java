package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import ru.questionhacker.trainer.auth.AppUser;
import ru.questionhacker.trainer.auth.UserAccountRepository;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:practice-idea-progress;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class PracticeIdeaProgressTest {

    private static final List<String> CATEGORIES = List.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private UserAccountRepository users;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM practice_draft");
        jdbc.update("DELETE FROM practice_assessment");
        jdbc.update("DELETE FROM practice_attempt");
        jdbc.update("DELETE FROM practice_assignment");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        alice = users.create("progress-alice", null, "$2a$alice", Set.of("USER"), false);
        bob = users.create("progress-bob", null, "$2a$bob", Set.of("USER"), false);
    }

    @Test
    void returnsTwoCompletedCyclesUsingFirstVerifiedAttemptOnly() throws Exception {
        UUID firstInversion = null;
        UUID improvedInversion = null;
        for (int sequence = 1; sequence <= 14; sequence++) {
            UUID assignment = assignment(alice.id(), sequence);
            UUID first = verifiedAttempt(assignment, alice.id(), 1,
                    sequence == 1 ? "NEEDS_REVISION" : "PASSED", sequence <= 7 ? 1 : 3);
            if (sequence == 1) {
                firstInversion = first;
                improvedInversion = verifiedAttempt(assignment, alice.id(), 2, "PASSED", 4);
            }
        }
        UUID bobAttempt = verifiedAttempt(
                assignment(bob.id(), 1), bob.id(), 1, "PASSED", 4);

        String body = mvc.perform(get("/api/practice/idea-progress")
                        .with(user("progress-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastStartedCycle").value(2))
                .andExpect(jsonPath("$.lastAnsweredCycle").value(2))
                .andExpect(jsonPath("$.comparisonAvailable").value(true))
                .andExpect(jsonPath("$.categories[0].code").value("INVERSION"))
                .andExpect(jsonPath("$.categories[0].points[0].attemptId")
                        .value(firstInversion.toString()))
                .andExpect(jsonPath("$.categories[0].points[0].ideaPotential.overallScore")
                        .value(1.0))
                .andExpect(jsonPath("$.categories[0].points[1].ideaPotential.overallScore")
                        .value(3.0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(improvedInversion.toString(), bobAttempt.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM practice_assessment", Integer.class))
                .isEqualTo(16);
    }

    @Test
    void incompleteCycleCreatesExplicitGapsAndNoComparison() throws Exception {
        for (int sequence = 1; sequence <= 6; sequence++) {
            UUID assignment = assignment(alice.id(), sequence);
            if (sequence != 2) verifiedAttempt(assignment, alice.id(), 1, "PASSED", 2);
        }

        mvc.perform(get("/api/practice/idea-progress").with(user("progress-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastStartedCycle").value(1))
                .andExpect(jsonPath("$.lastAnsweredCycle").value(0))
                .andExpect(jsonPath("$.comparisonAvailable").value(false))
                .andExpect(jsonPath("$.categories[0].points[0].gapReason")
                        .value("CYCLE_INCOMPLETE"))
                .andExpect(jsonPath("$.categories[1].points[0].gapReason")
                        .value("NOT_VERIFIED"))
                .andExpect(jsonPath("$.categories[6].points[0].gapReason")
                        .value("NOT_STARTED"));
    }

    @Test
    void answeredCyclesStopAtFirstIncompleteCycle() throws Exception {
        for (int sequence = 1; sequence <= 21; sequence++) {
            UUID assignment = assignment(alice.id(), sequence);
            if (sequence <= 7 || sequence >= 15) {
                verifiedAttempt(assignment, alice.id(), 1, "PASSED", 2);
            }
        }

        mvc.perform(get("/api/practice/idea-progress").with(user("progress-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastStartedCycle").value(3))
                .andExpect(jsonPath("$.lastAnsweredCycle").value(1))
                .andExpect(jsonPath("$.comparisonAvailable").value(false))
                .andExpect(jsonPath("$.categories[0].points[2].gapReason")
                        .value("CYCLE_INCOMPLETE"));
    }

    @Test
    void malformedPersistedProfileBecomesExplicitGap() throws Exception {
        UUID firstAttempt = null;
        for (int sequence = 1; sequence <= 7; sequence++) {
            UUID attempt = verifiedAttempt(
                    assignment(alice.id(), sequence), alice.id(), 1, "PASSED", 2);
            if (sequence == 1) firstAttempt = attempt;
        }
        jdbc.update("UPDATE practice_assessment SET idea_potential_dimensions_json='{}'"
                + " WHERE attempt_id=?", firstAttempt);

        mvc.perform(get("/api/practice/idea-progress").with(user("progress-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].points[0].gapReason")
                        .value("INVALID_PROFILE"))
                .andExpect(jsonPath("$.categories[0].points[0].ideaPotential").doesNotExist());
    }

    private UUID assignment(UUID ownerId, int sequence) {
        UUID id = UUID.randomUUID();
        int cycleNumber = ((sequence - 1) / 7) + 1;
        int cyclePosition = ((sequence - 1) % 7) + 1;
        String category = CATEGORIES.get(cyclePosition - 1);
        jdbc.update("""
                INSERT INTO practice_assignment(
                  id, owner_id, scenario_id, target_category_code, domain_text,
                  situation_text, hint_text, guidance_text, sequence_number,
                  cycle_number, cycle_position, created_at
                ) VALUES (?, ?, NULL, ?, 'Домен', ?, NULL, 'Ориентир', ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, ownerId, category, "Ситуация " + sequence,
                (long) sequence, cycleNumber, cyclePosition);
        return id;
    }

    private UUID verifiedAttempt(UUID assignmentId, UUID ownerId, int attemptNumber,
                                 String status, int score) throws Exception {
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO practice_attempt(
                  id, assignment_id, owner_id, parent_attempt_id, attempt_number,
                  question_text, rationale_text, solution_text, revised_fields_json,
                  status, created_at, completed_at
                ) VALUES (?, ?, ?, NULL, ?, 'Вопрос', 'Обоснование', 'Решение', '[]',
                          ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attemptId, assignmentId, ownerId, attemptNumber, status);
        JsonNode dimensions = json.readTree("""
                [
                  {"name":"impact","status":"SCORED","score":%1$d,"evidence":"impact"},
                  {"name":"questionAlignment","status":"SCORED","score":%1$d,"evidence":"alignment"},
                  {"name":"disruption","status":"SCORED","score":%1$d,"evidence":"disruption"},
                  {"name":"feasibility","status":"SCORED","score":%1$d,"evidence":"feasibility"}
                ]
                """.formatted(score));
        jdbc.update("""
                INSERT INTO practice_assessment(
                  id, attempt_id, outcome, completeness_status, step_results_json,
                  category_fit_score, category_fit_evidence, question_strength_score,
                  strength_dimensions_json, confidence, idea_potential_score,
                  idea_potential_dimensions_json, strengths_json, correction_what,
                  correction_why, correction_example, fields_to_revise_json,
                  feedback_text, prompt_key, prompt_version, schema_version,
                  model_id, latency_ms, created_at
                ) VALUES (?, ?, 'VERIFIED', 'PASS', '[]', 2, 'fit', 3, '[]', 'HIGH',
                          ?, ?, '[]', 'what', 'why', 'example', '[]', 'feedback',
                          'practice-assessment', 3, 'practice-assessment-v3',
                          'test-model', 1, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), attemptId, BigDecimal.valueOf(score), dimensions.toString());
        return attemptId;
    }
}
