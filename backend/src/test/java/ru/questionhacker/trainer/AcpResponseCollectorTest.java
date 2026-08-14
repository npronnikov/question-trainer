package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import static com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;
import static com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AcpResponseCollectorTest {

    @Test
    void streamsAndCollectsOnlyTheFinalAnswer() {
        var streamed = new ArrayList<String>();
        var subject = new AcpResponseCollector(streamed::add);

        subject.accept(notification("служебный текст", "commentary"));
        subject.accept(notification("ответ ", "final_answer"));
        subject.accept(notification("коуча", "final_answer"));

        assertThat(streamed).containsExactly("ответ ", "коуча");
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

    @Test
    void concurrentNotificationCannotOvertakeAnEarlierChunkCallback() throws Exception {
        var streamed = new CopyOnWriteArrayList<String>();
        var firstCallbackEntered = new CountDownLatch(1);
        var releaseFirstCallback = new CountDownLatch(1);
        var secondAcceptStarted = new CountDownLatch(1);
        var secondCallbackEntered = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var subject = new AcpResponseCollector(text -> {
            if (text.equals("A")) {
                firstCallbackEntered.countDown();
                await(releaseFirstCallback);
            } else if (text.equals("B")) {
                secondCallbackEntered.countDown();
            }
            streamed.add(text);
        });

        Thread first = new Thread(() -> accept(subject, "A", failure));
        Thread second = new Thread(() -> {
            secondAcceptStarted.countDown();
            accept(subject, "B", failure);
        });
        first.start();
        boolean firstEntered = firstCallbackEntered.await(2, TimeUnit.SECONDS);
        boolean secondStarted = false;
        boolean secondAttempted = false;
        boolean secondOvertookFirst = false;
        try {
            if (firstEntered) {
                second.start();
                secondStarted = true;
                secondAttempted = secondAcceptStarted.await(2, TimeUnit.SECONDS);
                if (secondAttempted) {
                    secondOvertookFirst = secondCallbackEntered.await(250, TimeUnit.MILLISECONDS);
                }
            }
        } finally {
            releaseFirstCallback.countDown();
            first.join(2_000);
            if (secondStarted) {
                second.join(2_000);
            }
        }

        assertThat(firstEntered).isTrue();
        assertThat(secondAttempted).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(secondOvertookFirst).isFalse();
        assertThat(streamed).containsExactly("A", "B");
        assertThat(subject.text()).isEqualTo("AB");
    }

    private void accept(AcpResponseCollector subject, String text,
                        AtomicReference<Throwable> failure) {
        try {
            subject.accept(notification(text, "final_answer"));
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
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
