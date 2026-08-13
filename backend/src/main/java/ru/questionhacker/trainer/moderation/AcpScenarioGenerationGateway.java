package ru.questionhacker.trainer.moderation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ru.questionhacker.trainer.AcpGateway;
import ru.questionhacker.trainer.AppProperties;

@Component
public class AcpScenarioGenerationGateway implements ScenarioGenerationGateway {

    private static final Logger log = LoggerFactory.getLogger(AcpScenarioGenerationGateway.class);
    private static final String JSON_RETRY_INSTRUCTION = """

            Предыдущая попытка содержала некорректный JSON. Сгенерируй массив заново.
            В каждом объекте разрешены только перечисленные в схеме поля с парами ключ:значение.
            Не добавляй пустые члены вида ,"" и проверь, что результат разбирается стандартным JSON-парсером.
            """;
    private static final String PRACTICE_JSON_RETRY_INSTRUCTION = """

            Предыдущая попытка содержала некорректный JSON. Сгенерируй объект заново.
            Разрешены только поля domain, situation и hint с парами ключ:значение.
            Не добавляй category, готовый вопрос, ответ, рассуждение, решение или пустые члены вида ,"".
            Проверь, что результат разбирается стандартным JSON-парсером.
            """;

    private final AcpGateway acp;
    private final AppProperties properties;
    private final ObjectMapper json;
    private final String trainerPrompt;
    private final String practicePrompt;

    public AcpScenarioGenerationGateway(AcpGateway acp, AppProperties properties, ObjectMapper json) {
        this.acp = acp;
        this.properties = properties;
        this.json = json;
        this.trainerPrompt = readPrompt("prompts/scenario-candidates-cycled-v1.md");
        this.practicePrompt = readPrompt("prompts/practice-scenario-candidate-v1.md");
    }

    @Override
    public List<ScenarioDraft> generate(List<String> categories, String requestedModel) {
        String model = validatedModel(requestedModel);
        String rendered;
        try {
            rendered = trainerPrompt
                    .replace("{{count}}", Integer.toString(categories.size()))
                    .replace("{{categories}}", json.writeValueAsString(categories));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot serialize generator prompt", error);
        }

        List<ScenarioDraft> drafts = requestDrafts(rendered, model);
        if (drafts.size() != categories.size()) {
            throw upstreamFailure("ACP вернул неверное количество ситуаций", null);
        }
        for (int index = 0; index < categories.size(); index++) {
            String expected = categories.get(index);
            ScenarioDraft draft = drafts.get(index);
            if (draft == null || !expected.equals(draft.category())
                    || !expected.equals(draft.correctCategory())) {
                throw upstreamFailure("ACP нарушил заданный порядок категорий", null);
            }
        }
        return drafts;
    }

    @Override
    public PracticeScenarioDraft generatePractice(String category, String requestedModel) {
        String model = validatedModel(requestedModel);
        String rendered = practicePrompt.replace("{{category}}", category);
        return requestPracticeDraft(rendered, model);
    }

    private List<ScenarioDraft> requestDrafts(String rendered, String model) {
        String raw = acp.ask(rendered, model, ignored -> { });
        try {
            return parseWithLocalRepair(raw);
        } catch (IOException firstError) {
            log.warn("ACP scenario generator returned invalid JSON ({} chars, {}). Retrying once.",
                    raw.length(), errorLocation(firstError));
        }

        String retried = acp.ask(rendered + JSON_RETRY_INSTRUCTION, model, ignored -> { });
        try {
            return parseWithLocalRepair(retried);
        } catch (IOException secondError) {
            log.warn("ACP scenario generator returned invalid JSON after retry ({} chars, {}).",
                    retried.length(), errorLocation(secondError));
            throw upstreamFailure("ACP вернул некорректный JSON после повторной попытки", secondError);
        }
    }

