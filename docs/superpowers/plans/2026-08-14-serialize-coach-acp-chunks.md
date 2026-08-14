# Serialize Coach ACP Chunks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve live coach streaming while guaranteeing that browser chunks appear in exactly the same order as the accumulated and persisted ACP answer.

**Architecture:** Make `AcpResponseCollector` the serialization boundary for each visible ACP notification. Filtering, accumulation, and the SSE callback execute under one monitor, and reads of the accumulated state use the same monitor; the public SSE contract and frontend remain unchanged.

**Tech Stack:** Java 21, Spring Boot 3.5, ACP Java SDK 0.14, JUnit 5, AssertJ, Maven

---

### Task 1: Serialize ACP response collection

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/AcpResponseCollectorTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/AcpResponseCollector.java`

- [ ] **Step 1: Write the failing concurrency test**

Add imports for `CopyOnWriteArrayList`, `CountDownLatch`, `TimeUnit`, and `AtomicReference`, then add this test and helper to `AcpResponseCollectorTest`:

```java
@Test
void concurrentNotificationCannotOvertakeAnEarlierChunkCallback() throws Exception {
    var streamed = new CopyOnWriteArrayList<String>();
    var firstCallbackEntered = new CountDownLatch(1);
    var releaseFirstCallback = new CountDownLatch(1);
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
    Thread second = new Thread(() -> accept(subject, "B", failure));
    first.start();
    assertThat(firstCallbackEntered.await(2, TimeUnit.SECONDS)).isTrue();
    second.start();

    boolean secondOvertookFirst = secondCallbackEntered.await(250, TimeUnit.MILLISECONDS);
    releaseFirstCallback.countDown();
    first.join(2_000);
    second.join(2_000);

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
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd backend
mvn -Dtest=AcpResponseCollectorTest#concurrentNotificationCannotOvertakeAnEarlierChunkCallback test
```

Expected: FAIL because callback B enters while callback A is intentionally blocked; `secondOvertookFirst` is `true` and the streamed order is `B, A`.

- [ ] **Step 3: Implement the minimal serialization boundary**

Change the three stateful methods in `AcpResponseCollector` to use the instance monitor:

```java
@Override
public synchronized void accept(SessionNotification notification) {
    if (notification.update() instanceof AgentMessageChunk message) {
        AcpMessageFilter.visibleText(message).ifPresent(text -> {
            chunks.append(text);
            onChunk.accept(text);
        });
    }
}

synchronized boolean isEmpty() {
    return chunks.isEmpty();
}

synchronized String text() {
    return chunks.toString();
}
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
cd backend
mvn -Dtest=AcpResponseCollectorTest,AcpMessageFilterTest,RunStreamRegistryTest test
```

Expected: all focused tests pass; no thread remains alive and the new test observes `A, B` in both the stream and accumulated response.

- [ ] **Step 5: Run complete verification**

Run:

```bash
cd backend
mvn test
```

Expected: all backend tests pass.

Run from the repository root:

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
node --check frontend/app.js
git diff --check
```

Expected: all Node tests pass, JavaScript syntax is valid, and `git diff --check` produces no output.

- [ ] **Step 6: Commit the fix**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/AcpResponseCollector.java \
  backend/src/test/java/ru/questionhacker/trainer/AcpResponseCollectorTest.java \
  docs/superpowers/plans/2026-08-14-serialize-coach-acp-chunks.md
git commit -m "fix: serialize coach response chunks"
```

