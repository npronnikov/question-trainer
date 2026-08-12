package ru.questionhacker.trainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class PromptCatalog {

    private final String trainingCoach;

    public PromptCatalog() {
        this.trainingCoach = read("prompts/training-coach.md");
    }

    public String trainingCoach() {
        return trainingCoach;
    }

    private static String read(String location) {
        try (var input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось загрузить prompt: " + location, e);
        }
    }
}
