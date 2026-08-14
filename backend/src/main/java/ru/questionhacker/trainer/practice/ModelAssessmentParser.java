package ru.questionhacker.trainer.practice;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ModelAssessmentParser {

    public static final String SCHEMA_VERSION = "practice-assessment-v1";
    private static final Set<String> STEP_FIELDS = Set.of(
            "question", "answer", "reasoning", "solution");
    private static final Set<String> DIMENSIONS = Set.of(
            "specificity", "depth", "unexpectedness", "productivity");
    private static final Set<String> CATEGORIES = Set.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");

    private final ObjectMapper json;

    public ModelAssessmentParser(ObjectMapper json) {
        this.json = json;
    }

    public ModelAssessment parse(String raw, String targetCategory) {
        if (raw == null || !raw.strip().startsWith("{") || !raw.strip().endsWith("}")) {
            throw new IllegalArgumentException("Assessment must be one JSON object");
        }
        ModelAssessment result;
        try {
            result = json.readValue(raw.strip(), ModelAssessment.class);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid assessment JSON", error);
        }
        ModelAssessment normalized = normalizeDerivedValues(result);
        validate(normalized, targetCategory);
        return normalized;
    }

    private ModelAssessment normalizeDerivedValues(ModelAssessment value) {
        if (value == null) return null;

        ModelAssessment.Completeness completeness = value.completeness();
        if (completeness != null && completeness.steps() != null
                && completeness.steps().stream().allMatch(step -> step != null
                && Set.of("PASS", "FAIL").contains(step.status()))) {
            String status = completeness.steps().stream()
                    .allMatch(step -> "PASS".equals(step.status())) ? "PASS" : "FAIL";
            completeness = new ModelAssessment.Completeness(status, completeness.steps());
        }

        ModelAssessment.QuestionStrength strength = value.questionStrength();
        if (strength != null && strength.dimensions() != null
                && strength.dimensions().stream().allMatch(dimension -> dimension != null)) {
            int score = (int) strength.dimensions().stream()
                    .filter(ModelAssessment.StrengthDimension::met).count();
            strength = new ModelAssessment.QuestionStrength(score, strength.dimensions());
        }

        return new ModelAssessment(
                value.schemaVersion(), completeness, value.categoryFit(), strength,
                value.confidence(), value.strengths(), value.priorityCorrection(),
                value.fieldsToRevise(), value.feedback());
    }

    private void validate(ModelAssessment value, String targetCategory) {
        require(value != null, "assessment is required");
        require(SCHEMA_VERSION.equals(value.schemaVersion()), "unsupported schema version");
        require(value.completeness() != null, "completeness is required");
        require(Set.of("PASS", "FAIL").contains(value.completeness().status()),
                "invalid completeness status");
        require(value.completeness().steps() != null && value.completeness().steps().size() == 4,
                "four completeness steps are required");
        Set<String> stepFields = new HashSet<>();
        boolean allStepsPass = true;
        for (var step : value.completeness().steps()) {
            require(step != null && STEP_FIELDS.contains(step.field()), "invalid completeness field");
            require(stepFields.add(step.field()), "duplicate completeness field");
            require(Set.of("PASS", "FAIL").contains(step.status()), "invalid step status");
            requireText(step.evidence(), "step evidence");
            allStepsPass &= "PASS".equals(step.status());
        }
        require(allStepsPass == "PASS".equals(value.completeness().status()),
                "completeness status does not match steps");

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
        require(Set.of("HIGH", "MEDIUM", "LOW").contains(value.confidence()),
                "invalid confidence");

        require(value.strengths() != null && value.strengths().size() <= 4,
                "strengths must be a bounded list");
        value.strengths().forEach(item -> requireText(item, "strength"));
        require(value.priorityCorrection() != null, "priority correction is required");
        requireText(value.priorityCorrection().what(), "correction what");
        requireText(value.priorityCorrection().why(), "correction why");
        requireText(value.priorityCorrection().example(), "correction example");
        require(value.fieldsToRevise() != null && value.fieldsToRevise().size() <= 4,
                "revision fields must be a bounded list");
        require(new HashSet<>(value.fieldsToRevise()).size() == value.fieldsToRevise().size(),
                "duplicate revision field");
        require(STEP_FIELDS.containsAll(value.fieldsToRevise()), "unknown revision field");
        boolean thresholdsPass = "PASS".equals(value.completeness().status())
                && value.categoryFit().score() >= 2
                && value.questionStrength().score() >= 3
                && !"LOW".equals(value.confidence());
        require(thresholdsPass || !value.fieldsToRevise().isEmpty(),
                "failed assessment must identify revision fields");
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
