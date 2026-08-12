package ru.questionhacker.trainer.practice;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class PracticeEventRegistry {

    private static final long TIMEOUT_MILLIS = 120_000;

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> listeners =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID attemptId, PracticeAssessmentService.AttemptView initial) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        if (!"EVALUATING".equals(initial.status())) {
            sendAndComplete(emitter, initial);
            return emitter;
        }
        var group = listeners.computeIfAbsent(attemptId, ignored -> new CopyOnWriteArrayList<>());
        group.add(emitter);
        emitter.onCompletion(() -> remove(attemptId, emitter));
        emitter.onTimeout(() -> remove(attemptId, emitter));
        emitter.onError(error -> remove(attemptId, emitter));
        send(emitter, initial);
        return emitter;
    }

    public void publish(PracticeAssessmentService.AttemptView terminal) {
        List<SseEmitter> group = listeners.remove(terminal.attemptId());
        if (group == null) return;
        group.forEach(emitter -> sendAndComplete(emitter, terminal));
    }

    private void sendAndComplete(SseEmitter emitter, Object data) {
        send(emitter, data);
        emitter.complete();
    }

    private void send(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().name("assessment").data(data));
        } catch (IOException error) {
            emitter.completeWithError(error);
        }
    }

    private void remove(UUID attemptId, SseEmitter emitter) {
        listeners.computeIfPresent(attemptId, (id, group) -> {
            group.remove(emitter);
            return group.isEmpty() ? null : group;
        });
    }
}
