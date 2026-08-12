package ru.questionhacker.trainer.practice;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(value = "verdict")
public record ModelAssessment(
        String schemaVersion,
        Completeness completeness,
        CategoryFit categoryFit,
        QuestionStrength questionStrength,
        String confidence,
        List<String> strengths,
        PriorityCorrection priorityCorrection,
        List<String> fieldsToRevise,
        String feedback) {

    public record Completeness(String status, List<StepResult> steps) {
    }

    public record StepResult(String field, String status, String evidence) {
    }

    public record CategoryFit(int score, String evidence, String confusedWith) {
    }

    public record QuestionStrength(int score, List<StrengthDimension> dimensions) {
    }

    public record StrengthDimension(String name, boolean met, String evidence) {
    }

    public record PriorityCorrection(String what, String why, String example) {
    }
}
