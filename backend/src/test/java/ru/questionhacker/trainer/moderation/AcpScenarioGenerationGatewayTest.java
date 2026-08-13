package ru.questionhacker.trainer.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ru.questionhacker.trainer.AcpGateway;
import ru.questionhacker.trainer.AppProperties;

class AcpScenarioGenerationGatewayTest {

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
        when(acp.ask(anyString(), eq("test-model"), any())).thenReturn("""
                [{"category":"INVERSION","secondaryCategory":null,"difficulty":"L2","domain":"ПРОДУКТ","situation":"Команда обсуждает реалистичную рабочую ситуацию достаточной длины и ищет новое безопасное решение без упоминания брендов.","question":"Какие действия гарантированно приведут процесс к провалу?","hint":"Найдите причинные механизмы нежелательного исхода.","options":["INVERSION","HYPERBOLE","REFRAMING","SIMPLIFICATION"],"correctCategory":"INVERSION","explanation":"Вопрос переворачивает цель и исследует конкретные причины возможного провала.","confusedWith":null,"contrast":null}]
                """);
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("category at position 0");
    }
}
