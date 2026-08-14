package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunStreamRegistryTest {

    @Test
    void subscriberReceivesBacklogAndLiveDeltaExactlyOnceInOrder() throws Exception {
        var emitter = new RecordingSseEmitter();
        var subject = new RunStreamRegistry(timeout -> emitter);
        UUID ownerId = UUID.randomUUID();
        UUID runId = subject.create(ownerId);
        subject.delta(runId, "старый");

        subscribeWhilePublishing(subject, ownerId, runId,
                () -> subject.delta(runId, "новый"));

        assertThat(emitter.eventNames()).containsExactly("delta", "delta");
        assertThat(emitter.textPayloads()).containsExactly("старый", "новый");
        assertThat(emitter.completed()).isFalse();
    }

    @Test
    void completionRacingWithSubscriptionArrivesOnceAfterBacklog() throws Exception {
        var emitter = new RecordingSseEmitter();
        var subject = new RunStreamRegistry(timeout -> emitter);
        UUID ownerId = UUID.randomUUID();
        UUID runId = subject.create(ownerId);
        subject.delta(runId, "частичный ответ");

        subscribeWhilePublishing(subject, ownerId, runId,
                () -> subject.done(runId, "ACP", UUID.randomUUID()));

        assertThat(emitter.eventNames()).containsExactly("delta", "done");
        assertThat(emitter.textPayloads()).containsExactly("частичный ответ");
        assertThat(emitter.completed()).isTrue();
    }

    @Test
    void failureRacingWithSubscriptionArrivesOnceAfterBacklog() throws Exception {
        var emitter = new RecordingSseEmitter();
        var subject = new RunStreamRegistry(timeout -> emitter);
        UUID ownerId = UUID.randomUUID();
        UUID runId = subject.create(ownerId);
        subject.delta(runId, "частичный ответ");

        subscribeWhilePublishing(subject, ownerId, runId,
                () -> subject.error(runId, "соединение потеряно"));

        assertThat(emitter.eventNames()).containsExactly("delta", "failure");
        assertThat(emitter.textPayloads()).containsExactly("частичный ответ");
        assertThat(emitter.completed()).isTrue();
    }

    private void subscribeWhilePublishing(RunStreamRegistry subject, UUID ownerId,
                                          UUID runId, Runnable publish) throws Exception {
        var beforeHandoff = new CountDownLatch(1);
        var continueSubscription = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread subscriber = new Thread(() -> {
            try {
                subject.subscribe(ownerId, runId, () -> {
                    beforeHandoff.countDown();
                    await(continueSubscription);
                });
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        subscriber.start();
        assertThat(beforeHandoff.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        publish.run();
        continueSubscription.countDown();
        subscriber.join(Duration.ofSeconds(2));

        assertThat(failure.get()).isNull();
        assertThat(subscriber.isAlive()).isFalse();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        private final List<RecordedEvent> events = new ArrayList<>();
        private boolean completed;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            String name = null;
            Map<?, ?> payload = Map.of();
            for (var part : builder.build()) {
                Object data = part.getData();
                if (data instanceof String value && value.startsWith("event:")) {
                    name = value.substring("event:".length()).lines().findFirst().orElseThrow().strip();
                } else if (data instanceof Map<?, ?> value) {
                    payload = value;
                }
            }
            events.add(new RecordedEvent(name, payload));
        }

        @Override
        public synchronized void complete() {
            completed = true;
            super.complete();
        }

        synchronized List<String> eventNames() {
            return events.stream().map(RecordedEvent::name).toList();
        }

        synchronized List<String> textPayloads() {
            return events.stream()
                    .map(RecordedEvent::payload)
                    .filter(payload -> payload.get("text") instanceof String)
                    .map(payload -> (String) payload.get("text"))
                    .toList();
        }

        synchronized boolean completed() {
            return completed;
        }
    }

    private record RecordedEvent(String name, Map<?, ?> payload) {
    }
}
