package ru.questionhacker.trainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class ChatServiceStreamTest {

    private final UUID ownerId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final DatabaseStore store = mock(DatabaseStore.class);
    private final PromptCatalog prompts = new PromptCatalog();
    private final AcpGateway acp = mock(AcpGateway.class);
    private final FallbackCoach fallback = mock(FallbackCoach.class);
    private final RunStreamRegistry streams = mock(RunStreamRegistry.class);

    @Test
    void successfulAcpRunPublishesSnapshotsAndCanonicalDoneText() {
        prepareStore();
        when(acp.ask(anyString(), eq("test-model"), any())).thenAnswer(invocation -> {
            Consumer<String> onSnapshot = invocation.getArgument(2);
            onSnapshot.accept("первая ");
            onSnapshot.accept("первая часть");
            return "первая часть";
        });
        var saved = message("ACP", "первая часть");
        when(store.addMessage(ownerId, sessionId, "ASSISTANT", "ACP", "первая часть"))
                .thenReturn(saved);

        UUID runId = service(true).send(ownerId, sessionId, "Вопрос", "test-model");

        verify(streams).snapshot(runId, "первая ");
        verify(streams).snapshot(runId, "первая часть");
        verify(streams).done(runId, "первая часть", "ACP", saved.id());
    }

    @Test
    void interruptedAcpRunUsesLatestSnapshotAsCanonicalPartialText() {
        prepareStore();
        when(acp.ask(anyString(), eq("test-model"), any())).thenAnswer(invocation -> {
            Consumer<String> onSnapshot = invocation.getArgument(2);
            onSnapshot.accept("частичный ");
            onSnapshot.accept("частичный ответ");
            throw new IllegalStateException("обрыв");
        });
        String expected = "частичный ответ\n\n---\n\n_Ответ ACP-агента оборвался: обрыв_";
        var saved = message("ACP_PARTIAL", expected);
        when(store.addMessage(ownerId, sessionId, "ASSISTANT", "ACP_PARTIAL", expected))
                .thenReturn(saved);

        UUID runId = service(true).send(ownerId, sessionId, "Вопрос", "test-model");

        verify(streams).snapshot(runId, "частичный ответ");
        verify(streams).done(runId, expected, "ACP_PARTIAL", saved.id());
        verify(streams, never()).error(eq(runId), anyString());
    }

    @Test
    void fallbackPublishesCumulativeSnapshotAndCanonicalDoneText() {
        prepareStore();
        when(acp.ask(anyString(), eq("test-model"), any()))
                .thenThrow(new IllegalStateException("offline"));
        String answer = "первый абзац\n\nвторой абзац";
        when(fallback.answer("Вопрос")).thenReturn(answer);
        var saved = message("FALLBACK", answer);
        when(store.addMessage(ownerId, sessionId, "ASSISTANT", "FALLBACK", answer))
                .thenReturn(saved);

        UUID runId = service(true).send(ownerId, sessionId, "Вопрос", "test-model");

        verify(streams).snapshot(runId, answer);
        verify(streams).done(runId, answer, "FALLBACK", saved.id());
    }

    @Test
    void failureWithoutTextOrFallbackPublishesFailureOnly() {
        prepareStore();
        when(acp.ask(anyString(), eq("test-model"), any()))
                .thenThrow(new IllegalStateException("offline"));

        UUID runId = service(false).send(ownerId, sessionId, "Вопрос", "test-model");

        verify(streams).error(runId, "ACP-агент недоступен: offline");
        verify(streams, never()).done(eq(runId), anyString(), anyString(), any());
    }

    @Test
    void successfulAcpPersistenceFailurePublishesFailureWithoutFalsePartialRecovery() {
        prepareStore();
        when(acp.ask(anyString(), eq("test-model"), any())).thenAnswer(invocation -> {
            Consumer<String> onSnapshot = invocation.getArgument(2);
            onSnapshot.accept("полный ответ");
            return "полный ответ";
        });
        when(store.addMessage(ownerId, sessionId, "ASSISTANT", "ACP", "полный ответ"))
                .thenThrow(new IllegalStateException("database offline"));

        UUID runId = service(true).send(ownerId, sessionId, "Вопрос", "test-model");

        verify(streams).error(runId, "Не удалось сохранить ответ: database offline");
        verify(store, never()).addMessage(eq(ownerId), eq(sessionId), eq("ASSISTANT"),
                eq("ACP_PARTIAL"), anyString());
        verify(fallback, never()).answer(anyString());
        verify(streams, never()).done(eq(runId), anyString(), anyString(), any());
    }

    @Test
    void partialPersistenceFailureStillTerminatesTheStream() {
        prepareStore();
        when(acp.ask(anyString(), eq("test-model"), any())).thenAnswer(invocation -> {
            Consumer<String> onSnapshot = invocation.getArgument(2);
            onSnapshot.accept("частичный ответ");
            throw new IllegalStateException("обрыв");
        });
        String partial = "частичный ответ\n\n---\n\n_Ответ ACP-агента оборвался: обрыв_";
        when(store.addMessage(ownerId, sessionId, "ASSISTANT", "ACP_PARTIAL", partial))
                .thenThrow(new IllegalStateException("database offline"));

        UUID runId = service(true).send(ownerId, sessionId, "Вопрос", "test-model");

        verify(streams).error(runId, "Не удалось сохранить ответ: database offline");
        verify(streams, never()).done(eq(runId), anyString(), anyString(), any());
    }

    @Test
    void fallbackFailureStillTerminatesTheStream() {
        prepareStore();
        when(acp.ask(anyString(), eq("test-model"), any()))
                .thenThrow(new IllegalStateException("offline"));
        when(fallback.answer("Вопрос")).thenThrow(new IllegalStateException("fallback failed"));

        UUID runId = service(true).send(ownerId, sessionId, "Вопрос", "test-model");

        verify(streams).error(runId, "Резервный ответ недоступен: fallback failed");
        verify(streams, never()).done(eq(runId), anyString(), anyString(), any());
    }

    private ChatService service(boolean fallbackEnabled) {
        var properties = new AppProperties(
                new AppProperties.Acp(true, fallbackEnabled, "codex", List.of(), ".",
                        Duration.ofSeconds(30), 1024, List.of(),
                        List.of("test-model"), "test-model"),
                new AppProperties.Chat(12000, 24),
                new AppProperties.Admin("", "", ""));
        return new ChatService(store, prompts, acp, fallback, streams,
                new SameThreadExecutor(), properties);
    }

    private void prepareStore() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var session = new DatabaseStore.SessionRow(
                sessionId, ownerId, "Диалог", null, now, now);
        when(store.findSession(ownerId, sessionId)).thenReturn(Optional.of(session));
        when(store.latestMessages(ownerId, sessionId, 24)).thenReturn(List.of());
        when(streams.create(ownerId)).thenReturn(runId);
    }

    private DatabaseStore.MessageRow message(String source, String content) {
        return new DatabaseStore.MessageRow(
                UUID.randomUUID(), sessionId, "ASSISTANT", source, content,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static final class SameThreadExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new IllegalStateException("executor stopped");
            }
            command.run();
        }
    }
}
