package ru.questionhacker.trainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public List<DatabaseStore.ScenarioRow> generate(int requestedCount) {
        int count = Math.max(1, Math.min(20, requestedCount));
        try {
            String raw = acp.ask(prompts.scenarioGenerator()
                    + "\n\nКоличество элементов: " + count + ".", ignored -> { });
            List<GeneratedInput> inputs = mapper.readValue(extractArray(raw), new TypeReference<>() { });
            var saved = new ArrayList<DatabaseStore.ScenarioRow>();
            for (GeneratedInput input : inputs.stream().limit(count).toList()) {
                validate(input);
                saved.add(store.addScenario(input.situation().strip(), input.category(), input.explanation().strip()));
            }
            if (saved.isEmpty()) throw new IllegalArgumentException("Агент вернул пустой массив");
            return saved;
        } catch (Exception error) {
            log.warn("Scenario generation through ACP failed", error);
            if (!properties.acp().fallbackEnabled()) throw new IllegalStateException("Не удалось сгенерировать ситуации", error);
            return fallback(count);
        }
    }

    private List<DatabaseStore.ScenarioRow> fallback(int count) {
        List<GeneratedInput> samples = List.of(
                new GeneratedInput("Команда хочет повысить качество релизов. Фасилитатор спрашивает: «Что нужно делать, чтобы каждый релиз гарантированно ломал доверие пользователей?»", "INVERSION", "Цель намеренно перевёрнута в провал, чтобы затем обратить причины в меры защиты."),
                new GeneratedInput("Сервис обрабатывает 200 заявок в день. Вопрос: «Какой должна стать система, если завтра придёт 20 000 заявок, а штат останется прежним?»", "HYPERBOLE", "Параметр спроса увеличен в 100 раз, поэтому косметические улучшения уже не подходят."),
                new GeneratedInput("В библиотеке длинные очереди на выдачу книг. Команда спрашивает: «Как с потоком справляется сортировочный центр посылок и какой принцип маршрутизации мы можем перенести?»", "CROSS_DISCIPLINE", "Ищется перенос механизма решения из другой отрасли, а не копирование внешнего вида."),
                new GeneratedInput("Представьте 2031 год: обучение сотрудников занимает один день и даёт устойчивый навык. Какие три решения, принятые раньше, привели к этому?", "FUTURISM", "Сначала задано успешное будущее, затем путь восстанавливается назад методом backcasting."),
                new GeneratedInput("В отрасли принято продавать только годовые лицензии. Вопрос: «Что откроется, если мы вообще перестанем продавать лицензии?»", "PROVOCATION", "Под сомнение поставлена негласная и привычная основа бизнес-модели."),
                new GeneratedInput("Пользователи редко нажимают кнопку «Экспорт». Вместо вопроса про цвет кнопки команда спрашивает: «Какой результат человек пытается получить после экспорта?»", "REFRAMING", "Фокус перенесён с элемента интерфейса на реальную задачу пользователя."),
                new GeneratedInput("В форме регистрации 14 полей. Вопрос: «Если оставить одно действие и три обязательных данных, без чего ценность действительно разрушится?»", "SIMPLIFICATION", "Удаляются исторические детали, чтобы выделить минимально необходимое ядро."));
        var result = new ArrayList<DatabaseStore.ScenarioRow>();
        for (int i = 0; i < count; i++) {
            GeneratedInput sample = samples.get(i % samples.size());
            result.add(store.addScenario(sample.situation(), sample.category(), sample.explanation()));
        }
        return result;
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
