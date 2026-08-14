# Versioned Coach Stream Snapshots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace append-only coach deltas with ordered, idempotent, versioned full-text snapshots from ACP transport through SSE to browser rendering.

**Architecture:** Use the ACP async builder with a synchronously completing update handler so inbound notifications are consumed without the sync client's cached-thread-pool hop. Accumulate one canonical answer, publish full snapshots through a latest-state SSE registry, and let a small frontend state machine reject stale versions and coalesce Markdown renders for 60 ms.

**Tech Stack:** Java 21, Spring Boot 3.5, ACP Java SDK 0.14, Project Reactor, JUnit 5, AssertJ, browser JavaScript, Node test runner

---

### Task 1: Make the ACP boundary sequential and snapshot-based

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/AcpResponseCollectorTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/AcpResponseCollector.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java`

- [ ] **Step 1: Change the collector test to require cumulative snapshots**

Replace the delta expectation in `streamsAndCollectsOnlyTheFinalAnswer` with:

```java
assertThat(streamed).containsExactly("ответ ", "ответ коуча");
assertThat(subject.text()).isEqualTo("ответ коуча");
```

Remove the old concurrency test: it encodes monitor acquisition order rather than ACP wire order and does not reproduce the SDK scheduler hop.

- [ ] **Step 2: Run the collector test and verify RED**

Run:

```bash
cd backend
mvn -Dtest=AcpResponseCollectorTest test
```

Expected: FAIL because the callback currently receives `"ответ "` and `"коуча"` rather than cumulative snapshots.

- [ ] **Step 3: Publish the canonical full text from the collector**

Change the collector callback body to:

```java
chunks.append(text);
onSnapshot.accept(chunks.toString());
```

Rename the field and constructor parameter from `onChunk` to `onSnapshot`. Keep filtering and synchronized access in place as defense in depth.

- [ ] **Step 4: Replace the ACP sync client with the async client**

In `AcpGateway`, build the client with `AcpClient.async(transport)`. Adapt synchronous filesystem callbacks to Reactor and keep update handling synchronous:

```java
.readTextFileHandler(request -> Mono.fromSupplier(
        () -> new ReadTextFileResponse(workspace.read(request.path()))))
.writeTextFileHandler(request -> Mono.fromSupplier(() -> {
    workspace.write(request.path(), request.content());
    return new WriteTextFileResponse();
}))
.sessionUpdateConsumer(notification -> Mono.fromRunnable(
        () -> responseCollector.accept(notification)))
```

Call each lifecycle operation with `.block()`, null-check initialize and session responses with `Objects.requireNonNull`, and release the client in `finally`:

```java
try {
    // initialize, authenticate, newSession, optional model, prompt
} finally {
    try {
        client.closeGracefully().block(Duration.ofSeconds(10));
    } finally {
        client.close();
    }
}
```

The handler must not use `subscribeOn` or another scheduler; synchronous inner completion is what preserves inbound notification order.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
cd backend
mvn -Dtest=AcpResponseCollectorTest,AcpMessageFilterTest,AcpAvailabilityTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the ACP boundary**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/AcpGateway.java \
  backend/src/main/java/ru/questionhacker/trainer/AcpResponseCollector.java \
  backend/src/test/java/ru/questionhacker/trainer/AcpResponseCollectorTest.java
git commit -m "fix: preserve ACP coach notification order"
```

### Task 2: Replace the SSE delta backlog with latest versioned state

**Files:**
- Modify: `backend/src/test/java/ru/questionhacker/trainer/RunStreamRegistryTest.java`
- Modify: `backend/src/main/java/ru/questionhacker/trainer/RunStreamRegistry.java`

- [ ] **Step 1: Write failing snapshot contract tests**

Replace the existing delta tests with tests that assert these exact payloads:

```java
subject.snapshot(runId, "первый");
subject.snapshot(runId, "первый второй");
subject.subscribe(ownerId, runId);

assertThat(emitter.eventNames()).containsExactly("snapshot");
assertThat(emitter.payloads()).containsExactly(Map.of(
        "version", 2L,
        "text", "первый второй"));
```

Add a terminal test:

```java
UUID messageId = UUID.randomUUID();
subject.subscribe(ownerId, runId);
subject.snapshot(runId, "часть");
subject.done(runId, "полный ответ", "ACP", messageId);

assertThat(emitter.eventNames()).containsExactly("snapshot", "done");
assertThat(emitter.payloads().getLast()).isEqualTo(Map.of(
        "version", 2L,
        "text", "полный ответ",
        "source", "ACP",
        "messageId", messageId.toString()));
```

Keep the subscription race helper and make it publish a snapshot while subscription is paused. Assert one latest snapshot followed by future snapshots, never replay of the full history. Add a test that snapshot and done calls after a terminal event are ignored.

- [ ] **Step 2: Run the registry test and verify RED**

Run:

