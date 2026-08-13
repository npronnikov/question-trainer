package ru.questionhacker.trainer.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ru.questionhacker.trainer.AcpGateway;
import ru.questionhacker.trainer.AppProperties;

class AcpScenarioGenerationGatewayTest {

    private static final String VALID_INVERSION = """
            [{"category":"INVERSION","secondaryCategory":null,"difficulty":"L2","domain":"ПРОДУКТ","situation":"Команда обсуждает реалистичную рабочую ситуацию достаточной длины и ищет новое безопасное решение без упоминания брендов.","question":"Какие действия гарантированно приведут процесс к провалу?","hint":"Найдите причинные механизмы нежелательного исхода.","options":["INVERSION","HYPERBOLE","REFRAMING","SIMPLIFICATION"],"correctCategory":"INVERSION","explanation":"Вопрос переворачивает цель и исследует конкретные причины возможного провала.","confusedWith":null,"contrast":null}]
            """;

    private final AcpGateway acp = org.mockito.Mockito.mock(AcpGateway.class);
    private final AppProperties properties = new AppProperties(
            new AppProperties.Acp(false, false, "codex", List.of(), ".",
                    Duration.ofSeconds(30), 1024, List.of(),
                    List.of("test-model"), "test-model"),
            new AppProperties.Chat(12000, 24),
            new AppProperties.Admin("", "", ""));
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void promptContainsExactOrderedCategories() {
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn(VALID_INVERSION);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        gateway.generate(List.of("INVERSION"), "test-model");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(acp).ask(prompt.capture(), eq("test-model"), any());
        assertThat(prompt.getValue()).contains("[\"INVERSION\"]");
        assertThat(prompt.getValue()).doesNotContain("{{categories}}", "{{count}}");
    }

    @Test
    void rejectsAcpCategoryThatDoesNotMatchRequestedPosition() {
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn("""
                [{"category":"HYPERBOLE","secondaryCategory":null,"difficulty":"L2","domain":"ПРОДУКТ","situation":"Команда обсуждает реалистичную рабочую ситуацию достаточной длины и ищет новое безопасное решение без упоминания брендов.","question":"Что изменится, если увеличить ограничение в десять раз?","hint":"Измените масштаб одного параметра.","options":["HYPERBOLE","INVERSION","REFRAMING","SIMPLIFICATION"],"correctCategory":"HYPERBOLE","explanation":"Вопрос доводит один параметр до экстремального значения и меняет механику решения.","confusedWith":null,"contrast":null}]
                """);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThatThrownBy(() -> gateway.generate(List.of("INVERSION"), "test-model"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void repairsStrayEmptyObjectMemberFromAcpOutput() {
        String malformed = VALID_INVERSION.replace("\"contrast\":null}", "\"contrast\":null,\"\"}");
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn(malformed);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThat(gateway.generate(List.of("INVERSION"), "test-model"))
                .singleElement()
                .extracting(ScenarioDraft::category)
                .isEqualTo("INVERSION");
        verify(acp).ask(anyString(), eq("test-model"), any());
    }

    @Test
    void repairsStrayEmptyObjectMemberBetweenValidFields() {
        String malformed = VALID_INVERSION.replace(
                "\"confusedWith\":null,\"contrast\"",
                "\"confusedWith\":null, \"\" , \"contrast\"");
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn(malformed);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThat(gateway.generate(List.of("INVERSION"), "test-model"))
                .hasSize(1);
        verify(acp).ask(anyString(), eq("test-model"), any());
    }

    @Test
    void repairsMultipleWhitespaceSeparatedMembersInOneResponse() {
        String object = VALID_INVERSION.strip();
        object = object.substring(1, object.length() - 1)
                .replace("\"contrast\":null}", "\"contrast\":null, \n \"\" }");
        when(acp.ask(anyString(), eq("test-model"), any()))
                .thenReturn("[" + object + "," + object + "]");
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThat(gateway.generate(List.of("INVERSION", "INVERSION"), "test-model"))
                .hasSize(2);
        verify(acp).ask(anyString(), eq("test-model"), any());
    }

    @Test
    void localRepairPreservesSimilarTextInsideJsonString() {
        String malformed = VALID_INVERSION
                .replace("без упоминания брендов.",
                        "без упоминания брендов и сохраняет фрагмент ,\\\"\\\"} внутри текста.")
                .replace("\"contrast\":null}", "\"contrast\":null,\"\"}");
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn(malformed);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThat(gateway.generate(List.of("INVERSION"), "test-model"))
                .singleElement()
                .extracting(ScenarioDraft::situation)
                .asString()
                .contains(",\"\"} внутри текста");
        verify(acp).ask(anyString(), eq("test-model"), any());
    }

    @Test
    void retriesGenerationOnceWhenJsonCannotBeRepairedLocally() {
        when(acp.ask(anyString(), eq("test-model"), any()))
                .thenReturn("[{\"category\"}]", VALID_INVERSION);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThat(gateway.generate(List.of("INVERSION"), "test-model"))
                .hasSize(1);
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(acp, times(2)).ask(prompts.capture(), eq("test-model"), any());
        assertThat(prompts.getAllValues().get(1))
                .contains("Предыдущая попытка содержала некорректный JSON");
    }

    @Test
    void reportsBadGatewayAfterTwoInvalidGeneratorResponses() {
        when(acp.ask(anyString(), eq("test-model"), any()))
                .thenReturn("[{\"category\"}]");
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThatThrownBy(() -> gateway.generate(List.of("INVERSION"), "test-model"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(acp, times(2)).ask(anyString(), eq("test-model"), any());
    }

    @Test
    void practicePromptReceivesServerCategoryAndReturnsOnlyPracticeFields() {
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn("""
                {"domain":"ПРОДУКТ","situation":"Команда готовит запуск и должна самостоятельно найти новый ход в достаточно подробной безопасной рабочей ситуации.","hint":"Исследуйте противоположное направление цели, не называя технику."}
                """);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        PracticeScenarioDraft result = gateway.generatePractice("INVERSION", "test-model");

        assertThat(result.domain()).isEqualTo("ПРОДУКТ");
        assertThat(result.situation()).contains("Команда готовит запуск");
        assertThat(result.hint()).isNotBlank();
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(acp).ask(prompt.capture(), eq("test-model"), any());
        assertThat(prompt.getValue()).contains("INVERSION").doesNotContain("{{category}}");
    }

    @Test
    void practiceResponseRejectsFieldsOutsideStrictSchema() {
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn("""
                {"domain":"ПРОДУКТ","situation":"Команда готовит запуск и должна самостоятельно найти новый ход в достаточно подробной безопасной рабочей ситуации.","hint":"Исследуйте противоположное направление цели, не называя технику.","category":"INVERSION"}
                """);
        var gateway = new AcpScenarioGenerationGateway(acp, properties, json);

        assertThatThrownBy(() -> gateway.generatePractice("INVERSION", "test-model"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(acp, times(2)).ask(anyString(), eq("test-model"), any());
    }

}
