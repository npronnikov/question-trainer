package ru.questionhacker.trainer;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class RunStreamRegistry {

    private static final long SSE_TIMEOUT = Duration.ofMinutes(8).toMillis();
    private final Map<UUID, StreamState> streams = new ConcurrentHashMap<>();

    public UUID create() {
        var id = UUID.randomUUID();
        streams.put(id, new StreamState());
        return id;
    }

    public SseEmitter subscribe(UUID runId) {
        var state = streams.get(runId);
        if (state == null) {
            throw new IllegalArgumentException("Неизвестный runId");
        }
        var emitter = new SseEmitter(SSE_TIMEOUT);
        state.emitters.add(emitter);
        emitter.onCompletion(() -> state.emitters.remove(emitter));
        emitter.onTimeout(() -> state.emitters.remove(emitter));
        emitter.onError(error -> state.emitters.remove(emitter));

        synchronized (state) {
            state.backlog.forEach(event -> send(emitter, event));
            if (state.completed) {
                send(emitter, state.terminalEvent);
                emitter.complete();
            }
        }
        return emitter;
    }

    public void delta(UUID runId, String text) {
        publish(runId, new StreamEvent("delta", Map.of("text", text)), false);
    }

    public void done(UUID runId, String source, UUID messageId) {
        publish(runId, new StreamEvent("done", Map.of(
                "source", source,
                "messageId", messageId.toString())), true);
    }

    public void error(UUID runId, String message) {
        publish(runId, new StreamEvent("failure", Map.of("message", message)), true);
    }

    private void publish(UUID runId, StreamEvent event, boolean terminal) {
        var state = streams.get(runId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.completed) {
                return;
            }
            if (terminal) {
                state.completed = true;
                state.terminalEvent = event;
            } else {
                state.backlog.add(event);
            }
            state.emitters.forEach(emitter -> send(emitter, event));
            if (terminal) {
                state.emitters.forEach(SseEmitter::complete);
                state.emitters.clear();
            }
        }
    }

    private void send(SseEmitter emitter, StreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.name())
                    .data(event.data(), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private static final class StreamState {
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private final List<StreamEvent> backlog = new ArrayList<>();
        private boolean completed;
        private StreamEvent terminalEvent;
    }

    private record StreamEvent(String name, Object data) {
    }
}