```bash
cd backend
mvn -Dtest=RunStreamRegistryTest test
```

Expected: compilation failure because `snapshot` and the new `done` signature do not exist.

- [ ] **Step 3: Implement latest-state publication**

Replace `delta` with:

```java
public void snapshot(UUID runId, String text) {
    var state = streams.get(runId);
    if (state == null) return;
    synchronized (state) {
        if (state.completed) return;
        state.version++;
        state.text = text;
        state.hasSnapshot = true;
        sendAll(state, new StreamEvent("snapshot", Map.of(
                "version", state.version,
                "text", state.text)));
    }
}
```

Change terminal completion to accept full text and increment the version:

```java
public void done(UUID runId, String text, String source, UUID messageId) {
    var state = streams.get(runId);
    if (state == null) return;
    synchronized (state) {
        if (state.completed) return;
        state.completed = true;
        state.version++;
        state.text = text;
        state.hasSnapshot = true;
        state.terminalEvent = new StreamEvent("done", Map.of(
                "version", state.version,
                "text", text,
                "source", source,
                "messageId", messageId.toString()));
        sendAll(state, state.terminalEvent);
        completeAll(state);
    }
}
```

`subscribe` must send only `done` for a completed run. For an active run, it sends at most one current snapshot before registering the emitter:

```java
if (state.completed) {
    send(emitter, state.terminalEvent);
    emitter.complete();
} else {
    if (state.hasSnapshot) {
        send(emitter, new StreamEvent("snapshot", Map.of(
                "version", state.version,
                "text", state.text)));
    }
    state.emitters.add(emitter);
}
```

Do not retain `backlog`. Add focused `sendAll` and `completeAll` helpers and preserve failure behavior.

- [ ] **Step 4: Run registry tests and verify GREEN**

Run:

```bash
cd backend
mvn -Dtest=RunStreamRegistryTest test
```

Expected: PASS.

- [ ] **Step 5: Commit the SSE state registry**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/RunStreamRegistry.java \
  backend/src/test/java/ru/questionhacker/trainer/RunStreamRegistryTest.java
git commit -m "feat: stream versioned coach snapshots"
```

### Task 3: Send canonical outcomes from ChatService

**Files:**
- Modify: `backend/src/main/java/ru/questionhacker/trainer/ChatService.java`
- Test: `backend/src/test/java/ru/questionhacker/trainer/RunStreamRegistryTest.java`

- [ ] **Step 1: Strengthen the terminal contract test**

In the registry tests, assert that a terminal partial or fallback answer replaces a different preceding snapshot and has a strictly greater version. This test is RED until Task 2's terminal contract is present and protects the service integration from reverting to suffix deltas.

- [ ] **Step 2: Update successful and partial ACP flows**

Use one atomic reference for the latest canonical snapshot:

```java
var latestSnapshot = new AtomicReference<>("");
String answer = acp.ask(prompt, model, snapshot -> {
    latestSnapshot.set(snapshot);
    streams.snapshot(runId, snapshot);
});
var saved = store.addMessage(ownerId, sessionId, "ASSISTANT", "ACP", answer);
streams.done(runId, answer, "ACP", saved.id());
```

On failure with a non-empty snapshot, build the saved partial result from that full snapshot and send it only through the authoritative terminal event:

```java
String answer = latestSnapshot.get()
        + "\n\n---\n\n_Ответ ACP-агента оборвался: " + safe(error.getMessage()) + "_";
var saved = store.addMessage(ownerId, sessionId, "ASSISTANT", "ACP_PARTIAL", answer);
streams.done(runId, answer, "ACP_PARTIAL", saved.id());
```

Remove the separate connection-loss delta.

- [ ] **Step 3: Update fallback streaming**

Build cumulative fallback snapshots by paragraph:

```java
private void streamFallback(UUID runId, String answer) {
    var snapshot = new StringBuilder();
    for (String chunk : answer.split("(?<=\\n\\n)")) {
        snapshot.append(chunk);
        streams.snapshot(runId, snapshot.toString());
    }
}
```

Then terminate with:

```java
streams.done(runId, answer, "FALLBACK", saved.id());
```

Keep `failure` for the no-text/no-fallback branch.

- [ ] **Step 4: Run backend tests**

Run:

```bash
cd backend
mvn -Dtest=AcpResponseCollectorTest,RunStreamRegistryTest,ChatOwnershipTest test
```

Expected: PASS.

- [ ] **Step 5: Commit service integration**

```bash
git add backend/src/main/java/ru/questionhacker/trainer/ChatService.java \
  backend/src/test/java/ru/questionhacker/trainer/RunStreamRegistryTest.java
