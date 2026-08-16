package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PracticePromptCatalogTest {

    @Test
    void rendersV3ContractWithFourAxesAndServerOwnedDerivedValues() {
        var catalog = new PracticePromptCatalog(mock(JdbcTemplate.class));
        var input = new PracticeAssessmentGateway.Input(
                "Ситуация-маркер", "INVERSION", "Ориентир-маркер",
                "Вопрос-маркер", "Обоснование-маркер", "Решение-маркер");

        String prompt = catalog.render(input);

        assertThat(prompt)
                .contains("Ситуация-маркер", "INVERSION", "Ориентир-маркер",
                        "Вопрос-маркер", "Обоснование-маркер", "Решение-маркер")
                .contains("\"name\":\"impact\"", "\"name\":\"questionAlignment\"",
                        "\"name\":\"disruption\"", "\"name\":\"feasibility\"")
                .contains("INSUFFICIENT_CONTEXT", "score=null")
                .contains("Не вычисляй и не возвращай overallScore")
                .contains("не возвращай verdict")
                .doesNotContain("{{");
    }
}
