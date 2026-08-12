package ru.questionhacker.trainer;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class AcpAvailability {

    private static final int MAX_REASON_LENGTH = 320;
    private final AppProperties properties;
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    public AcpAvailability(AppProperties properties) {
        this.properties = properties;
    }

    public boolean available() {
        return properties.acp().enabled() && lastFailure.get() == null;
    }

    public String reason() {
        if (!properties.acp().enabled()) {
            return "ACP отключён настройкой ACP_ENABLED=false.";
        }
        return lastFailure.get();
    }

    public void recordFailure(Throwable error) {
        String message = error == null ? null : error.getMessage();
        String normalized = message == null || message.isBlank()
                ? "неизвестная ошибка запуска"
                : message.replaceAll("\\s+", " ").strip();
        if (normalized.length() > MAX_REASON_LENGTH) {
            normalized = normalized.substring(0, MAX_REASON_LENGTH) + "…";
        }
        lastFailure.set("Не удалось запустить ACP-сессию: " + normalized);
    }

    public void recordSuccess() {
        lastFailure.set(null);
    }
}
