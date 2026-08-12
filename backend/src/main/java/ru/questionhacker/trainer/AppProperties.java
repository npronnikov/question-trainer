package ru.questionhacker.trainer;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Acp acp, Chat chat, Admin admin) {

    public record Acp(
            boolean enabled,
            boolean fallbackEnabled,
            String command,
            List<String> args,
            String workspace,
            Duration timeout,
            long maxFileBytes,
            List<String> forwardEnv,
            List<String> models,
            String defaultModel) {
    }

    public record Chat(int maxMessageChars, int historyLimit) {
    }

    public record Admin(String username, String password, String email) {
    }
}
