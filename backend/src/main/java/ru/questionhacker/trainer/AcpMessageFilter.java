package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import static com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;

import java.util.Map;
import java.util.Optional;

final class AcpMessageFilter {

    private AcpMessageFilter() {
    }

    static Optional<String> visibleText(AgentMessageChunk message) {
        if (!(message.content() instanceof TextContent text) || text.text() == null) {
            return Optional.empty();
        }
        return "commentary".equals(codexPhase(message.meta()))
                ? Optional.empty()
                : Optional.of(text.text());
    }

    private static String codexPhase(Map<String, Object> meta) {
        if (meta == null || !(meta.get("codex") instanceof Map<?, ?> codex)) {
            return null;
        }
        Object phase = codex.get("phase");
        return phase instanceof String value ? value : null;
    }
}
