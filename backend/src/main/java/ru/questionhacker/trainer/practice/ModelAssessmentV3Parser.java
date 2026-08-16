package ru.questionhacker.trainer.practice;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ModelAssessmentV3Parser {

    public static final String SCHEMA_VERSION = "practice-assessment-v3";
    private static final Set<String> STEP_FIELDS = Set.of("question", "rationale", "solution");
    private static final Set<String> STRENGTH_DIMENSIONS = Set.of(
            "specificity", "depth", "unexpectedness", "productivity");
    private static final Set<String> IDEA_DIMENSIONS = Set.of(
            "impact", "questionAlignment", "disruption", "feasibility");
    private static final Set<String> CATEGORIES = Set.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");

    private final ObjectMapper json;

    public ModelAssessmentV3Parser(ObjectMapper json) {
        this.json = json.copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public ModelAssessmentV3 parse(String raw, String targetCategory) {
        if (raw == null || !raw.strip().startsWith("{") || !raw.strip().endsWith("}")) {
            throw new IllegalArgumentException("Assessment must be one JSON object");
        }
        ModelAssessmentV3 result;
        try {
            result = json.readerFor(ModelAssessmentV3.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(raw.strip());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid assessment JSON", error);
        }
        ModelAssessmentV3 normalized = normalizeDerivedValues(result);
        validate(normalized, targetCategory);
        return normalized;
    }

    private ModelAssessmentV3 normalizeDerivedValues(ModelAssessmentV3 value) {
        if (value == null) return null;
        ModelAssessmentV2.QuestionStrength strength = value.questionStrength();
        if (strength != null && strength.dimensions() != null
                && strength.dimensions().stream().allMatch(dimension -> dimension != null)) {
            int score = (int) strength.dimensions().stream()
                    .filter(ModelAssessmentV2.StrengthDimension::met).count();
            strength = new ModelAssessmentV2.QuestionStrength(score, strength.dimensions());
        }
        return new ModelAssessmentV3(
                value.schemaVersion(), value.chain(), value.categoryFit(), strength,
                value.ideaPotential(), value.confidence(), value.strengths(),
                value.priorityCorrection(), value.feedback());
    }

    private void validate(ModelAssessmentV3 value, String targetCategory) {
        require(value != null, "assessment is required");
        require(SCHEMA_VERSION.equals(value.schemaVersion()), "unsupported schema version");
        require(value.chain() != null && value.chain().steps() != null
                && value.chain().steps().size() == 3, "three chain steps are required");
        Set<String> stepFields = new HashSet<>();
        for (var step : value.chain().steps()) {
            require(step != null && STEP_FIELDS.contains(step.field()), "invalid chain field");
            require(stepFields.add(step.field()), "duplicate chain field");
            Set<String> statuses = "rationale".equals(step.field())
                    ? Set.of("SUPPORTS", "WEAK", "CONTRADICTS") : Set.of("PASS", "FAIL");
            require(statuses.contains(step.status()), "invalid chain status");
            requireText(step.evidence(), "step evidence");
        }
        require(stepFields.equals(STEP_FIELDS), "all chain fields are required");

        require(value.categoryFit() != null, "category fit is required");
        require(value.categoryFit().score() >= 0 && value.categoryFit().score() <= 3,
                "category fit score is out of range");
        requireText(value.categoryFit().evidence(), "category fit evidence");
        if (value.categoryFit().confusedWith() != null) {
            require(CATEGORIES.contains(value.categoryFit().confusedWith()), "unknown confused category");
            require(!value.categoryFit().confusedWith().equals(targetCategory),
                    "confused category cannot equal target");
        }

        require(value.questionStrength() != null, "question strength is required");
        require(value.questionStrength().score() >= 0 && value.questionStrength().score() <= 4,
                "question strength score is out of range");
        require(value.questionStrength().dimensions() != null
                && value.questionStrength().dimensions().size() == 4,
                "four strength dimensions are required");
        Set<String> strengthDimensions = new HashSet<>();
        int met = 0;
        for (var dimension : value.questionStrength().dimensions()) {
            require(dimension != null && STRENGTH_DIMENSIONS.contains(dimension.name()),
                    "invalid strength dimension");
            require(strengthDimensions.add(dimension.name()), "duplicate strength dimension");
            requireText(dimension.evidence(), "strength evidence");
            if (dimension.met()) met++;
        }
        require(met == value.questionStrength().score(),
                "strength score does not match dimensions");

        require(value.ideaPotential() != null, "idea potential is required");
        validateIdeaDimensions(value.ideaPotential().dimensions());

        require(Set.of("HIGH", "MEDIUM", "LOW").contains(value.confidence()),
                "invalid confidence");
        require(value.strengths() != null && value.strengths().size() <= 4,
                "strengths must be a bounded list");
        value.strengths().forEach(item -> requireText(item, "strength"));
        require(value.priorityCorrection() != null, "priority correction is required");
        requireText(value.priorityCorrection().what(), "correction what");
        requireText(value.priorityCorrection().why(), "correction why");
        requireText(value.priorityCorrection().example(), "correction example");
        requireText(value.feedback(), "feedback");
    }

    static void validateIdeaDimensions(List<ModelAssessmentV3.IdeaDimension> dimensions) {
        require(dimensions != null && dimensions.size() == 4,
                "four idea potential dimensions are required");
        Set<String> ideaDimensions = new HashSet<>();
        for (var dimension : dimensions) {
            require(dimension != null && IDEA_DIMENSIONS.contains(dimension.name()),
                    "invalid idea potential dimension");
            require(ideaDimensions.add(dimension.name()), "duplicate idea potential dimension");
            requireText(dimension.evidence(), "idea potential evidence");
            if ("SCORED".equals(dimension.status())) {
                require(dimension.score() != null
                                && dimension.score() >= 0 && dimension.score() <= 4,
                        "idea potential score is out of range");
            } else {
                require("feasibility".equals(dimension.name())
                                && "INSUFFICIENT_CONTEXT".equals(dimension.status())
                                && dimension.score() == null,
                        "invalid idea potential status and score");
            }
        }
        require(ideaDimensions.equals(IDEA_DIMENSIONS),
                "all idea potential dimensions are required");
    }

    private static void requireText(String value, String field) {
        require(value != null && !value.isBlank() && value.length() <= 1200,
                field + " is required or too long");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
