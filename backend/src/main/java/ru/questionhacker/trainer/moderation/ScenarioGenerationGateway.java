package ru.questionhacker.trainer.moderation;

import java.util.List;

public interface ScenarioGenerationGateway {
    List<ScenarioDraft> generate(int count, String requestedModel);
}
