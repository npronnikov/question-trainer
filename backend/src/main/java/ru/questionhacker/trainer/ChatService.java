package ru.questionhacker.trainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final DatabaseStore store;
    private final PromptCatalog prompts;
    private final AcpGateway acp;
    private final FallbackCoach fallback;
    private final RunStreamRegistry streams;
    private final ExecutorService executor;
    private final AppProperties properties;

    public ChatService(DatabaseStore store, PromptCatalog prompts, AcpGateway acp,
                       FallbackCoach fallback, RunStreamRegistry streams,
                       ExecutorService executor, AppProperties properties) {
        this.store = store;
        this.prompts = prompts;
        this.acp = acp;
        this.fallback = fallback;
        this.streams = streams;
        this.executor = executor;
        this.properties = properties;
    }

    public DatabaseStore.SessionRow createSession(UUID ownerId, String title) {
        String safeTitle = title == null || title.isBlank() ? "Новый диалог" : title.strip();
        return store.createSession(ownerId, safeTitle.substring(0, Math.min(180, safeTitle.length())));
    }

    public void deleteSession(UUID ownerId, UUID sessionId) {
        if (!store.deleteSession(ownerId, sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Диалог не найден");
        }
    }

    public DatabaseStore.SessionRow renameSession(UUID ownerId, UUID sessionId, String title) {
        requireSession(ownerId, sessionId);
        String clean = title == null ? "" : title.strip();
        if (clean.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название не должно быть пустым");
        }
        if (!store.touchSession(ownerId, sessionId, clean)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Диалог не найден");
        }
        return requireSession(ownerId, sessionId);
    }

    public List<DatabaseStore.MessageRow> messages(UUID ownerId, UUID sessionId) {
        requireSession(ownerId, sessionId);
        return store.listMessages(ownerId, sessionId);
    }

    public UUID send(UUID ownerId, UUID sessionId, String text, String model) {
        var session = requireSession(ownerId, sessionId);
        String clean = text == null ? "" : text.strip();
        if (clean.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сообщение не должно быть пустым");
        }
        if (clean.length() > properties.chat().maxMessageChars()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сообщение превышает допустимую длину");
        }

        store.addMessage(ownerId, sessionId, "USER", "USER", clean);
        if ("Новый диалог".equals(session.title())) {
            store.touchSession(ownerId, sessionId, makeTitle(clean));
        }
        UUID runId = streams.create(ownerId);
        String selectedModel = validateModel(model);
        executor.submit(() -> execute(runId, ownerId, sessionId, clean, selectedModel));
        return runId;
    }

    private void execute(UUID runId, UUID ownerId, UUID sessionId, String userText, String model) {
        var latestSnapshot = new AtomicReference<>("");
        String prompt;
        try {
            prompt = buildPrompt(ownerId, sessionId);
        } catch (Exception error) {
            log.warn("Coach run {} could not build its prompt", runId, error);
            streams.error(runId, "Не удалось подготовить ответ: " + safe(error.getMessage()));
            return;
        }

        String answer;
        try {
            answer = acp.ask(prompt, model, snapshot -> {
                latestSnapshot.set(snapshot);
                streams.snapshot(runId, snapshot);
            });
        } catch (Exception error) {
            recoverFromAcpFailure(runId, ownerId, sessionId, userText,
                    latestSnapshot.get(), error);
            return;
        }

        saveAndComplete(runId, ownerId, sessionId, answer, "ACP");
    }

    private void recoverFromAcpFailure(UUID runId, UUID ownerId, UUID sessionId,
                                       String userText, String latestSnapshot, Exception error) {
        log.warn("Coach run {} failed", runId, error);
        if (!latestSnapshot.isEmpty()) {
            String partial = latestSnapshot
                    + "\n\n---\n\n_Ответ ACP-агента оборвался: "
                    + safe(error.getMessage()) + "_";
            saveAndComplete(runId, ownerId, sessionId, partial, "ACP_PARTIAL");
            return;
        }
        if (!properties.acp().fallbackEnabled()) {
            streams.error(runId, "ACP-агент недоступен: " + safe(error.getMessage()));
            return;
        }

        try {
            String answer = fallback.answer(userText);
            streamFallback(runId, answer);
            saveAndComplete(runId, ownerId, sessionId, answer, "FALLBACK");
        } catch (Exception fallbackError) {
            log.warn("Coach run {} fallback failed", runId, fallbackError);
            streams.error(runId, "Резервный ответ недоступен: " + safe(fallbackError.getMessage()));
        }
    }

    private void saveAndComplete(UUID runId, UUID ownerId, UUID sessionId,
                                 String answer, String source) {
        DatabaseStore.MessageRow saved;
        try {
            saved = store.addMessage(ownerId, sessionId, "ASSISTANT", source, answer);
        } catch (Exception error) {
            log.warn("Coach run {} could not persist its {} answer", runId, source, error);
            streams.error(runId, "Не удалось сохранить ответ: " + safe(error.getMessage()));
            return;
        }
        streams.done(runId, answer, source, saved.id());
    }

    private String validateModel(String requested) {
        String model = requested == null || requested.isBlank()
                ? properties.acp().defaultModel()
                : requested.strip();
        if (model == null || model.isBlank()) return null;
        if (!properties.acp().models().contains(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестная модель: " + model);
        }
        return model;
    }

    private String buildPrompt(UUID ownerId, UUID sessionId) {
        List<DatabaseStore.MessageRow> history = store.latestMessages(
                ownerId, sessionId, properties.chat().historyLimit());
        var result = new StringBuilder(prompts.trainingCoach());
        result.append("\n\n# Контекст диалога\n\n");
        for (var message : history) {
            result.append(message.role().equals("USER") ? "Пользователь" : "Тренер")
                    .append(":\n")
                    .append(message.content())
                    .append("\n\n");
        }
        result.append("Ответь на последнее сообщение пользователя согласно роли и правилам выше.");
        return result.toString();
    }

    private void streamFallback(UUID runId, String answer) {
        var snapshot = new StringBuilder();
        String[] chunks = answer.split("(?<=\n\n)");
        for (String chunk : chunks) {
            snapshot.append(chunk);
            streams.snapshot(runId, snapshot.toString());
        }
    }

    private DatabaseStore.SessionRow requireSession(UUID ownerId, UUID id) {
        return store.findSession(ownerId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Диалог не найден"));
    }

    private String makeTitle(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        int count = Math.min(30, oneLine.codePointCount(0, oneLine.length()));
        int end = oneLine.offsetByCodePoints(0, count);
        return oneLine.substring(0, end) + "...";
    }

    private String safe(String message) {
        return message == null || message.isBlank() ? "неизвестная ошибка" : message;
    }
}
