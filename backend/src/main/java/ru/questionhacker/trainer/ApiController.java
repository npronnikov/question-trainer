package ru.questionhacker.trainer;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    private final DatabaseStore store;
    private final ChatService chat;
    private final RunStreamRegistry streams;
    private final ScenarioService scenarios;
    private final AcpGateway acp;
    private final AppProperties properties;

    public ApiController(DatabaseStore store, ChatService chat, RunStreamRegistry streams,
                         ScenarioService scenarios, AcpGateway acp, AppProperties properties) {
        this.store = store;
        this.chat = chat;
        this.streams = streams;
        this.scenarios = scenarios;
        this.acp = acp;
        this.properties = properties;
    }

    @GetMapping("/system/status")
    public SystemStatus status() {
        return new SystemStatus(
                acp.enabled(),
                properties.acp().fallbackEnabled(),
                acp.commandDescription(),
                "H2 file",
                "seven-category trainer");
    }

    @GetMapping("/chat/sessions")
    public List<DatabaseStore.SessionRow> sessions() {
        return store.listSessions();
    }

    @PostMapping("/chat/sessions")
    public DatabaseStore.SessionRow createSession(@RequestBody(required = false) CreateSessionRequest request) {
        return chat.createSession(request == null ? null : request.title());
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    public List<DatabaseStore.MessageRow> messages(@PathVariable UUID sessionId) {
        return store.listMessages(sessionId);
    }

    @PostMapping("/chat/sessions/{sessionId}/messages")
    public RunResponse send(@PathVariable UUID sessionId, @Valid @RequestBody SendMessageRequest request) {
        return new RunResponse(chat.send(sessionId, request.text()));
    }

    @GetMapping(value = "/chat/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID runId) {
        return streams.subscribe(runId);
    }

    @GetMapping("/scenarios/generated")
    public List<DatabaseStore.ScenarioRow> generatedScenarios() {
        return store.listScenarios();
    }

    @PostMapping("/scenarios/generate")
    public List<DatabaseStore.ScenarioRow> generate(@Valid @RequestBody GenerateScenariosRequest request) {
        return scenarios.generate(request.count());
    }

    public record CreateSessionRequest(@Size(max = 180) String title) {
    }

    public record SendMessageRequest(@NotBlank @Size(max = 12000) String text) {
    }

    public record GenerateScenariosRequest(@Min(1) @Max(20) int count) {
    }

    public record RunResponse(UUID runId) {
    }

    public record SystemStatus(boolean acpEnabled, boolean fallbackEnabled, String agentCommand,
                               String database, String curriculum) {
    }
}
