package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

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
        "spring.datasource.url=jdbc:h2:mem:practice-administration;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class PracticeAdministrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    private AppUser owner;

    @BeforeEach
    void resetUserData() {
        jdbc.update("DELETE FROM practice_draft");
        jdbc.update("DELETE FROM practice_assessment");
        jdbc.update("DELETE FROM practice_attempt");
        jdbc.update("DELETE FROM practice_assignment");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        owner = users.create("practice-owner", null, "$2a$owner", Set.of("USER"), false);
    }

    @Test
    void ordinaryUserCannotClearPracticeCycles() throws Exception {
        mvc.perform(delete("/api/admin/practice/cycles")
                        .with(user("ordinary").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminClearsAllPracticeHistoryWithoutDeletingCatalogAndCanRepeat() throws Exception {
        seedHistory();
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

        mvc.perform(delete("/api/admin/practice/cycles")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCycles").value(0));
    }

    private void seedHistory() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID draftAssignment = UUID.randomUUID();
        UUID assessedAssignment = UUID.randomUUID();
        insertAssignment(draftAssignment, now, 1);
        insertAssignment(assessedAssignment, now, 2);
        jdbc.update("""
                INSERT INTO practice_draft(
                  assignment_id, owner_id, base_attempt_id, question_text,
                  rationale_text, solution_text, updated_at
                ) VALUES (?, ?, NULL, '', '', '', ?)
                """, draftAssignment, owner.id(), now);

        UUID attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO practice_attempt(
                  id, assignment_id, owner_id, parent_attempt_id, attempt_number,
                  question_text, rationale_text, solution_text,
                  revised_fields_json, status, requested_model, idempotency_key,
                  created_at, completed_at
                ) VALUES (?, ?, ?, NULL, 1, 'question', 'rationale',
                          'solution', '[]', 'PASSED', 'test-model', 'cleanup-test', ?, ?)
                """, attempt, assessedAssignment, owner.id(), now, now);
        jdbc.update("""
                INSERT INTO practice_assessment(
                  id, attempt_id, outcome, completeness_status, step_results_json,
                  category_fit_score, category_fit_evidence, confused_with,
                  question_strength_score, strength_dimensions_json, confidence,
                  strengths_json, correction_what, correction_why, correction_example,
                  fields_to_revise_json, feedback_text, prompt_key, prompt_version,
                  schema_version, model_id, latency_ms, failure_reason, created_at
                ) VALUES (?, ?, 'VERIFIED', 'PASS', '[]', 2, 'fits', NULL,
                          3, '{}', 'HIGH', '[]', '', '', '', '[]', 'accepted',
                          'practice-assessment', 1, 'practice-assessment-v1',
                          'test-model', 1, NULL, ?)
                """, UUID.randomUUID(), attempt, now);
    }

    private void insertAssignment(UUID id, OffsetDateTime now, int sequenceNumber) {
        jdbc.update("""
                INSERT INTO practice_assignment(
                  id, owner_id, scenario_id, target_category_code, domain_text,
                  situation_text, hint_text, guidance_text, created_at,
                  sequence_number, cycle_number, cycle_position
                ) VALUES (?, ?, NULL, 'INVERSION', 'Домен', 'Ситуация',
                          'Подсказка', 'Ориентир', ?, ?, 1, ?)
                """, id, owner.id(), now, sequenceNumber, sequenceNumber);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
