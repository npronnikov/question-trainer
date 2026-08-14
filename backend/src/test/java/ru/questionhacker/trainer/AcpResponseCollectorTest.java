package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import static com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;
import static com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AcpResponseCollectorTest {

    @Test
    void streamsAndCollectsOnlyTheFinalAnswer() {
        var streamed = new ArrayList<String>();
        var subject = new AcpResponseCollector(streamed::add);

        subject.accept(notification("служебный текст", "commentary"));
        subject.accept(notification("ответ ", "final_answer"));
        subject.accept(notification("коуча", "final_answer"));

        assertThat(streamed).containsExactly("ответ ", "ответ коуча");
        assertThat(subject.text()).isEqualTo("ответ коуча");
    }

    @Test
    void commentaryOnlyProducesAnEmptyResponse() {
        var streamed = new ArrayList<String>();
        var subject = new AcpResponseCollector(streamed::add);

        subject.accept(notification("служебный текст", "commentary"));

        assertThat(streamed).isEmpty();
        assertThat(subject.isEmpty()).isTrue();
        assertThat(subject.text()).isEmpty();
    }

    private SessionNotification notification(String text, String phase) {
        var message = new AgentMessageChunk(
                "agent_message_chunk",
                new TextContent(text),
                "message-id",
                Map.of("codex", Map.of("phase", phase)));
        return new SessionNotification("session-id", message);
    }
}
