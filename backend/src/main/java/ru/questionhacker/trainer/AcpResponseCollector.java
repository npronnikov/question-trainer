package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import static com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;

import java.util.function.Consumer;

final class AcpResponseCollector implements Consumer<SessionNotification> {

    private final Consumer<String> onChunk;
    private final StringBuilder chunks = new StringBuilder();

    AcpResponseCollector(Consumer<String> onChunk) {
        this.onChunk = onChunk;
    }

    @Override
    public void accept(SessionNotification notification) {
        if (notification.update() instanceof AgentMessageChunk message) {
            AcpMessageFilter.visibleText(message).ifPresent(text -> {
                chunks.append(text);
                onChunk.accept(text);
            });
        }
    }

    boolean isEmpty() {
        return chunks.isEmpty();
    }

    String text() {
        return chunks.toString();
    }
}
