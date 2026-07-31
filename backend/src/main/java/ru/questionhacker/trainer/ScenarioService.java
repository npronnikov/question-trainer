package ru.questionhacker.trainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);
    private static final Set<String> ALLOWED = Set.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "FUTURISM",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");

    private final DatabaseStore store;
    private final PromptCatalog prompts;
    private final AcpGateway acp;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public ScenarioService(DatabaseStore store, PromptCatalog prompts, AcpGateway acp,
                           AppProperties properties, ObjectMapper mapper) {
        this.store = store;
        this.prompts = prompts;
        this.acp = acp;
        this.properties = properties;
        this.mapper = mapper;
    }

    public List<DatabaseStore.ScenarioRow> generate(int requestedCount, String requestedModel) {
        int count = Math.max(1, Math.min(20, requestedCount));
        try {
            String raw = acp.ask(prompts.scenarioGenerator()
                    + "\n\nКоличество элементов: " + count + ".",
                    validateModel(requestedModel), ignored -> { });
            List<GeneratedInput> inputs = mapper.readValue(extractArray(raw), new TypeReference<>() { });
            var saved = new ArrayList<DatabaseStore.ScenarioRow>();
            for (GeneratedInput input : inputs.stream().limit(count).toList()) {
                validate(input);
                saved.add(store.addScenario(input.situation().strip(), input.category(), input.explanation().strip()));
            }
            if (saved.isEmpty()) throw new IllegalArgumentException("Агент вернул пустой массив");
            return saved;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            log.warn("Scenario generation through ACP failed", error);
            throw new IllegalStateException("Не удалось сгенерировать ситуации через ACP", error);
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

    private void validate(GeneratedInput input) {
        if (input == null || input.situation() == null || input.explanation() == null || input.category() == null) {
            throw new IllegalArgumentException("Неполная ситуация");
        }
        if (!ALLOWED.contains(input.category())) throw new IllegalArgumentException("Неизвестная категория");
        if (input.situation().isBlank() || input.situation().length() > 1200) throw new IllegalArgumentException("Некорректная ситуация");
        if (input.explanation().isBlank() || input.explanation().length() > 1800) throw new IllegalArgumentException("Некорректное объяснение");
    }

    private String extractArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < start) throw new IllegalArgumentException("В ответе нет JSON-массива");
        return raw.substring(start, end + 1);
    }

    public record GeneratedInput(String situation, String category, String explanation) {
    }
}
