package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelAssessmentV3ParserTest {

    private final ModelAssessmentV3Parser parser = new ModelAssessmentV3Parser(new ObjectMapper());

    @Test
    void acceptsExactlyFourScoredIdeaDimensions() {
        var result = parser.parse(validJson(), "INVERSION");

        assertThat(result.schemaVersion()).isEqualTo("practice-assessment-v3");
        assertThat(result.questionStrength().score()).isEqualTo(3);
        assertThat(result.ideaPotential().dimensions())
                .extracting(ModelAssessmentV3.IdeaDimension::name)
                .containsExactlyInAnyOrder(
                        "impact", "questionAlignment", "disruption", "feasibility");
        assertThat(result.ideaPotential().dimensions())
                .allSatisfy(dimension -> {
                    assertThat(dimension.status()).isEqualTo("SCORED");
                    assertThat(dimension.score()).isBetween(0, 4);
                    assertThat(dimension.evidence()).isNotBlank();
                });
    }

    @Test
    void allowsOnlyFeasibilityToHaveInsufficientContextAndNullScore() {
        String incomplete = validJson().replace(
                "{\"name\":\"feasibility\",\"status\":\"SCORED\",\"score\":3,\"evidence\":\"Указан недельный тест\"}",
                "{\"name\":\"feasibility\",\"status\":\"INSUFFICIENT_CONTEXT\",\"score\":null,\"evidence\":\"Ресурсы и ограничения не заданы\"}");

        var result = parser.parse(incomplete, "INVERSION");

        assertThat(result.ideaPotential().dimensions())
                .filteredOn(dimension -> dimension.name().equals("feasibility"))
                .singleElement().satisfies(dimension -> {
                    assertThat(dimension.status()).isEqualTo("INSUFFICIENT_CONTEXT");
                    assertThat(dimension.score()).isNull();
                });
    }

    @Test
    void rejectsMissingDuplicateUnknownAndOutOfRangeDimensions() {
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "{\"name\":\"feasibility\",\"status\":\"SCORED\",\"score\":3,\"evidence\":\"Указан недельный тест\"}", ""),
                "INVERSION")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"name\":\"feasibility\"", "\"name\":\"impact\""), "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"name\":\"feasibility\"", "\"name\":\"novelty\""), "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"name\":\"impact\",\"status\":\"SCORED\",\"score\":4",
                "\"name\":\"impact\",\"status\":\"SCORED\",\"score\":5"), "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidStatusScoreUnionsAndUnknownProperties() {
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"name\":\"impact\",\"status\":\"SCORED\",\"score\":4",
                "\"name\":\"impact\",\"status\":\"INSUFFICIENT_CONTEXT\",\"score\":null"),
                "INVERSION")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"name\":\"feasibility\",\"status\":\"SCORED\",\"score\":3",
                "\"name\":\"feasibility\",\"status\":\"INSUFFICIENT_CONTEXT\",\"score\":3"),
                "INVERSION")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"name\":\"impact\",\"status\":\"SCORED\",\"score\":4",
                "\"name\":\"impact\",\"status\":\"SCORED\",\"score\":null"),
                "INVERSION")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"feedback\":", "\"overallScore\":4,\"feedback\":"), "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFractionalScoresTrailingJsonAndDuplicateKeys() {
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"name\":\"impact\",\"status\":\"SCORED\",\"score\":4",
                "\"name\":\"impact\",\"status\":\"SCORED\",\"score\":3.7"),
                "INVERSION")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(validJson() + " {}", "INVERSION"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(validJson().replace(
                "\"schemaVersion\":\"practice-assessment-v3\"",
                "\"schemaVersion\":\"practice-assessment-v3\","
                        + "\"schemaVersion\":\"practice-assessment-v3\""),
                "INVERSION")).isInstanceOf(IllegalArgumentException.class);
    }

    static String validJson() {
        return """
                {
                  "schemaVersion":"practice-assessment-v3",
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
                  "ideaPotential":{"dimensions":[
                    {"name":"impact","status":"SCORED","score":4,"evidence":"Меняется сам способ запуска"},
                    {"name":"questionAlignment","status":"SCORED","score":3,"evidence":"Решение отвечает на причины провала"},
                    {"name":"disruption","status":"SCORED","score":4,"evidence":"Обычная цель намеренно перевёрнута"},
                    {"name":"feasibility","status":"SCORED","score":3,"evidence":"Указан недельный тест"}
                  ]},
                  "confidence":"MEDIUM",
                  "strengths":["Ясная инверсия"],
                  "priorityCorrection":{"what":"Уточнить проверку","why":"Так вывод станет проверяемым","example":"Назовите три наблюдаемых действия"},
                  "feedback":"Вопрос и решение связаны; обоснование можно сделать яснее."
                }
                """;
    }
}
