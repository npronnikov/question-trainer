package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelAssessmentParserTest {

    private final ModelAssessmentParser parser = new ModelAssessmentParser(new ObjectMapper());

    @Test
    void acceptsCompleteVersionedContractAndIgnoresModelVerdict() {
        var result = parser.parse(validJson().replace(
                "\"feedback\":", "\"verdict\":\"PASSED\",\"feedback\":"), "INVERSION");

        assertThat(result.schemaVersion()).isEqualTo("practice-assessment-v1");
        assertThat(result.completeness().steps()).hasSize(4);
        assertThat(result.categoryFit().score()).isEqualTo(2);
        assertThat(result.questionStrength().score()).isEqualTo(3);
        assertThat(result.confidence()).isEqualTo("MEDIUM");
        assertThat(result).hasNoNullFieldsOrPropertiesExcept("categoryFit.confusedWith");
    }

    @Test
    void derivesStrengthScoreFromIndividualDimensions() {
        var result = parser.parse(
                validJson().replace("\"score\":3,\"dimensions\"", "\"score\":4,\"dimensions\""),
                "INVERSION");

        assertThat(result.questionStrength().score()).isEqualTo(3);
    }

    @Test
    void derivesCompletenessStatusFromIndividualSteps() {
        var result = parser.parse(
                validJson().replaceFirst("\"status\":\"PASS\"", "\"status\":\"FAIL\""),
                "INVERSION");

        assertThat(result.completeness().status()).isEqualTo("PASS");
    }

    @Test
    void rejectsMissingEvidenceAndUnknownRevisionFields() {
        assertThatThrownBy(() -> parser.parse(
                validJson().replace("Вопрос задаёт конкретный провал", "")
                        .replace("\"reasoning\"]", "\"reasoning\",\"verdict\"]"),
                "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMarkdownWrapperAndWrongSchema() {
        assertThatThrownBy(() -> parser.parse("```json\n" + validJson() + "\n```", "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(
                validJson().replace("practice-assessment-v1", "practice-assessment-v2"),
                "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String validJson() {
        return """
                {
                  "schemaVersion":"practice-assessment-v1",
                  "completeness":{"status":"PASS","steps":[
                    {"field":"question","status":"PASS","evidence":"Вопрос задаёт конкретный провал"},
                    {"field":"answer","status":"PASS","evidence":"Ответ раскрывает причины провала"},
                    {"field":"reasoning","status":"PASS","evidence":"Рассуждение связывает причины и вывод"},
                    {"field":"solution","status":"PASS","evidence":"Решение обращает причины в профилактику"}
                  ]},
                  "categoryFit":{"score":2,"evidence":"Вопрос выполняет инверсию через намеренный провал","confusedWith":null},
                  "questionStrength":{"score":3,"dimensions":[
                    {"name":"specificity","met":true,"evidence":"Назван конкретный запуск"},
                    {"name":"depth","met":true,"evidence":"Ищется причинный механизм"},
                    {"name":"unexpectedness","met":true,"evidence":"Цель намеренно перевёрнута"},
                    {"name":"productivity","met":false,"evidence":"Не задан способ проверки"}
                  ]},
                  "confidence":"MEDIUM",
                  "strengths":["Вопрос ясно переворачивает желаемый исход"],
                  "priorityCorrection":{"what":"Добавить проверяемый критерий","why":"Иначе вывод нельзя проверить","example":"Какие три наблюдаемых действия гарантируют провал запуска?"},
                  "fieldsToRevise":["question","reasoning"],
                  "feedback":"Цепочка связна; усилите проверяемость вопроса."
                }
                """;
    }
}
