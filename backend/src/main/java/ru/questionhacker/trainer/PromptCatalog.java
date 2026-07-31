package ru.questionhacker.trainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class PromptCatalog {

    private final String trainingCoach;
    private final String scenarioGenerator;
    private final String practiceScenario;
    private final String practiceReview;

    public PromptCatalog() {
        this.trainingCoach = read("prompts/training-coach.md");
        this.scenarioGenerator = read("prompts/scenario-generator.md");
        this.practiceScenario = read("prompts/practice-scenario.md");
        this.practiceReview = read("prompts/practice-review.md");
    }

    public String trainingCoach() {
        return trainingCoach;
    }

    public String scenarioGenerator() {
        return scenarioGenerator;
    }

    public String practiceScenario() {
        return practiceScenario;
    }

    public String practiceReview() {
        return practiceReview;
    }

    private static String read(String location) {
        try (var input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось загрузить prompt: " + location, e);
        }
    }
}
