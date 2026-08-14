package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import static com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AcpMessageFilterTest {

    @Test
    void hidesCodexCommentary() {
        var message = message("служебный текст", Map.of(
                "codex", Map.of("phase", "commentary")));

        assertThat(AcpMessageFilter.visibleText(message)).isEmpty();
    }

    @Test
    void keepsCodexFinalAnswer() {
        var message = message("ответ коуча", Map.of(
                "codex", Map.of("phase", "final_answer")));

        assertThat(AcpMessageFilter.visibleText(message)).contains("ответ коуча");
    }

    @Test
    void keepsUnphasedAgentMessagesForCompatibility() {
        assertThat(AcpMessageFilter.visibleText(message("обычный ответ", null)))
                .contains("обычный ответ");
    }

    @Test
    void keepsUnknownPhasesForCompatibility() {
        assertThat(AcpMessageFilter.visibleText(message("новая фаза", Map.of(
                "codex", Map.of("phase", "future_phase")))))
                .contains("новая фаза");
    }

    @Test
    void ignoresMissingTextAndAcceptsMalformedCodexMetadata() {
        var missingText = new AgentMessageChunk(
                "agent_message_chunk", null, "message-id", Map.of());

        assertThat(AcpMessageFilter.visibleText(missingText)).isEmpty();
        assertThat(AcpMessageFilter.visibleText(message(
                "совместимый ответ", Map.of("codex", "unexpected"))))
                .contains("совместимый ответ");
    }

    private AgentMessageChunk message(String text, Map<String, Object> meta) {
        return new AgentMessageChunk(
                "agent_message_chunk", new TextContent(text), "message-id", meta);
    }
}
