package ru.questionhacker.trainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class PromptCatalog {

    private final String trainingCoach;
    private final String scenarioGenerator;

    public PromptCatalog() {
        this.trainingCoach = read("prompts/training-coach.md");
        this.scenarioGenerator = read("prompts/scenario-generator.md");
    }

    public String trainingCoach() {
        return trainingCoach;
    }

    public String scenarioGenerator() {
        return scenarioGenerator;
    }

    private static String read(String location) {
        try (var input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось загрузить prompt: " + location, e);
        }
    }
}
