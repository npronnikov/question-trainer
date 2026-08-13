package ru.questionhacker.trainer.moderation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ru.questionhacker.trainer.AcpGateway;
import ru.questionhacker.trainer.AppProperties;

@Component
public class AcpScenarioGenerationGateway implements ScenarioGenerationGateway {

    private final AcpGateway acp;
    private final AppProperties properties;
    private final ObjectMapper json;
    private final String prompt;

    public AcpScenarioGenerationGateway(AcpGateway acp, AppProperties properties, ObjectMapper json) {
        this.acp = acp;
        this.properties = properties;
        this.json = json;
        this.prompt = readPrompt();
    }

    @Override
    public List<ScenarioDraft> generate(List<String> categories, String requestedModel) {
        String model = requestedModel == null || requestedModel.isBlank()
                ? properties.acp().defaultModel() : requestedModel.strip();
        if (model != null && !model.isBlank() && !properties.acp().models().contains(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестная модель: " + model);
        }
        try {
            String rendered = prompt
                    .replace("{{count}}", Integer.toString(categories.size()))
                    .replace("{{categories}}", json.writeValueAsString(categories));
            String raw = acp.ask(rendered, model, ignored -> { });
            if (!raw.strip().startsWith("[") || !raw.strip().endsWith("]")) {
                throw new IllegalArgumentException("Generator must return one JSON array");
            }
            List<ScenarioDraft> drafts = json.readValue(raw.strip(), new TypeReference<>() { });
            if (drafts.size() != categories.size()) {
                throw new IllegalArgumentException("Generator returned wrong count");
            }
            for (int index = 0; index < categories.size(); index++) {
                String expected = categories.get(index);
                ScenarioDraft draft = drafts.get(index);
                if (draft == null || !expected.equals(draft.category())
                        || !expected.equals(draft.correctCategory())) {
                    throw new IllegalArgumentException(
                            "Generator returned wrong category at position " + index);
                }
            }
            return drafts;
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid generator JSON", error);
        }
    }

    private String readPrompt() {
        try (var input = new ClassPathResource(
                "prompts/scenario-candidates-cycled-v1.md").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load scenario candidate prompt", error);
        }
    }
}