git commit -m "feat: publish canonical coach outcomes"
```

### Task 4: Make browser rendering idempotent and throttled

**Files:**
- Create: `frontend/coach-stream.js`
- Create: `frontend/tests/coach-stream.test.mjs`
- Modify: `frontend/index.html`
- Modify: `frontend/app.js`
- Modify: `frontend/sw.js`
- Modify: `frontend/tests/thin-client.test.mjs`

- [ ] **Step 1: Write failing state-machine tests**

Create `frontend/tests/coach-stream.test.mjs` using `createRequire` to load the browser/CommonJS module. Test with injected `schedule` and `cancel` functions:

```javascript
test('new snapshots replace text and stale versions are ignored', () => {
  const scheduled = [];
  const rendered = [];
  const stream = createCoachStream({
    render: text => rendered.push(text),
    schedule: callback => (scheduled.push(callback), scheduled.length - 1),
    cancel: () => {}
  });

  stream.accept({ version: 2, text: 'новый текст' });
  stream.accept({ version: 1, text: 'старый текст' });
  scheduled[0]();

  assert.deepEqual(rendered, ['новый текст']);
  assert.equal(stream.text(), 'новый текст');
});
```

Add tests that multiple accepted snapshots schedule one render and that `finish({version, text})` cancels pending work and immediately renders authoritative final text.

- [ ] **Step 2: Run the frontend test and verify RED**

Run:

```bash
node --test frontend/tests/coach-stream.test.mjs
```

Expected: FAIL because `frontend/coach-stream.js` does not exist.

- [ ] **Step 3: Implement the coach stream state machine**

Create a small UMD-style module exporting `createCoachStream`. It must validate `Number.isSafeInteger(version)`, `version >= 0`, and `typeof text === 'string'`; accept only versions greater than `acceptedVersion`; schedule at most one render with a default delay of 60 ms; and expose `accept`, `finish`, `text`, and `dispose`.

Core behavior:

```javascript
function accept(payload) {
  if (!valid(payload) || payload.version <= acceptedVersion) return false;
  acceptedVersion = payload.version;
  latestText = payload.text;
  if (pending === null) {
    pending = schedule(() => {
      pending = null;
      render(latestText);
    }, delay);
  }
  return true;
}

function finish(payload) {
  if (!valid(payload)) return false;
  acceptedVersion = Math.max(acceptedVersion, payload.version);
  latestText = payload.text;
  if (pending !== null) cancel(pending);
  pending = null;
  render(latestText);
  return true;
}
```

- [ ] **Step 4: Run the state-machine tests and verify GREEN**

Run:

```bash
node --test frontend/tests/coach-stream.test.mjs
```

Expected: PASS.

- [ ] **Step 5: Integrate snapshot events into the app**

Load `coach-stream.js` before `app.js` in `index.html`. In `consumeChatRun`, create a state instance whose render callback updates the message body with `renderMarkdown(text)`. Replace the `delta` listener with:

```javascript
source.addEventListener('snapshot', event => {
  stream.accept(JSON.parse(event.data));
});
```

Handle terminal state without concatenation:

```javascript
source.addEventListener('done', event => {
  const payload = JSON.parse(event.data);
  source.close();
  activeStream = null;
  stream.finish(payload);
  finishChat(streamId, stream.text(), payload.source || 'ACP');
  reloadSessions(currentSessionId).catch(() => {});
});
```

On `failure` or `onerror`, dispose the stream before calling `finishChat`. Use `stream.text()` for interrupted output.

- [ ] **Step 6: Update offline caching and static contract coverage**

Add `./coach-stream.js` to the service worker's `OFFLINE` array and increment `CACHE` from `question-hacker-v17` to `question-hacker-v18`. Add thin-client assertions that `coach-stream.js` loads before `app.js`, `snapshot` is listened to, and the old `delta` listener is absent.

- [ ] **Step 7: Run frontend tests**

Run:

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
node --check frontend/coach-stream.js
node --check frontend/app.js
```

Expected: PASS with no syntax errors.

- [ ] **Step 8: Commit frontend integration**

```bash
git add frontend/coach-stream.js frontend/tests/coach-stream.test.mjs \
  frontend/index.html frontend/app.js frontend/sw.js frontend/tests/thin-client.test.mjs
git commit -m "feat: render idempotent coach snapshots"
```

### Task 5: Complete verification

**Files:**
- Verify all modified files

- [ ] **Step 1: Run the full backend suite**

```bash
cd backend
mvn test
```

Expected: all tests PASS.

- [ ] **Step 2: Run all frontend and repository tests**

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
node --check frontend/coach-stream.js
node --check frontend/app.js
```

Expected: all tests PASS and both syntax checks succeed.

- [ ] **Step 3: Check the final diff**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intentional implementation and plan files are present.

- [ ] **Step 4: Review protocol invariants**

Confirm in the diff that:

- no production code calls `streams.delta`;
- no frontend code listens for `delta`;
- `done` always contains `version`, `text`, `source`, and `messageId`;
- the ACP update handler contains no scheduler hop;
- a reconnect receives at most one snapshot before the terminal event.
