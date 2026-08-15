package ru.questionhacker.trainer.practice;

import java.util.List;

public record ModelAssessmentV2(
        String schemaVersion,
        Chain chain,
        CategoryFit categoryFit,
        QuestionStrength questionStrength,
        String confidence,
        List<String> strengths,
        PriorityCorrection priorityCorrection,
        String feedback) {

    public record Chain(List<StepResult> steps) {
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
