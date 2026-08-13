package ru.questionhacker.trainer.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ru.questionhacker.trainer.auth.AppUser;
import ru.questionhacker.trainer.auth.UserAccountRepository;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:scenario-moderation;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ScenarioModerationTest {

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

    private AppUser admin;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM moderation_action");
        jdbc.update("DELETE FROM scenario_candidate");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        admin = users.create("queue-admin", null, "$2a$admin", Set.of("USER", "ADMIN"), false);
        users.create("queue-user", null, "$2a$user", Set.of("USER"), false);
    }

    @Test
    void generationScreensBadCasesBeforeReviewQueue() throws Exception {
        when(generator.generate(anyList(), anyString())).thenReturn(List.of(
                goodDraftForSituation("Новая команда готовит запуск сервиса, но согласования скрывают главные риски и откладывают первые проверки."),
                new ScenarioDraft("INVERSION", "REFRAMING", "L2", "ПРОДУКТ",
                        "Команда обсуждает тупик, но кейс одновременно требует двух главных техник и не имеет однозначной операции.",
                        "Какие действия приведут запуск к провалу?", "Подумайте об инверсии.",
                        List.of("INVERSION", "HYPERBOLE", "REFRAMING", "SIMPLIFICATION"),
                        "INVERSION", "Объяснение операции инверсии достаточно конкретно.", null, null),
                goodDraftForSituation("Команда хочет убить клиента и гарантированно причинить ему вред во время проверки продукта.")));

        mvc.perform(post("/api/admin/scenario-candidates/generate")
                        .with(user("queue-admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3,\"model\":\"gpt-5.6-terra[high]\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$[1].status").value("AUTO_REJECTED"))
                .andExpect(jsonPath("$[1].rejectionReasons[0]").value("MULTIPLE_TECHNIQUES"))
                .andExpect(jsonPath("$[2].status").value("AUTO_REJECTED"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_candidate WHERE status='PENDING_REVIEW'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM moderation_action", Integer.class)).isEqualTo(3);
    }

    @Test
    void onlyAdminCanReadOrMutateQueue() throws Exception {
        mvc.perform(get("/api/admin/scenario-candidates")
                        .with(user("queue-user").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/scenario-candidates/generate")
                        .with(user("queue-user").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvalPublishesExactlyOnceAndAuditsOptimisticDecision() throws Exception {
        when(generator.generate(anyList(), anyString())).thenReturn(List.of(goodDraftForSituation(
                "Команда готовит новый процесс запуска и хочет заранее обнаружить действия, которые гарантированно сорвут результат.")));
        JsonNode candidate = generateOne();
        UUID id = UUID.fromString(candidate.path("id").asText());
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM scenario WHERE published=TRUE", Integer.class);

        mvc.perform(post("/api/admin/scenario-candidates/{id}/approve", id)
                        .with(user("queue-admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedScenarioId").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scenario WHERE published=TRUE", Integer.class))
                .isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM moderation_action
                WHERE candidate_id=? AND actor_id=? AND action_type='APPROVE_PUBLISH'
                """, Integer.class, id, admin.id())).isEqualTo(1);

        mvc.perform(post("/api/admin/scenario-candidates/{id}/approve", id)
                        .with(user("queue-admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scenario WHERE published=TRUE", Integer.class))
                .isEqualTo(before + 1);
    }

    @Test
    void rejectionRequiresReasonAndRejectedCandidateNeverPublishes() throws Exception {
        when(generator.generate(anyList(), anyString())).thenReturn(List.of(goodDraftForSituation(
                "Команда улучшает стабильный процесс, но продолжает добавлять проверки и больше не видит ядро создаваемой ценности.")));
        UUID id = UUID.fromString(generateOne().path("id").asText());
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM scenario WHERE published=TRUE", Integer.class);

        mvc.perform(post("/api/admin/scenario-candidates/{id}/reject", id)
                        .with(user("queue-admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/scenario-candidates/{id}/reject", id)
                        .with(user("queue-admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"reason\":\"WEAK_LEARNING_VALUE\",\"comment\":\"Слишком очевидно\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scenario WHERE published=TRUE", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void editedCandidateRunsAutomaticScreeningAgain() throws Exception {
        when(generator.generate(anyList(), anyString())).thenReturn(List.of(goodDraftForSituation(
                "Команда проектирует новый процесс и хочет заранее найти действия, которые гарантированно разрушат полезный результат.")));
        UUID id = UUID.fromString(generateOne().path("id").asText());

        mvc.perform(put("/api/admin/scenario-candidates/{id}", id)
                        .with(user("queue-admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Object() {
                            public final int expectedVersion = 1;
                            public final ScenarioDraft draft = new ScenarioDraft(
                                    "INVERSION", "REFRAMING", "L2", "ПРОДУКТ",
                                    "Команда проектирует новый процесс, но редактор смешал две главные техники и сделал категорию неоднозначной.",
                                    "Какие три действия гарантированно приведут процесс к провалу?",
                                    "Найдите причинные механизмы нежелательного исхода.",
                                    List.of("INVERSION", "HYPERBOLE", "REFRAMING", "SIMPLIFICATION"),
                                    "INVERSION", "Вопрос меняет направление цели и исследует провал.", null, null);
                        })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTO_REJECTED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.rejectionReasons[0]").value("MULTIPLE_TECHNIQUES"));
    }

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

    private JsonNode generateOne() throws Exception {
        String response = mvc.perform(post("/api/admin/scenario-candidates/generate")
                        .with(user("queue-admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"model\":\"gpt-5.6-terra[high]\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get(0);
    }

    private ScenarioDraft goodDraftForSituation(String situation) {
        return new ScenarioDraft("INVERSION", null, "L2", "ПРОДУКТ", situation,
                "Какие три конкретных действия гарантированно приведут этот процесс к провалу?",
                "Найдите причинные механизмы нежелательного исхода, не называя ответ.",
                List.of("INVERSION", "HYPERBOLE", "REFRAMING", "SIMPLIFICATION"),
                "INVERSION", "Вопрос переворачивает цель и ищет конкретные механизмы провала.",
                null, null);
    }

    private ScenarioDraft goodDraftForCategory(String category) {
        List<String> options = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(category),
                        java.util.stream.Stream.of(
                                        "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE",
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
}
