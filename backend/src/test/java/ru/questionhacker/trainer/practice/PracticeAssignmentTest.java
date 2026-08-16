package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ru.questionhacker.trainer.auth.AppUser;
import ru.questionhacker.trainer.auth.UserAccountRepository;
import ru.questionhacker.trainer.moderation.ScenarioGenerationGateway;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:practice-assignment;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class PracticeAssignmentTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private ScenarioGenerationGateway generator;

    private AppUser alice;
    private AppUser bob;

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
        alice = users.create("practice-alice", null, "$2a$alice", Set.of("USER"), false);
        bob = users.create("practice-bob", null, "$2a$bob", Set.of("USER"), false);
    }

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
    void unfinishedDraftDoesNotBlockAnotherAssignment() throws Exception {
        publishForPractice("INVERSION", 0);
        publishForPractice("HYPERBOLE", 0);
        assignment("practice-alice");

        UUID second = assignment("practice-alice");

        assertThat(category(second)).isEqualTo("HYPERBOLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVALUATING", "NEEDS_REVISION", "UNVERIFIED"})
    void unfinishedLatestStatusDoesNotBlockAnotherAssignment(String attemptStatus) throws Exception {
        publishForPractice("INVERSION", 0);
        publishForPractice("HYPERBOLE", 0);
        UUID assignmentId = assignment("practice-alice");
        replaceDraftWithAttempt(assignmentId, alice.id(), attemptStatus);

        UUID second = assignment("practice-alice");

        assertThat(category(second)).isEqualTo("HYPERBOLE");
    }

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
            replaceDraftWithAttempt(assignmentId, alice.id(), "PASSED");
        }

        assertThat(issued).containsExactly(
                "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
                "PROVOCATION", "REFRAMING", "SIMPLIFICATION", "INVERSION");
        assertThat(scenarios).hasSize(8);
    }

    @Test
    void categoryPositionIsIndependentForEachUser() throws Exception {
        publishForPractice("INVERSION", 0);

        UUID aliceAssignment = assignment("practice-alice");
        UUID bobAssignment = assignment("practice-bob");

        assertThat(category(aliceAssignment)).isEqualTo("INVERSION");
        assertThat(category(bobAssignment)).isEqualTo("INVERSION");
    }

    @Test
    void assignmentsPersistTwoCanonicalSevenCaseCyclesPerOwner() throws Exception {
        List<String> categories = List.of(
                "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
                "PROVOCATION", "REFRAMING", "SIMPLIFICATION");
        categories.forEach(category -> {
            publishForPractice(category, 0);
            publishForPractice(category, 1);
        });

        List<UUID> assignments = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            assignments.add(assignment("practice-alice"));
        }

        List<java.util.Map<String, Object>> coordinates = jdbc.queryForList("""
                SELECT sequence_number, cycle_number, cycle_position, target_category_code
                FROM practice_assignment
                WHERE owner_id=?
                ORDER BY sequence_number
                """, alice.id());
        assertThat(coordinates).extracting(row -> row.get("TARGET_CATEGORY_CODE"))
                .containsExactlyElementsOf(java.util.stream.Stream.concat(
                        categories.stream(), categories.stream()).toList());
        assertThat(coordinates).extracting(row -> ((Number) row.get("SEQUENCE_NUMBER")).longValue())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L,
                        8L, 9L, 10L, 11L, 12L, 13L, 14L);
        assertThat(coordinates).extracting(row -> ((Number) row.get("CYCLE_NUMBER")).intValue())
                .containsExactly(1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2);
        assertThat(coordinates).extracting(row -> ((Number) row.get("CYCLE_POSITION")).intValue())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 1, 2, 3, 4, 5, 6, 7);

        publishForPractice("INVERSION", 2);
        UUID bobAssignment = assignment("practice-bob");
        assertThat(jdbc.queryForObject(
                "SELECT sequence_number FROM practice_assignment WHERE id=?",
                Long.class, bobAssignment)).isEqualTo(1L);
    }

    @Test
    void assignmentReadRemainsOwnerOnly() throws Exception {
        publishForPractice("INVERSION", 0);
        UUID assignmentId = assignment("practice-alice");

        mvc.perform(get("/api/practice/assignments/{id}", assignmentId)
                        .with(user("practice-bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignmentUsesOnlyPracticeScenarioAndSnapshotsItsHint() throws Exception {
        UUID trainerScenario = jdbc.queryForObject("""
                SELECT id FROM scenario WHERE category_code='INVERSION' AND published=TRUE
                ORDER BY external_key LIMIT 1
                """, UUID.class);
        jdbc.update("""
                UPDATE scenario SET content_target='TRAINER', difficulty='L2',
                  hint_text='Тренажёрная подсказка не должна попасть в практику.'
                WHERE id=?
                """, trainerScenario);
        jdbc.update("""
                INSERT INTO scenario_candidate(
                  id, status, content_target, version_number, category_code,
                  rejection_reasons_json, warnings_json, published_scenario_id,
                  created_at, updated_at
                ) VALUES (?, 'PUBLISHED', 'TRAINER', 1, 'INVERSION', '[]', '[]', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), trainerScenario);
        UUID practiceScenario = publishForPractice(
                "INVERSION", 1, "Практическая подсказка сохранена вместе с назначением.");
        jdbc.update("UPDATE scenario SET difficulty='L1' WHERE id=?", practiceScenario);

        String response = mvc.perform(post("/api/practice/assignments")
                        .with(user("practice-alice")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hint").value(
                        "Практическая подсказка сохранена вместе с назначением."))
                .andReturn().getResponse().getContentAsString();
        UUID assignmentId = UUID.fromString(json.readTree(response).path("assignmentId").asText());

        assertThat(jdbc.queryForObject(
                "SELECT scenario_id FROM practice_assignment WHERE id=?", UUID.class, assignmentId))
                .isEqualTo(practiceScenario);
        assertThat(jdbc.queryForObject(
                "SELECT hint_text FROM practice_assignment WHERE id=?", String.class, assignmentId))
                .isEqualTo("Практическая подсказка сохранена вместе с назначением.");
    }

    private UUID publishForPractice(String category, int offset) {
        return publishForPractice(category, offset,
                "Посмотрите на ситуацию под другим углом, не называя готовую технику.");
    }

    private UUID publishForPractice(String category, int offset, String hint) {
        UUID scenarioId = jdbc.queryForObject("""
                SELECT id FROM scenario WHERE category_code=? AND published=TRUE
                ORDER BY external_key LIMIT 1 OFFSET ?
                """, UUID.class, category, offset);
        jdbc.update("UPDATE scenario SET content_target='PRACTICE', hint_text=? WHERE id=?",
                hint, scenarioId);
        jdbc.update("""
                INSERT INTO scenario_candidate(
                  id, status, content_target, version_number, category_code,
                  rejection_reasons_json, warnings_json, published_scenario_id,
                  created_at, updated_at
                ) VALUES (?, 'PUBLISHED', 'PRACTICE', 1, ?, '[]', '[]', ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), category, scenarioId);
        return scenarioId;
    }

    private UUID assignment(String username) throws Exception {
        String response = mvc.perform(post("/api/practice/assignments")
                        .with(user(username)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).path("assignmentId").asText());
    }

    private void replaceDraftWithAttempt(UUID assignmentId, UUID ownerId, String status) {
        jdbc.update("DELETE FROM practice_draft WHERE assignment_id=?", assignmentId);
        jdbc.update("""
                INSERT INTO practice_attempt(
                  id, assignment_id, owner_id, parent_attempt_id, attempt_number,
                  question_text, rationale_text, solution_text,
                  revised_fields_json, status, created_at, completed_at
                ) VALUES (?, ?, ?, NULL, 1, 'question', 'rationale',
                          'solution', '[]', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), assignmentId, ownerId, status);
    }

    private String category(UUID assignmentId) {
        return jdbc.queryForObject(
                "SELECT target_category_code FROM practice_assignment WHERE id=?",
                String.class, assignmentId);
    }
}
