package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AuthenticateRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import static com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import static com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileResponse;
import static com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import static com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileResponse;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class AcpGateway {

    private final AppProperties properties;
    private final WorkspaceAccess workspace;
    private final AcpAvailability availability;
    private final AcpInteractionLogger interactionLogger;

    public AcpGateway(AppProperties properties, WorkspaceAccess workspace,
                      AcpAvailability availability, AcpInteractionLogger interactionLogger) {
        this.properties = properties;
        this.workspace = workspace;
        this.availability = availability;
        this.interactionLogger = interactionLogger;
    }

    public String ask(String prompt, String model, Consumer<String> onChunk) {
        if (!properties.acp().enabled()) {
            throw new IllegalStateException("ACP отключён настройкой ACP_ENABLED=false");
        }

        var builder = AgentParameters.builder(properties.acp().command())
                .args(properties.acp().args());
        for (String name : properties.acp().forwardEnv()) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                builder.addEnvVar(name, value);
            }
        }

        var responseCollector = new AcpResponseCollector(onChunk);
        var capabilities = new ClientCapabilities(new FileSystemCapability(true, true), false);
        addEnvIfPresent(builder, "NO_BROWSER");
        builder.addEnvVar("INITIAL_AGENT_MODE", System.getenv().getOrDefault("INITIAL_AGENT_MODE", "read-only"));
        var transport = new StdioAcpClientTransport(builder.build());
        var interaction = interactionLogger.begin(prompt, model);

        AcpAsyncClient client = AcpClient.async(transport)
                .requestTimeout(properties.acp().timeout())
                .clientCapabilities(capabilities)
                .readTextFileHandler(request -> Mono.fromSupplier(
                        () -> new ReadTextFileResponse(workspace.read(request.path()))))
                .writeTextFileHandler(request -> Mono.fromSupplier(() -> {
                    workspace.write(request.path(), request.content());
                    return new WriteTextFileResponse();
                }))
                .sessionUpdateConsumer(notification -> Mono.fromRunnable(
                        () -> responseCollector.accept(notification)))
                .build();
        try {
            var initialization = Objects.requireNonNull(
                    client.initialize(new InitializeRequest(1, capabilities)).block(),
                    "ACP-агент не вернул результат инициализации");
            if (hasApiKey() && initialization.authMethods() != null && !initialization.authMethods().isEmpty()) {
                client.authenticate(new AuthenticateRequest("api-key")).block();
            }
            var session = Objects.requireNonNull(
                    client.newSession(new NewSessionRequest(workspace.root().toString(), List.of())).block(),
                    "ACP-агент не создал сессию");
            if (model != null && !model.isBlank()) {
                client.setSessionModel(new SetSessionModelRequest(session.sessionId(), model)).block();
            }
            client.prompt(new PromptRequest(session.sessionId(), List.of(new TextContent(prompt)))).block();
        } catch (RuntimeException error) {
            interactionLogger.failure(interaction, error);
            availability.recordFailure(error);
            throw error;
        } finally {
            close(client);
        }

        if (responseCollector.isEmpty()) {
            var error = new IllegalStateException("ACP-агент завершил ход без текстового ответа");
            interactionLogger.failure(interaction, error);
            availability.recordFailure(error);
            throw error;
        }
        String response = responseCollector.text();
        interactionLogger.success(interaction, response);
        availability.recordSuccess();
        return response;
    }

    private boolean hasApiKey() {
        return present("CODEX_API_KEY") || present("OPENAI_API_KEY");
    }

    private boolean present(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private void addEnvIfPresent(AgentParameters.Builder builder, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            builder.addEnvVar(name, value);
        }
    }

    private void close(AcpAsyncClient client) {
        try {
            client.closeGracefully().block(Duration.ofSeconds(10));
        } finally {
            client.close();
        }
    }

    public boolean enabled() {
        return properties.acp().enabled();
    }

    public boolean available() {
        return availability.available();
    }

    public String unavailabilityReason() {
        return availability.reason();
    }

    public String commandDescription() {
        return properties.acp().command() + " " + String.join(" ", properties.acp().args());
    }
}
