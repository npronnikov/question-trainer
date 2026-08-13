package ru.questionhacker.trainer.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        "spring.datasource.url=jdbc:h2:mem:trainer-issuance;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class TrainerIssuanceTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    private AppUser alice;

    @BeforeEach
    void resetUserData() {
        jdbc.update("DELETE FROM trainer_attempt");
        jdbc.update("DELETE FROM trainer_issuance");
        jdbc.update("DELETE FROM category_confusion");
        jdbc.update("DELETE FROM category_mastery");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        alice = users.create("trainer-alice", null, "$2a$alice", Set.of("USER"), false);
    }

    @Test
    void nextCardIsBoundToUserAndNeverLeaksTheAnswer() throws Exception {
        mvc.perform(get("/api/trainer/next")
                        .param("difficulty", "L3")
                        .with(user("trainer-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuanceId").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.card.difficulty").value("L3"))
                .andExpect(jsonPath("$.card.situation").isNotEmpty())
                .andExpect(jsonPath("$.card.question").isNotEmpty())
                .andExpect(jsonPath("$.card.options.length()").value(4))
                .andExpect(jsonPath("$.card.correctCategory").doesNotExist())
                .andExpect(jsonPath("$.card.explanation").doesNotExist())
                .andExpect(jsonPath("$.card.contrast").doesNotExist());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM trainer_issuance WHERE owner_id=? AND status='ISSUED'",
                Integer.class, alice.id())).isEqualTo(1);
    }

    @Test
    void invalidDifficultyIsRejectedAsBadRequest() throws Exception {
        mvc.perform(get("/api/trainer/next")
                        .param("difficulty", "L9")
                        .with(user("trainer-alice")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void practiceScenarioWithoutTrainerFieldsIsNeverIssued() throws Exception {
        UUID scenarioId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO scenario(
                  id, external_key, content_target, category_code, difficulty, domain_text,
                  situation_text, question_text, hint_text, explanation_text, content_hash,
                  published, created_at, updated_at
                ) VALUES (?, '000-practice-only', 'PRACTICE', 'INVERSION', 'L1', 'ПРОДУКТ',
                          'Команда обсуждает подробную практическую ситуацию без готового вопроса для тренажёра.',
                          NULL, 'Подсказка для практики.', NULL, ?, TRUE,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, scenarioId, "practice-only-" + scenarioId);

        mvc.perform(get("/api/trainer/next").with(user("trainer-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.card.id").value(org.hamcrest.Matchers.not(scenarioId.toString())))
                .andExpect(jsonPath("$.card.question").isNotEmpty())
                .andExpect(jsonPath("$.card.options.length()").value(4));
    }
}
