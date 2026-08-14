package ru.questionhacker.trainer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AcpInteractionLogger {

    static final String LOGGER_NAME = "ru.questionhacker.trainer.acp.interaction";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    public Interaction begin(String prompt, String model) {
        var interaction = new Interaction(UUID.randomUUID(), System.nanoTime());
        log.info("ACP request interactionId={} model={} prompt=\n{}",
                interaction.id(), model, prompt);
        return interaction;
    }

    public void success(Interaction interaction, String response) {
        log.info("ACP response interactionId={} durationMs={} response=\n{}",
                interaction.id(), interaction.durationMs(), response);
    }

    public void failure(Interaction interaction, RuntimeException error) {
        log.error("ACP error interactionId={} durationMs={}",
                interaction.id(), interaction.durationMs(), error);
    }

    public record Interaction(UUID id, long startedAtNanos) {
        long durationMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        }
    }
}
