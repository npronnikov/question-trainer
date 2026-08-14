package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import static com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;

import java.util.function.Consumer;

final class AcpResponseCollector implements Consumer<SessionNotification> {

    private final Consumer<String> onSnapshot;
    private final StringBuilder chunks = new StringBuilder();

    AcpResponseCollector(Consumer<String> onSnapshot) {
        this.onSnapshot = onSnapshot;
    }

    @Override
    public synchronized void accept(SessionNotification notification) {
        if (notification.update() instanceof AgentMessageChunk message) {
            AcpMessageFilter.visibleText(message).ifPresent(text -> {
                chunks.append(text);
                onSnapshot.accept(chunks.toString());
            });
        }
    }

    synchronized boolean isEmpty() {
        return chunks.isEmpty();
    }

    synchronized String text() {
        return chunks.toString();
    }
}
