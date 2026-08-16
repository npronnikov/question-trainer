package ru.questionhacker.trainer.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PracticeAssessmentDecisionTest {

    @Test
    void weakRationaleDoesNotBlockAnOtherwiseStrongAttempt() {
        var decision = PracticeAssessmentService.decide(assessment(
                "PASS", "WEAK", "PASS", 2, 3, "MEDIUM"));

        assertThat(decision.status()).isEqualTo("PASSED");
        assertThat(decision.fieldsToRevise()).isEmpty();
    }

    @Test
    void backendDerivesRevisionFieldsFromFailedCriteria() {
        var decision = PracticeAssessmentService.decide(assessment(
                "PASS", "CONTRADICTS", "FAIL", 1, 2, "HIGH"));

        assertThat(decision.status()).isEqualTo("NEEDS_REVISION");
        assertThat(decision.fieldsToRevise()).containsExactly("question", "rationale", "solution");
    }

    @Test
    void lowConfidenceUsesRetryInsteadOfInventingARevision() {
        var decision = PracticeAssessmentService.decide(assessment(
                "PASS", "SUPPORTS", "PASS", 3, 4, "LOW"));

        assertThat(decision.status()).isEqualTo("UNVERIFIED");
        assertThat(decision.fieldsToRevise()).isEmpty();
    }

    @Test
    void ideaPotentialNeverChangesTheBackendVerdict() {
        var weakIdea = assessment("PASS", "SUPPORTS", "PASS", 2, 3, "HIGH", 0);
        var strongIdea = assessment("PASS", "SUPPORTS", "PASS", 2, 3, "HIGH", 4);

        assertThat(PracticeAssessmentService.decide(weakIdea))
                .isEqualTo(PracticeAssessmentService.decide(strongIdea));
    }

    private ModelAssessmentV3 assessment(String questionStatus, String rationaleStatus,
                                         String solutionStatus, int fit, int strength,
                                         String confidence) {
        return assessment(questionStatus, rationaleStatus, solutionStatus,
                fit, strength, confidence, 2);
    }

    private ModelAssessmentV3 assessment(String questionStatus, String rationaleStatus,
                                         String solutionStatus, int fit, int strength,
                                         String confidence, int ideaScore) {
        return new ModelAssessmentV3(
                ModelAssessmentV3Parser.SCHEMA_VERSION,
                new ModelAssessmentV2.Chain(List.of(
                        new ModelAssessmentV2.StepResult("question", questionStatus, "question evidence"),
                        new ModelAssessmentV2.StepResult("rationale", rationaleStatus, "rationale evidence"),
                        new ModelAssessmentV2.StepResult("solution", solutionStatus, "solution evidence"))),
                new ModelAssessmentV2.CategoryFit(fit, "fit evidence", null),
                new ModelAssessmentV2.QuestionStrength(strength, List.of(
                        dimension("specificity", strength >= 1),
                        dimension("depth", strength >= 2),
                        dimension("unexpectedness", strength >= 3),
                        dimension("productivity", strength >= 4))),
                new ModelAssessmentV3.IdeaPotential(List.of(
                        ideaDimension("impact", ideaScore),
                        ideaDimension("questionAlignment", ideaScore),
                        ideaDimension("disruption", ideaScore),
                        ideaDimension("feasibility", ideaScore))),
                confidence, List.of(),
                new ModelAssessmentV2.PriorityCorrection("what", "why", "example"),
                "feedback");
    }

    private ModelAssessmentV2.StrengthDimension dimension(String name, boolean met) {
        return new ModelAssessmentV2.StrengthDimension(name, met, "evidence");
    }

    private ModelAssessmentV3.IdeaDimension ideaDimension(String name, int score) {
        return new ModelAssessmentV3.IdeaDimension(name, "SCORED", score, "evidence");
    }
}
