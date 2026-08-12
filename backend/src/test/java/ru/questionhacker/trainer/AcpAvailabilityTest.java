package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AcpAvailabilityTest {

    @Test
    void disabledConfigurationExplainsFallbackWithoutAProbe() {
        var status = new AcpAvailability(properties(false));

        assertThat(status.available()).isFalse();
        assertThat(status.reason()).isEqualTo("ACP отключён настройкой ACP_ENABLED=false.");
    }

    @Test
    void runtimeFailureIsSanitizedAndSuccessRestoresAvailability() {
        var status = new AcpAvailability(properties(true));

        status.recordFailure(new IllegalStateException("session failed\n  at internal.Client"));

        assertThat(status.available()).isFalse();
        assertThat(status.reason()).isEqualTo("Не удалось запустить ACP-сессию: session failed at internal.Client");

        status.recordSuccess();

        assertThat(status.available()).isTrue();
        assertThat(status.reason()).isNull();
    }

    private AppProperties properties(boolean enabled) {
        return new AppProperties(
                new AppProperties.Acp(enabled, true, "npx", List.of("codex-acp"), ".",
                        Duration.ofSeconds(1), 1024, List.of(), List.of("model"), "model"),
                new AppProperties.Chat(12000, 24),
                new AppProperties.Admin("admin", "password", "admin@example.test"));
    }
}
