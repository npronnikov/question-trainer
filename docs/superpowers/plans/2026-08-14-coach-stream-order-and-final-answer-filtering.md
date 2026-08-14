# Coach Stream Order and Final Answer Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver coach response fragments exactly once in publication order while excluding Codex `commentary` messages from streaming, persistence, and successful interaction logs.

**Architecture:** Add a package-private, pure ACP message filter at the existing `AcpGateway` boundary and use it before appending or streaming any text. Make backlog replay and live-subscriber registration one atomic `StreamState` operation so publication cannot interleave between them.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring MVC `SseEmitter`, ACP Java SDK 0.14, JUnit 5, AssertJ, Maven.

---

## File map

- Create `backend/src/main/java/ru/questionhacker/trainer/AcpMessageFilter.java`: classify an ACP agent message by Codex phase and return only visible text.
- Create `backend/src/test/java/ru/questionhacker/trainer/AcpMessageFilterTest.java`: prove commentary suppression and compatibility behavior without starting an ACP process.
- Modify `backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java`: route every `AgentMessageChunk` through the filter before accumulation and callback delivery.
- Create `backend/src/test/java/ru/questionhacker/trainer/RunStreamRegistryTest.java`: deterministically reproduce the subscription race by controlling the private `StreamState` monitor and inspect buffered SSE events.
- Modify `backend/src/main/java/ru/questionhacker/trainer/RunStreamRegistry.java`: replay backlog and register an active emitter under one state lock.

### Task 1: Suppress ACP commentary at the gateway

**Files:**
- Create: `backend/src/test/java/ru/questionhacker/trainer/AcpMessageFilterTest.java`
- Create: `backend/src/main/java/ru/questionhacker/trainer/AcpMessageFilter.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java:69-76`

- [ ] **Step 1: Write the failing phase-filter tests**

Create `AcpMessageFilterTest.java`:

```java
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
    void keepsUnphasedAndUnknownAgentMessagesForCompatibility() {
        assertThat(AcpMessageFilter.visibleText(message("обычный ответ", null)))
                .contains("обычный ответ");
        assertThat(AcpMessageFilter.visibleText(message("новая фаза", Map.of(
                "codex", Map.of("phase", "future_phase")))))
                .contains("новая фаза");
    }

    private AgentMessageChunk message(String text, Map<String, Object> meta) {
        return new AgentMessageChunk(
                "agent_message_chunk", new TextContent(text), "message-id", meta);
    }
}
```

- [ ] **Step 2: Run the filter test and verify RED**

Run:

```bash
cd backend && mvn -Dtest=AcpMessageFilterTest test
```

Expected: test compilation fails because `AcpMessageFilter` does not exist.

- [ ] **Step 3: Implement the minimal pure filter**

Create `AcpMessageFilter.java`:

```java
package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import static com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;

import java.util.Map;
import java.util.Optional;

final class AcpMessageFilter {

    private AcpMessageFilter() {
    }

    static Optional<String> visibleText(AgentMessageChunk message) {
        if (!(message.content() instanceof TextContent text) || text.text() == null) {
            return Optional.empty();
        }
        return "commentary".equals(codexPhase(message.meta()))
                ? Optional.empty()
                : Optional.of(text.text());
    }

    private static String codexPhase(Map<String, Object> meta) {
        if (meta == null || !(meta.get("codex") instanceof Map<?, ?> codex)) {
            return null;
        }
        Object phase = codex.get("phase");
        return phase instanceof String value ? value : null;
    }
}
```

Replace the `AgentMessageChunk` handling in `AcpGateway` with:

```java
if (notification.update() instanceof AgentMessageChunk message) {
    AcpMessageFilter.visibleText(message).ifPresent(text -> {
        chunks.append(text);
        onChunk.accept(text);
    });
}
```

Remove the now-unused static import of `TextContent` from `AcpGateway`.

- [ ] **Step 4: Run focused and gateway-adjacent tests and verify GREEN**

Run:

```bash
cd backend && mvn -Dtest=AcpMessageFilterTest,AcpInteractionLoggerTest,AcpAvailabilityTest test
```

Expected: all selected tests pass with no compilation errors.

- [ ] **Step 5: Commit the filter change**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/AcpMessageFilter.java backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java backend/src/test/java/ru/questionhacker/trainer/AcpMessageFilterTest.java
git commit -m "fix: hide coach commentary messages"
```

### Task 2: Make SSE backlog handoff atomic

**Files:**
- Create: `backend/src/test/java/ru/questionhacker/trainer/RunStreamRegistryTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/RunStreamRegistry.java:29-49`

- [ ] **Step 1: Write a deterministic failing concurrency test**

Create `RunStreamRegistryTest.java`. The test holds the private `StreamState`
monitor, waits until the subscribing thread blocks at that monitor, publishes a
new delta reentrantly, then verifies the emitter's buffered `text` payloads:

```java
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
```

- [ ] **Step 2: Run the stream test and verify RED**

Run:

```bash
cd backend && mvn -Dtest=RunStreamRegistryTest test
```

Expected: assertion fails because buffered payloads are `новый, старый, новый`.

- [ ] **Step 3: Move emitter registration into the atomic handoff**

Change `RunStreamRegistry.subscribe` so the emitter is registered only after
backlog replay and only when the stream is still active:

```java
var emitter = new SseEmitter(SSE_TIMEOUT);
emitter.onCompletion(() -> state.emitters.remove(emitter));
emitter.onTimeout(() -> state.emitters.remove(emitter));
emitter.onError(error -> state.emitters.remove(emitter));

synchronized (state) {
    state.backlog.forEach(event -> send(emitter, event));
    if (state.completed) {
        send(emitter, state.terminalEvent);
        emitter.complete();
    } else {
        state.emitters.add(emitter);
    }
}
return emitter;
```

- [ ] **Step 4: Run focused stream and ownership tests and verify GREEN**

Run:

```bash
cd backend && mvn -Dtest=RunStreamRegistryTest,ChatOwnershipTest test
```

Expected: both test classes pass; the concurrency test receives exactly
`старый, новый`.

- [ ] **Step 5: Commit the atomic handoff**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/RunStreamRegistry.java backend/src/test/java/ru/questionhacker/trainer/RunStreamRegistryTest.java
git commit -m "fix: preserve coach stream event order"
```

### Task 3: Verify the complete change

**Files:**
- Verify: all backend and frontend sources

- [ ] **Step 1: Run the complete backend suite**

Run:

```bash
cd backend && mvn test
```

Expected: Maven reports `BUILD SUCCESS` with zero test failures and errors.

- [ ] **Step 2: Run frontend and script regression tests**

Run:

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
```

Expected: every Node test passes; no frontend contract change is required.

- [ ] **Step 3: Run static JavaScript checks and diff hygiene**

Run:

```bash
node --check frontend/api.js
node --check frontend/auth.js
node --check frontend/app.js
node --check scripts/dev-server.mjs
git diff --check HEAD~2
git status --short
```

Expected: each syntax check exits successfully, `git diff --check` prints
nothing, and `git status --short` is clean except for this plan document if it
has not yet been committed.

- [ ] **Step 4: Commit the implementation plan if still uncommitted**

```bash
git add docs/superpowers/plans/2026-08-14-coach-stream-order-and-final-answer-filtering.md
git commit -m "docs: plan clean coach response streaming"
```
