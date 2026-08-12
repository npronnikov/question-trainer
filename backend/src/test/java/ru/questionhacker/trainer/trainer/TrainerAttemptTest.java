package ru.questionhacker.trainer.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.MockMvc;

import ru.questionhacker.trainer.auth.AppUser;
import ru.questionhacker.trainer.auth.UserAccountRepository;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:trainer-attempt;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class TrainerAttemptTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private ObjectMapper json;

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
        alice = users.create("attempt-alice", null, "$2a$alice", Set.of("USER"), false);
        bob = users.create("attempt-bob", null, "$2a$bob", Set.of("USER"), false);
    }

    @Test
    void scoresCorrectAnswerStoresRationaleAndIsIdempotent() throws Exception {
        UUID issuanceId = issue("attempt-alice", "L2");
        String correct = correctCategory(issuanceId);
        String body = answerBody(issuanceId, correct,
                "Основная операция меняет рамку именно указанным в вопросе способом.");

        String first = mvc.perform(post("/api/trainer/attempts")
                        .with(user("attempt-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(true))
                .andExpect(jsonPath("$.correctCategory").value(correct))
                .andExpect(jsonPath("$.operationExplanation").isNotEmpty())
                .andExpect(jsonPath("$.nextStep").isNotEmpty())
                .andExpect(jsonPath("$.mastery.score").isNumber())
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/api/trainer/attempts")
                        .with(user("attempt-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).path("attemptId").asText())
                .isEqualTo(json.readTree(first).path("attemptId").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trainer_attempt", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM category_mastery WHERE owner_id=? AND category_code=?",
                Integer.class, alice.id(), correct)).isEqualTo(1);
    }

    @Test
    void wrongAnswerUpdatesDirectedConfusionAndReturnsSpecificContrast() throws Exception {
        UUID issuanceId = issue("attempt-alice", "L3");
        String correct = correctCategory(issuanceId);
        String selected = jdbc.queryForObject("""
                SELECT category_code FROM scenario_option
                WHERE scenario_id=(SELECT scenario_id FROM trainer_issuance WHERE id=?)
                  AND category_code<>?
                ORDER BY sort_order LIMIT 1
                """, String.class, issuanceId, correct);

        mvc.perform(post("/api/trainer/attempts")
                        .with(user("attempt-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(issuanceId, selected,
                                "Я выбрал эту категорию, потому что увидел похожее движение мысли.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(false))
                .andExpect(jsonPath("$.selectedCategory").value(selected))
                .andExpect(jsonPath("$.correctCategory").value(correct))
                .andExpect(jsonPath("$.contrast").isNotEmpty());

        assertThat(jdbc.queryForObject("""
                SELECT confusion_count FROM category_confusion
                WHERE owner_id=? AND selected_category_code=? AND correct_category_code=?
                """, Integer.class, alice.id(), selected, correct)).isEqualTo(1);
    }

    @Test
    void rationaleIsRequiredButItsWordingDoesNotDetermineCorrectness() throws Exception {
        UUID issuanceId = issue("attempt-alice", "L1");
        String correct = correctCategory(issuanceId);

        mvc.perform(post("/api/trainer/attempts")
                        .with(user("attempt-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(issuanceId, correct, "")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/trainer/attempts")
                        .with(user("attempt-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(issuanceId, correct,
                                "Моё объяснение сохраняется для сравнения, но сервер проверяет выбранную категорию.")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(true));
    }

    @Test
    void foreignAndExpiredIssuancesCannotBeAnswered() throws Exception {
        UUID foreign = issue("attempt-bob", "L1");
        String foreignCorrect = correctCategory(foreign);
        mvc.perform(post("/api/trainer/attempts")
                        .with(user("attempt-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(foreign, foreignCorrect,
                                "Попытка ответить на карточку другого пользователя должна быть скрыта.")))
                .andExpect(status().isNotFound());

        UUID expired = issue("attempt-alice", "L1");
        jdbc.update("UPDATE trainer_issuance SET expires_at=? WHERE id=?",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), expired);
        mvc.perform(post("/api/trainer/attempts")
                        .with(user("attempt-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(expired, correctCategory(expired),
                                "Просроченная выдача не должна менять прогресс пользователя.")))
                .andExpect(status().isGone());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM trainer_issuance WHERE id=?", String.class, expired))
                .isEqualTo("EXPIRED");
    }

    @Test
    void rejectsCategoryThatWasNotOfferedWithoutChangingProgress() throws Exception {
        UUID issuanceId = issue("attempt-alice", "L1");
        String notOffered = jdbc.queryForObject("""
                SELECT code FROM category
                WHERE code NOT IN (
                  SELECT category_code FROM scenario_option
                  WHERE scenario_id=(SELECT scenario_id FROM trainer_issuance WHERE id=?)
                )
                ORDER BY sort_order LIMIT 1
                """, String.class, issuanceId);

        mvc.perform(post("/api/trainer/attempts")
                        .with(user("attempt-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody(issuanceId, notOffered,
                                "Категория существует, но сервер не выдавал её в этой карточке.")))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trainer_attempt", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category_mastery", Integer.class))
                .isZero();
    }

    private UUID issue(String username, String difficulty) throws Exception {
        String response = mvc.perform(get("/api/trainer/next")
                        .param("difficulty", difficulty).with(user(username)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).path("issuanceId").asText());
    }

    private String correctCategory(UUID issuanceId) {
        return jdbc.queryForObject("""
                SELECT s.category_code
                FROM trainer_issuance ti JOIN scenario s ON s.id=ti.scenario_id
                WHERE ti.id=?
                """, String.class, issuanceId);
    }

    private String answerBody(UUID issuanceId, String selectedCategory, String rationale) throws Exception {
        JsonNode body = json.createObjectNode()
                .put("issuanceId", issuanceId.toString())
                .put("selectedCategory", selectedCategory)
                .put("rationale", rationale);
        return json.writeValueAsString(body);
    }
}
