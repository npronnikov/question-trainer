package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunStreamRegistryTest {

    @Test
    void subscriberReceivesBacklogAndLiveDeltaExactlyOnceInOrder() throws Exception {
        var subject = new RunStreamRegistry();
        UUID ownerId = UUID.randomUUID();
        UUID runId = subject.create(ownerId);
        subject.delta(runId, "старый");
        Object state = state(subject, runId);
        var emitter = new AtomicReference<SseEmitter>();
        var failure = new AtomicReference<Throwable>();
        Thread subscriber = new Thread(() -> {
            try {
                emitter.set(subject.subscribe(ownerId, runId));
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        synchronized (state) {
            subscriber.start();
            awaitBlocked(subscriber);
            subject.delta(runId, "новый");
        }
        subscriber.join(Duration.ofSeconds(2));

        assertThat(failure.get()).isNull();
        assertThat(subscriber.isAlive()).isFalse();
        assertThat(bufferedTexts(emitter.get())).containsExactly("старый", "новый");
    }

    private Object state(RunStreamRegistry subject, UUID runId) throws Exception {
        Field streamsField = RunStreamRegistry.class.getDeclaredField("streams");
        streamsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var streams = (ConcurrentHashMap<UUID, Object>) streamsField.get(subject);
        return streams.get(runId);
    }

    private void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
    }

    private List<String> bufferedTexts(SseEmitter emitter) throws Exception {
        Field attemptsField = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        attemptsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<ResponseBodyEmitter.DataWithMediaType> attempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>) attemptsField.get(emitter);
        var texts = new ArrayList<String>();
        for (var attempt : attempts) {
            if (attempt.getData() instanceof Map<?, ?> data && data.get("text") instanceof String text) {
                texts.add(text);
            }
        }
        return texts;
    }
}
