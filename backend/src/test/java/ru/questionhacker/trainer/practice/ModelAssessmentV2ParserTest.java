package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelAssessmentV2ParserTest {

    private final ModelAssessmentV2Parser parser = new ModelAssessmentV2Parser(new ObjectMapper());

    @Test
    void acceptsThreeFieldContractAndDerivesStrengthScore() {
        var result = parser.parse(validJson(), "INVERSION");

        assertThat(result.schemaVersion()).isEqualTo("practice-assessment-v2");
        assertThat(result.chain().steps()).extracting(ModelAssessmentV2.StepResult::field)
                .containsExactlyInAnyOrder("question", "rationale", "solution");
        assertThat(result.questionStrength().score()).isEqualTo(3);
        assertThat(result.chain().steps()).filteredOn(step -> step.field().equals("rationale"))
                .extracting(ModelAssessmentV2.StepResult::status).containsExactly("WEAK");
    }

    @Test
    void rejectsModelControlledRevisionFieldsAndInvalidRationaleStatus() {
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"feedback\":", "\"fieldsToRevise\":[\"question\"],\"feedback\":"), "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(
                validJson().replace("\"status\":\"WEAK\"", "\"status\":\"FAIL\""),
                "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMarkdownAndWrongSchemaVersion() {
        assertThatThrownBy(() -> parser.parse("```json\n" + validJson() + "\n```", "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(
                validJson().replace("practice-assessment-v2", "practice-assessment-v1"),
                "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String validJson() {
        return """
                {
                  "schemaVersion":"practice-assessment-v2",
                  "chain":{"steps":[
                    {"field":"question","status":"PASS","evidence":"Вопрос задаёт конкретный провал"},
                    {"field":"rationale","status":"WEAK","evidence":"Связь обозначена, но механизм раскрыт кратко"},
                    {"field":"solution","status":"PASS","evidence":"Решение обращает причины в профилактику"}
                  ]},
                  "categoryFit":{"score":2,"evidence":"Вопрос выполняет инверсию","confusedWith":null},
                  "questionStrength":{"score":4,"dimensions":[
                    {"name":"specificity","met":true,"evidence":"Назван конкретный запуск"},
                    {"name":"depth","met":true,"evidence":"Ищется причинный механизм"},
                    {"name":"unexpectedness","met":true,"evidence":"Цель перевёрнута"},
                    {"name":"productivity","met":false,"evidence":"Критерий проверки не указан"}
                  ]},
                  "confidence":"MEDIUM",
                  "strengths":["Ясная инверсия"],
                  "priorityCorrection":{"what":"Уточнить проверку","why":"Так вывод станет проверяемым","example":"Назовите три наблюдаемых действия"},
                  "feedback":"Вопрос и решение связаны; обоснование можно сделать яснее."
                }
                """;
    }
}
