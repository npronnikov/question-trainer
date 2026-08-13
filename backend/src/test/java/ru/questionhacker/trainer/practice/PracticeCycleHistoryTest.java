package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import ru.questionhacker.trainer.auth.UserAccountRepository;
import ru.questionhacker.trainer.auth.AppUser;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:practice-history;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class PracticeCycleHistoryTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private ObjectMapper json;

    private AppUser alice;

    @BeforeEach
    void resetUserData() {
        jdbc.update("DELETE FROM practice_draft");
        jdbc.update("DELETE FROM practice_assessment");
        jdbc.update("DELETE FROM practice_attempt");
        jdbc.update("DELETE FROM practice_assignment");
        jdbc.update("DELETE FROM moderation_action");
        jdbc.update("DELETE FROM scenario_candidate");
        jdbc.update("UPDATE practice_example SET published=TRUE");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        alice = users.create("history-alice", null, "$2a$alice", Set.of("USER"), false);
        users.create("history-bob", null, "$2a$bob", Set.of("USER"), false);
    }

    @Test
    void newAssignmentIsOneOwnerOnlyDraftCycle() throws Exception {
        UUID assignment = assignment("history-alice", "INVERSION");

        mvc.perform(get("/api/practice/cycles").with(user("history-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assignmentId").value(assignment.toString()))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].attemptCount").value(0));

        mvc.perform(get("/api/practice/cycles").with(user("history-bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/practice/cycles/{id}", assignment).with(user("history-bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void autosaveRestoresAllFieldsAndMovesCycleToTop() throws Exception {
        UUID older = assignment("history-alice", "INVERSION");
        pass(older, alice.id());
        UUID newer = assignment("history-alice", "HYPERBOLE");
        jdbc.update("UPDATE practice_assignment SET created_at=DATEADD('DAY', -1, created_at) WHERE id=?", newer);
        jdbc.update("UPDATE practice_draft SET updated_at=DATEADD('DAY', -1, updated_at) WHERE assignment_id=?", newer);

        String question = "Какой вопрос изменит рамку и покажет скрытое ограничение этой ситуации?";
        String body = json.createObjectNode()
                .putNull("baseAttemptId")
                .put("question", question)
                .put("answer", "Пока это сохранённый ответ пользователя без отправки на оценку.")
                .put("reasoning", "Черновик хранит развёрнутую цепочку рассуждения на стороне сервера.")
                .put("solution", "Вернуться к циклу после перезагрузки и продолжить работу.")
                .toString();
        mvc.perform(put("/api/practice/cycles/{id}/draft", newer)
                        .with(user("history-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value(question));

        mvc.perform(get("/api/practice/cycles/{id}", newer).with(user("history-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.question").value(question))
                .andExpect(jsonPath("$.editor.question").value(question))
                .andExpect(jsonPath("$.editor.editableFields.length()").value(4))
                .andExpect(jsonPath("$.attempts").isEmpty());
        mvc.perform(get("/api/practice/cycles").with(user("history-alice")))
                .andExpect(jsonPath("$[0].assignmentId").value(newer.toString()))
                .andExpect(jsonPath("$[1].assignmentId").value(older.toString()));

        mvc.perform(put("/api/practice/cycles/{id}/draft", newer)
                        .with(user("history-bob")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void randomExampleReturnsCompletePublishedServerContent() throws Exception {
        jdbc.update("UPDATE practice_example SET published=FALSE WHERE category_code <> 'BACKCASTING'");

        String response = mvc.perform(get("/api/practice/examples/random").with(user("history-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCategory.code").value("BACKCASTING"))
                .andExpect(jsonPath("$.targetCategory.name").value("Backcasting"))
                .andExpect(jsonPath("$.situation").isNotEmpty())
                .andExpect(jsonPath("$.question").isNotEmpty())
                .andExpect(jsonPath("$.answer").isNotEmpty())
                .andExpect(jsonPath("$.reasoning").isNotEmpty())
                .andExpect(jsonPath("$.solution").isNotEmpty())
                .andExpect(jsonPath("$.recommendation").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode example = json.readTree(response);
        assertThat(example.path("recommendation").asText()).contains("backcasting");
    }

    private UUID assignment(String username, String category) throws Exception {
        publishForPractice(category, 0);
        String response = mvc.perform(post("/api/practice/assignments")
                        .with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetCategory.code").value(category))
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

    private void pass(UUID assignmentId, UUID ownerId) {
        jdbc.update("DELETE FROM practice_draft WHERE assignment_id=?", assignmentId);
        jdbc.update("""
                INSERT INTO practice_attempt(
                  id, assignment_id, owner_id, parent_attempt_id, attempt_number,
                  question_text, answer_text, reasoning_text, solution_text,
                  revised_fields_json, status, created_at, completed_at
                ) VALUES (?, ?, ?, NULL, 1, 'question', 'answer', 'reasoning',
                          'solution', '[]', 'PASSED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), assignmentId, ownerId);
    }
}
