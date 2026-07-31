package ru.questionhacker.trainer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PracticeService {

    private static final Logger log = LoggerFactory.getLogger(PracticeService.class);

    private final PromptCatalog prompts;
    private final AcpGateway acp;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public PracticeService(PromptCatalog prompts, AcpGateway acp,
                           AppProperties properties, ObjectMapper mapper) {
        this.prompts = prompts;
        this.acp = acp;
        this.properties = properties;
        this.mapper = mapper;
    }

    public PracticeScenario newScenario(String requestedModel) {
        try {
            String raw = acp.ask(prompts.practiceScenario(), validateModel(requestedModel), ignored -> { });
            PracticeScenario result = mapper.readValue(extractObject(raw), PracticeScenario.class);
            if (result.situation() == null || result.situation().isBlank() || result.situation().length() > 1600) {
                throw new IllegalArgumentException("Некорректная учебная ситуация");
            }
            return result;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            log.warn("Practice scenario generation failed", error);
            if (!properties.acp().fallbackEnabled()) {
                throw new IllegalStateException("Не удалось создать ситуацию", error);
            }
            return fallbackScenario();
        }
    }

    public PracticeReview review(String situation, String question, String idea,
                                 String previousFeedback, int attempt, String requestedModel) {
        String prompt = prompts.practiceReview().formatted(
                situation.strip(), question.strip(), idea.strip(),
                previousFeedback == null || previousFeedback.isBlank() ? "Нет — это первая попытка." : previousFeedback.strip(),
                attempt);
        try {
            String raw = acp.ask(prompt, validateModel(requestedModel), ignored -> { });
            PracticeReview result = mapper.readValue(extractObject(raw), PracticeReview.class);
            if (!List.of("IMPROVE", "PASSED").contains(result.verdict())) {
                throw new IllegalArgumentException("Неизвестный вердикт");
            }
            return result;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            log.warn("Practice review failed", error);
            if (!properties.acp().fallbackEnabled()) {
                throw new IllegalStateException("Не удалось проверить попытку", error);
            }
            return fallbackReview(question, idea, attempt);
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

    private PracticeScenario fallbackScenario() {
        List<PracticeScenario> samples = List.of(
                new PracticeScenario("ПРОДУКТ", "Команда три месяца улучшает онбординг сервиса, но доля пользователей, завершивших первое полезное действие, не растёт. Новые подсказки увеличивают длину сценария, а причины ухода остаются неясными."),
                new PracticeScenario("КОМАНДА", "Еженедельная встреча занимает полтора часа, но решения после неё регулярно пересматриваются. Участники считают встречу обязательной и добавляют новые отчёты, чтобы повысить её полезность."),
                new PracticeScenario("ОБРАЗОВАНИЕ", "Корпоративный курс заканчивают почти все сотрудники, однако через месяц они действуют по-старому. Команда предлагает добавить ещё больше теоретических модулей и итоговый тест."),
                new PracticeScenario("ПРОЦЕСС", "Поддержка отвечает клиентам всё медленнее, хотя штат вырос. Каждый новый тип обращения порождает отдельную инструкцию и дополнительное согласование."));
        return samples.get(ThreadLocalRandom.current().nextInt(samples.size()));
    }

    private PracticeReview fallbackReview(String question, String idea, int attempt) {
        boolean concreteQuestion = question.contains("?") && question.strip().length() >= 35;
        boolean concreteIdea = idea.strip().length() >= 55;
        boolean hasMechanism = containsAny(question + " " + idea,
                "если", "без", "вместо", "результат", "правил", "провал", "раз", "ядр", "провер");
        boolean passed = concreteQuestion && concreteIdea && hasMechanism;
        if (passed) {
            return new PracticeReview("PASSED", "Смешанная", 4,
                    "Вопрос меняет исходную рамку, а идея логично использует найденный механизм и допускает проверку.",
                    "Зафиксируйте один измеримый сигнал и проверьте идею небольшим обратимым экспериментом.");
        }
        String feedback = !concreteQuestion
                ? "Сделайте вопрос конкретным и завершите его вопросительным знаком: должно быть видно, какой параметр, цель или правило вы меняете."
                : !concreteIdea
                ? "Раскройте идею до конкретного действия: кто что изменит и какой наблюдаемый результат должен появиться."
                : "Сильнее свяжите идею с движением вопроса: покажите, какой именно новый механизм появился после смены рамки.";
        return new PracticeReview("IMPROVE", "Пока не определена", 2, feedback,
                "Перепишите вопрос и идею, изменив только один указанный аспект.");
    }

    private boolean containsAny(String value, String... fragments) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String fragment : fragments) if (lower.contains(fragment)) return true;
        return false;
    }

    private String extractObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("В ответе нет JSON-объекта");
        return raw.substring(start, end + 1);
    }

    public record PracticeScenario(String domain, String situation) {
    }

    public record PracticeReview(String verdict, String category, int score,
                                 String feedback, String nextStep) {
    }
}
