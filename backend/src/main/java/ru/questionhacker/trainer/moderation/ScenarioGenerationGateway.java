package ru.questionhacker.trainer.moderation;

import java.util.List;

public interface ScenarioGenerationGateway {
    List<ScenarioDraft> generate(List<String> categories, String requestedModel);

    PracticeScenarioDraft generatePractice(String category, String requestedModel);
}
