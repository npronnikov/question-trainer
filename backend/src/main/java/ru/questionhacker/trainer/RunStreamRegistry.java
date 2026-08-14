package ru.questionhacker.trainer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class RunStreamRegistry {

    private static final long SSE_TIMEOUT = Duration.ofMinutes(8).toMillis();
    private final Map<UUID, StreamState> streams = new ConcurrentHashMap<>();
    private final LongFunction<SseEmitter> emitterFactory;

    public RunStreamRegistry() {
        this(SseEmitter::new);
    }

    RunStreamRegistry(LongFunction<SseEmitter> emitterFactory) {
        this.emitterFactory = emitterFactory;
    }

    public UUID create(UUID ownerId) {
        var id = UUID.randomUUID();
        streams.put(id, new StreamState(ownerId));
        return id;
    }

    public SseEmitter subscribe(UUID ownerId, UUID runId) {
        return subscribe(ownerId, runId, () -> { });
    }

    SseEmitter subscribe(UUID ownerId, UUID runId, Runnable beforeHandoff) {
        var state = streams.get(runId);
        if (state == null || !state.ownerId.equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запуск не найден");
        }
        var emitter = emitterFactory.apply(SSE_TIMEOUT);
        emitter.onCompletion(() -> state.emitters.remove(emitter));
        emitter.onTimeout(() -> state.emitters.remove(emitter));
        emitter.onError(error -> state.emitters.remove(emitter));

        beforeHandoff.run();
        synchronized (state) {
            if (state.completed) {
                send(emitter, state.terminalEvent);
                emitter.complete();
            } else {
                if (state.hasSnapshot) {
                    send(emitter, snapshotEvent(state));
                }
                state.emitters.add(emitter);
            }
        }
        return emitter;
    }

    public void snapshot(UUID runId, String text) {
        var state = streams.get(runId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.completed) {
                return;
            }
            state.version++;
            state.text = text;
            state.hasSnapshot = true;
            sendAll(state, snapshotEvent(state));
        }
    }

    public void done(UUID runId, String text, String source, UUID messageId) {
        var state = streams.get(runId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.completed) {
                return;
            }
            state.completed = true;
            state.version++;
            state.text = text;
            state.hasSnapshot = true;
            state.terminalEvent = new StreamEvent("done", Map.of(
                    "version", state.version,
                    "text", state.text,
                    "source", source,
                    "messageId", messageId.toString()));
            sendAll(state, state.terminalEvent);
            completeAll(state);
        }
    }

    public void error(UUID runId, String message) {
        var state = streams.get(runId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.completed) {
                return;
            }
            state.completed = true;
            state.terminalEvent = new StreamEvent("failure", Map.of("message", message));
            sendAll(state, state.terminalEvent);
            completeAll(state);
        }
    }

    private StreamEvent snapshotEvent(StreamState state) {
        return new StreamEvent("snapshot", Map.of(
                "version", state.version,
                "text", state.text));
    }

    private void sendAll(StreamState state, StreamEvent event) {
        state.emitters.forEach(emitter -> send(emitter, event));
    }

    private void completeAll(StreamState state) {
        state.emitters.forEach(SseEmitter::complete);
        state.emitters.clear();
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
        private final UUID ownerId;
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private long version;
        private String text = "";
        private boolean hasSnapshot;
        private boolean completed;
        private StreamEvent terminalEvent;

        private StreamState(UUID ownerId) {
            this.ownerId = ownerId;
        }
    }

    private record StreamEvent(String name, Object data) {
    }
}
