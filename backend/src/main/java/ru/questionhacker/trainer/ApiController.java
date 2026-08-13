package ru.questionhacker.trainer;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ru.questionhacker.trainer.auth.AuthService;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DatabaseStore store;
    private final ChatService chat;
    private final RunStreamRegistry streams;
    private final AcpGateway acp;
    private final AppProperties properties;
    private final AuthService auth;

    public ApiController(DatabaseStore store, ChatService chat, RunStreamRegistry streams,
                         AcpGateway acp, AppProperties properties, AuthService auth) {
        this.store = store;
        this.chat = chat;
        this.streams = streams;
        this.acp = acp;
        this.properties = properties;
        this.auth = auth;
    }

    @GetMapping("/system/status")
    public SystemStatus status() {
        return new SystemStatus(
                acp.enabled(),
                acp.available(),
                properties.acp().fallbackEnabled(),
                acp.unavailabilityReason(),
                acp.commandDescription(),
                properties.acp().models(),
                properties.acp().defaultModel(),
                "H2 file",
                "seven-category trainer");
    }

    @GetMapping("/chat/sessions")
    public List<DatabaseStore.SessionRow> sessions() {
        return store.listSessions(auth.requireCurrentUser().id());
    }

    @PostMapping("/chat/sessions")
    public DatabaseStore.SessionRow createSession(@RequestBody(required = false) CreateSessionRequest request) {
        return chat.createSession(auth.requireCurrentUser().id(), request == null ? null : request.title());
    }

    @PatchMapping("/chat/sessions/{sessionId}")
    public DatabaseStore.SessionRow renameSession(@PathVariable UUID sessionId,
                                                   @Valid @RequestBody RenameSessionRequest request) {
        return chat.renameSession(auth.requireCurrentUser().id(), sessionId, request.title());
    }

    @DeleteMapping("/chat/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable UUID sessionId) {
        chat.deleteSession(auth.requireCurrentUser().id(), sessionId);
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    public List<DatabaseStore.MessageRow> messages(@PathVariable UUID sessionId) {
        return chat.messages(auth.requireCurrentUser().id(), sessionId);
    }

    @PostMapping("/chat/sessions/{sessionId}/messages")
    public RunResponse send(@PathVariable UUID sessionId, @Valid @RequestBody SendMessageRequest request) {
        return new RunResponse(chat.send(
                auth.requireCurrentUser().id(), sessionId, request.text(), request.model()));
    }

    @GetMapping(value = "/chat/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID runId) {
        return streams.subscribe(auth.requireCurrentUser().id(), runId);
    }

    public record CreateSessionRequest(@Size(max = 180) String title) {
    }

    public record RenameSessionRequest(@NotBlank @Size(max = 180) String title) {
    }

    public record SendMessageRequest(@NotBlank @Size(max = 12000) String text,
                                     @Size(max = 120) String model) {
    }

    public record RunResponse(UUID runId) {
    }

    public record SystemStatus(boolean acpEnabled, boolean acpAvailable,
                               boolean fallbackEnabled, String acpReason, String agentCommand,
                               List<String> models, String defaultModel,
                               String database, String curriculum) {
    }
}
