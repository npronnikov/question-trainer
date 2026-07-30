package ru.questionhacker.trainer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import static com.agentclientprotocol.sdk.spec.AcpSchema.AuthenticateRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import static com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import static com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import static com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileResponse;
import static com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import static com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileResponse;

import java.util.List;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AcpGateway {

    private static final Logger log = LoggerFactory.getLogger(AcpGateway.class);

    private final AppProperties properties;
    private final WorkspaceAccess workspace;

    public AcpGateway(AppProperties properties, WorkspaceAccess workspace) {
        this.properties = properties;
        this.workspace = workspace;
    }

    public String ask(String prompt, Consumer<String> onChunk) {
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

        var chunks = new StringBuilder();
        var capabilities = new ClientCapabilities(new FileSystemCapability(true, true), false);
        addEnvIfPresent(builder, "NO_BROWSER");
        builder.addEnvVar("INITIAL_AGENT_MODE", System.getenv().getOrDefault("INITIAL_AGENT_MODE", "read-only"));
        var transport = new StdioAcpClientTransport(builder.build());

        try (AcpSyncClient client = AcpClient.sync(transport)
                .requestTimeout(properties.acp().timeout())
                .clientCapabilities(capabilities)
                .readTextFileHandler(request -> new ReadTextFileResponse(workspace.read(request.path())))
                .writeTextFileHandler(request -> {
                    workspace.write(request.path(), request.content());
                    return new WriteTextFileResponse();
                })
                .sessionUpdateConsumer(notification -> {
                    if (notification.update() instanceof AgentMessageChunk message
                            && message.content() instanceof TextContent text
                            && text.text() != null) {
                        chunks.append(text.text());
                        onChunk.accept(text.text());
                    }
                })
                .build()) {
            var initialization = client.initialize(new InitializeRequest(1, capabilities));
            if (hasApiKey() && initialization.authMethods() != null && !initialization.authMethods().isEmpty()) {
                client.authenticate(new AuthenticateRequest("api-key"));
            }
            var session = client.newSession(new NewSessionRequest(workspace.root().toString(), List.of()));
            client.prompt(new PromptRequest(session.sessionId(), List.of(new TextContent(prompt))));
        } catch (RuntimeException error) {
            log.warn("ACP agent failed: {}", error.toString());
            throw error;
        }

        if (chunks.isEmpty()) {
            throw new IllegalStateException("ACP-агент завершил ход без текстового ответа");
        }
        return chunks.toString();
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

    public boolean enabled() {
        return properties.acp().enabled();
    }

    public String commandDescription() {
        return properties.acp().command() + " " + String.join(" ", properties.acp().args());
    }
}
