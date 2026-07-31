package ru.questionhacker.trainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public DatabaseStore.SessionRow createSession(String title) {
        String safeTitle = title == null || title.isBlank() ? "Новый диалог" : title.strip();
        return store.createSession(safeTitle.substring(0, Math.min(180, safeTitle.length())));
    }

    public UUID send(UUID sessionId, String text, String model) {
        var session = requireSession(sessionId);
        String clean = text == null ? "" : text.strip();
        if (clean.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сообщение не должно быть пустым");
        }
        if (clean.length() > properties.chat().maxMessageChars()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сообщение превышает допустимую длину");
        }

        store.addMessage(sessionId, "USER", "USER", clean);
        if ("Новый диалог".equals(session.title())) {
            store.touchSession(sessionId, makeTitle(clean));
        }
        UUID runId = streams.create();
        String selectedModel = validateModel(model);
        executor.submit(() -> execute(runId, sessionId, clean, selectedModel));
        return runId;
    }

    private void execute(UUID runId, UUID sessionId, String userText, String model) {
        var streamed = new AtomicBoolean(false);
        var partial = new StringBuilder();
        try {
            String prompt = buildPrompt(sessionId);
            String answer = acp.ask(prompt, model, chunk -> {
                streamed.set(true);
                partial.append(chunk);
                streams.delta(runId, chunk);
            });
            var saved = store.addMessage(sessionId, "ASSISTANT", "ACP", answer);
            streams.done(runId, "ACP", saved.id());
        } catch (Exception error) {
            log.warn("Coach run {} failed", runId, error);
            if (streamed.get()) {
                String answer = partial + "\n\n---\n\n_Ответ ACP-агента оборвался: " + safe(error.getMessage()) + "_";
                var saved = store.addMessage(sessionId, "ASSISTANT", "ACP_PARTIAL", answer);
                streams.delta(runId, "\n\n---\n\n_Соединение с ACP-агентом оборвалось. Частичный ответ сохранён._");
                streams.done(runId, "ACP_PARTIAL", saved.id());
            } else if (properties.acp().fallbackEnabled()) {
                String answer = fallback.answer(userText);
                streamFallback(runId, answer);
                var saved = store.addMessage(sessionId, "ASSISTANT", "FALLBACK", answer);
                streams.done(runId, "FALLBACK", saved.id());
            } else {
                streams.error(runId, "ACP-агент недоступен: " + safe(error.getMessage()));
            }
        }
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

    private String buildPrompt(UUID sessionId) {
        List<DatabaseStore.MessageRow> history = store.latestMessages(sessionId, properties.chat().historyLimit());
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
        String[] chunks = answer.split("(?<=\n\n)");
        for (String chunk : chunks) {
            streams.delta(runId, chunk);
        }
    }

    private DatabaseStore.SessionRow requireSession(UUID id) {
        return store.findSession(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Диалог не найден"));
    }

    private String makeTitle(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.substring(0, Math.min(64, oneLine.length()));
    }

    private String safe(String message) {
        return message == null || message.isBlank() ? "неизвестная ошибка" : message;
    }
}
