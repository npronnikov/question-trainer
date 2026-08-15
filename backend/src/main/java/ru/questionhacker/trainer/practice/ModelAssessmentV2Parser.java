package ru.questionhacker.trainer.practice;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ModelAssessmentV2Parser {

    public static final String SCHEMA_VERSION = "practice-assessment-v2";
    private static final Set<String> STEP_FIELDS = Set.of("question", "rationale", "solution");
    private static final Set<String> DIMENSIONS = Set.of(
            "specificity", "depth", "unexpectedness", "productivity");
    private static final Set<String> CATEGORIES = Set.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");

    private final ObjectMapper json;

    public ModelAssessmentV2Parser(ObjectMapper json) {
        this.json = json;
    }

    public ModelAssessmentV2 parse(String raw, String targetCategory) {
        if (raw == null || !raw.strip().startsWith("{") || !raw.strip().endsWith("}")) {
            throw new IllegalArgumentException("Assessment must be one JSON object");
        }
        ModelAssessmentV2 result;
        try {
            result = json.readerFor(ModelAssessmentV2.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(raw.strip());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid assessment JSON", error);
        }
        ModelAssessmentV2 normalized = normalizeDerivedValues(result);
        validate(normalized, targetCategory);
        return normalized;
    }

    private ModelAssessmentV2 normalizeDerivedValues(ModelAssessmentV2 value) {
        if (value == null) return null;
        ModelAssessmentV2.QuestionStrength strength = value.questionStrength();
        if (strength != null && strength.dimensions() != null
                && strength.dimensions().stream().allMatch(dimension -> dimension != null)) {
            int score = (int) strength.dimensions().stream()
                    .filter(ModelAssessmentV2.StrengthDimension::met).count();
            strength = new ModelAssessmentV2.QuestionStrength(score, strength.dimensions());
        }
        return new ModelAssessmentV2(
                value.schemaVersion(), value.chain(), value.categoryFit(), strength,
                value.confidence(), value.strengths(), value.priorityCorrection(), value.feedback());
    }

    private void validate(ModelAssessmentV2 value, String targetCategory) {
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
        Set<String> dimensions = new HashSet<>();
        int met = 0;
        for (var dimension : value.questionStrength().dimensions()) {
            require(dimension != null && DIMENSIONS.contains(dimension.name()),
                    "invalid strength dimension");
            require(dimensions.add(dimension.name()), "duplicate strength dimension");
            requireText(dimension.evidence(), "strength evidence");
            if (dimension.met()) met++;
        }
        require(met == value.questionStrength().score(), "strength score does not match dimensions");
        require(Set.of("HIGH", "MEDIUM", "LOW").contains(value.confidence()), "invalid confidence");

        require(value.strengths() != null && value.strengths().size() <= 4,
                "strengths must be a bounded list");
        value.strengths().forEach(item -> requireText(item, "strength"));
        require(value.priorityCorrection() != null, "priority correction is required");
        requireText(value.priorityCorrection().what(), "correction what");
        requireText(value.priorityCorrection().why(), "correction why");
        requireText(value.priorityCorrection().example(), "correction example");
        requireText(value.feedback(), "feedback");
    }

    private void requireText(String value, String field) {
        require(value != null && !value.isBlank() && value.length() <= 1200,
                field + " is required or too long");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
