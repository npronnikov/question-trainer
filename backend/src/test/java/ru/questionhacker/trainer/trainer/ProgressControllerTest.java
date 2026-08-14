package ru.questionhacker.trainer.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        "spring.datasource.url=jdbc:h2:mem:progress-api;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ProgressControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void resetUserData() {
        jdbc.update("DELETE FROM trainer_attempt");
        jdbc.update("DELETE FROM trainer_issuance");
        jdbc.update("DELETE FROM category_confusion");
        jdbc.update("DELETE FROM category_mastery");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        alice = users.create("progress-alice", null, "$2a$alice", Set.of("USER"), false);
        bob = users.create("progress-bob", null, "$2a$bob", Set.of("USER"), false);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO category_mastery(
                  owner_id, category_code, mastery_score, attempt_count, correct_count,
                  last_seen_at, next_review_at
                ) VALUES (?, 'INVERSION', 72, 10, 7, ?, ?)
                """, alice.id(), now, now.plusDays(2));
        jdbc.update("""
                INSERT INTO category_confusion(
                  owner_id, selected_category_code, correct_category_code,
                  confusion_count, last_confused_at
                ) VALUES (?, 'REFRAMING', 'INVERSION', 3, ?)
                """, alice.id(), now);
    }

    @Test
    void returnsAllCategoriesDirectedConfusionsAndRecommendation() throws Exception {
        mvc.perform(get("/api/progress").with(user("progress-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(7))
                .andExpect(jsonPath("$.categories[?(@.code == 'INVERSION')].score").value(72.0))
                .andExpect(jsonPath("$.categories[?(@.code == 'INVERSION')].level").value("CONFIDENT"))
                .andExpect(jsonPath("$.categories[?(@.code == 'INVERSION')].accuracyPercent").value(70.0))
                .andExpect(jsonPath("$.confusions[0].selectedCategory").value("REFRAMING"))
                .andExpect(jsonPath("$.confusions[0].correctCategory").value("INVERSION"))
                .andExpect(jsonPath("$.confusions[0].count").value(3))
                .andExpect(jsonPath("$.recommendation").isNotEmpty());

        mvc.perform(get("/api/progress").with(user("progress-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.code == 'INVERSION')].attempts").value(10));
    }

    @Test
    void progressIsIsolatedBetweenUsers() throws Exception {
        String body = mvc.perform(get("/api/progress").with(user("progress-bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(7))
                .andExpect(jsonPath("$.confusions.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("72.0");
    }

    @Test
    void userResetsOnlyOwnTrainerProgress() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID scenarioId = jdbc.queryForObject(
                "SELECT id FROM scenario WHERE content_target='TRAINER' ORDER BY external_key LIMIT 1",
                UUID.class);
        String category = jdbc.queryForObject(
                "SELECT category_code FROM scenario WHERE id=?", String.class, scenarioId);
        UUID issuanceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trainer_issuance(
                  id, owner_id, scenario_id, status, issued_at, expires_at, answered_at
                ) VALUES (?, ?, ?, 'ANSWERED', ?, ?, ?)
                """, issuanceId, alice.id(), scenarioId, now, now.plusMinutes(30), now);
        jdbc.update("""
                INSERT INTO trainer_attempt(
                  id, issuance_id, owner_id, scenario_id, selected_category_code,
                  rationale_text, correct, mastery_delta, created_at
                ) VALUES (?, ?, ?, ?, ?, 'Проверочное объяснение категории', TRUE, 8, ?)
                """, UUID.randomUUID(), issuanceId, alice.id(), scenarioId, category, now);
        jdbc.update("""
                INSERT INTO category_mastery(
                  owner_id, category_code, mastery_score, attempt_count, correct_count
                ) VALUES (?, 'REFRAMING', 55, 4, 2)
                """, bob.id());

        mvc.perform(delete("/api/progress").with(user("progress-alice")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.attempts > 0)]").isEmpty())
                .andExpect(jsonPath("$.confusions").isEmpty());

        for (String table : Set.of(
                "trainer_attempt", "trainer_issuance", "category_confusion", "category_mastery")) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE owner_id=?", Integer.class, alice.id()))
                    .isZero();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM category_mastery WHERE owner_id=?", Integer.class, bob.id()))
                .isEqualTo(1);
    }
}
