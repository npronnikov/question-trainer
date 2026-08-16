package ru.questionhacker.trainer.practice;

import java.util.List;

public record ModelAssessmentV3(
        String schemaVersion,
        ModelAssessmentV2.Chain chain,
        ModelAssessmentV2.CategoryFit categoryFit,
        ModelAssessmentV2.QuestionStrength questionStrength,
        IdeaPotential ideaPotential,
        String confidence,
        List<String> strengths,
        ModelAssessmentV2.PriorityCorrection priorityCorrection,
        String feedback) {

    public record IdeaPotential(List<IdeaDimension> dimensions) {
    }

    public record IdeaDimension(
            String name,
            String status,
            Integer score,
            String evidence) {
    }
}
