package ru.questionhacker.trainer;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DatabaseStore store;
    private final ChatService chat;
    private final RunStreamRegistry streams;
    private final ScenarioService scenarios;
    private final AcpGateway acp;
    private final AppProperties properties;
    private final PracticeService practice;

    public ApiController(DatabaseStore store, ChatService chat, RunStreamRegistry streams,
                         ScenarioService scenarios, AcpGateway acp, AppProperties properties,
                         PracticeService practice) {
        this.store = store;
        this.chat = chat;
        this.streams = streams;
        this.scenarios = scenarios;
        this.acp = acp;
        this.properties = properties;
        this.practice = practice;
    }

    @GetMapping("/system/status")
    public SystemStatus status() {
        return new SystemStatus(
                acp.enabled(),
                properties.acp().fallbackEnabled(),
                acp.commandDescription(),
                properties.acp().models(),
                properties.acp().defaultModel(),
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

    @DeleteMapping("/chat/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable UUID sessionId) {
        chat.deleteSession(sessionId);
    }

    @GetMapping("/chat/sessions/{sessionId}/messages")
    public List<DatabaseStore.MessageRow> messages(@PathVariable UUID sessionId) {
        return store.listMessages(sessionId);
    }

    @PostMapping("/chat/sessions/{sessionId}/messages")
    public RunResponse send(@PathVariable UUID sessionId, @Valid @RequestBody SendMessageRequest request) {
        return new RunResponse(chat.send(sessionId, request.text(), request.model()));
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
        return scenarios.generate(request.count(), request.model());
    }

    @PostMapping("/practice/scenario")
    public PracticeService.PracticeScenario practiceScenario(@Valid @RequestBody ModelRequest request) {
        return practice.newScenario(request.model());
    }

    @PostMapping("/practice/review")
    public PracticeService.PracticeReview practiceReview(@Valid @RequestBody PracticeReviewRequest request) {
        return practice.review(request.situation(), request.question(), request.idea(),
                request.previousFeedback(), request.attempt(), request.model());
    }

    public record CreateSessionRequest(@Size(max = 180) String title) {
    }

    public record SendMessageRequest(@NotBlank @Size(max = 12000) String text,
                                     @Size(max = 120) String model) {
    }

    public record GenerateScenariosRequest(@Min(1) @Max(20) int count,
                                           @Size(max = 120) String model) {
    }

    public record ModelRequest(@Size(max = 120) String model) {
    }

    public record PracticeReviewRequest(
            @NotBlank @Size(max = 1600) String situation,
            @NotBlank @Size(max = 1800) String question,
            @NotBlank @Size(max = 3000) String idea,
            @Size(max = 3000) String previousFeedback,
            @Min(1) @Max(20) int attempt,
            @Size(max = 120) String model) {
    }

    public record RunResponse(UUID runId) {
    }

    public record SystemStatus(boolean acpEnabled, boolean fallbackEnabled, String agentCommand,
                               List<String> models, String defaultModel,
                               String database, String curriculum) {
    }
}