    private PracticeScenarioDraft requestPracticeDraft(String rendered, String model) {
        String raw = acp.ask(rendered, model, ignored -> { });
        try {
            return parsePracticeWithLocalRepair(raw);
        } catch (IOException firstError) {
            log.warn("ACP practice generator returned invalid JSON ({} chars, {}). Retrying once.",
                    raw.length(), errorLocation(firstError));
        }

        String retried = acp.ask(rendered + PRACTICE_JSON_RETRY_INSTRUCTION, model, ignored -> { });
        try {
            return parsePracticeWithLocalRepair(retried);
        } catch (IOException secondError) {
            log.warn("ACP practice generator returned invalid JSON after retry ({} chars, {}).",
                    retried.length(), errorLocation(secondError));
            throw upstreamFailure("ACP вернул некорректный JSON практической ситуации после повторной попытки",
                    secondError);
        }
    }

    private List<ScenarioDraft> parseWithLocalRepair(String raw) throws IOException {
        try {
            return parse(raw);
        } catch (IOException originalError) {
            String repaired = removeStrayEmptyObjectMembers(raw);
            if (repaired.equals(raw)) {
                throw originalError;
            }
            log.debug("Removed stray empty object members from ACP scenario JSON before parsing.");
            return parse(repaired);
        }
    }

    private PracticeScenarioDraft parsePracticeWithLocalRepair(String raw) throws IOException {
        try {
            return parsePractice(raw);
        } catch (IOException originalError) {
            String repaired = removeStrayEmptyObjectMembers(raw);
            if (repaired.equals(raw)) {
                throw originalError;
            }
            log.debug("Removed stray empty object members from ACP practice JSON before parsing.");
            return parsePractice(repaired);
        }
    }

    private List<ScenarioDraft> parse(String raw) throws IOException {
        String stripped = raw.strip();
        if (!stripped.startsWith("[") || !stripped.endsWith("]")) {
            throw new IOException("Generator must return one JSON array");
        }
        return json.readValue(stripped, new TypeReference<>() { });
    }

    private PracticeScenarioDraft parsePractice(String raw) throws IOException {
        String stripped = raw.strip();
        if (!stripped.startsWith("{") || !stripped.endsWith("}")) {
            throw new IOException("Practice generator must return one JSON object");
        }
        JsonNode node = json.readTree(stripped);
        if (!node.isObject()) {
            throw new IOException("Practice generator must return one JSON object");
        }
        Set<String> fields = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("domain", "situation", "hint"))) {
            throw new IOException("Practice generator returned fields outside the schema");
        }
        return json.treeToValue(node, PracticeScenarioDraft.class);
    }

    private String removeStrayEmptyObjectMembers(String raw) {
        var repaired = new StringBuilder(raw.length());
        var containers = new ArrayDeque<Character>();
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (!inString && current == ','
                    && !containers.isEmpty() && containers.peek() == '{') {
                int cursor = index + 1;
                while (cursor < raw.length() && Character.isWhitespace(raw.charAt(cursor))) {
                    cursor++;
                }
                if (cursor + 1 < raw.length()
                        && raw.charAt(cursor) == '"' && raw.charAt(cursor + 1) == '"') {
                    cursor += 2;
                    while (cursor < raw.length() && Character.isWhitespace(raw.charAt(cursor))) {
                        cursor++;
                    }
                    if (cursor < raw.length()
                            && (raw.charAt(cursor) == '}' || raw.charAt(cursor) == ',')) {
                        index = cursor - 1;
                        continue;
                    }
                }
            }

            repaired.append(current);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
            } else if (current == '"') {
                inString = true;
            } else if (current == '{' || current == '[') {
                containers.push(current);
            } else if (current == '}' && !containers.isEmpty() && containers.peek() == '{') {
                containers.pop();
            } else if (current == ']' && !containers.isEmpty() && containers.peek() == '[') {
                containers.pop();
            }
        }
        return repaired.toString();
    }

    private String errorLocation(IOException error) {
        if (error instanceof JsonProcessingException processing && processing.getLocation() != null) {
            return "line " + processing.getLocation().getLineNr()
                    + ", column " + processing.getLocation().getColumnNr();
        }
        return error.getClass().getSimpleName();
    }

    private ResponseStatusException upstreamFailure(String reason, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, reason, cause);
    }

    private String validatedModel(String requestedModel) {
        String model = requestedModel == null || requestedModel.isBlank()
                ? properties.acp().defaultModel() : requestedModel.strip();
        if (model != null && !model.isBlank() && !properties.acp().models().contains(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестная модель: " + model);
        }
        return model;
    }

    private String readPrompt(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load scenario candidate prompt: " + path, error);
        }
    }
}
