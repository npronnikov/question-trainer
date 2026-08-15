package ru.questionhacker.trainer.practice;

public interface PracticeAssessmentGateway {

    Result assess(Input input, String requestedModel);

    record Input(
            String situation,
            String category,
            String guidance,
            String question,
            String rationale,
            String solution) {
    }

    record Result(String json, String modelId) {
    }
}
