package ru.questionhacker.trainer.curriculum;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurriculumService {

    private final CurriculumRepository curriculum;
    private final ObjectMapper json;

    public CurriculumService(CurriculumRepository curriculum, ObjectMapper json) {
        this.curriculum = curriculum;
        this.json = json;
    }

    public List<CategorySummary> categories() {
        return curriculum.listCategories().stream().map(this::summary).toList();
    }

    public CategoryDetail category(String rawCode) {
        String code = rawCode.toUpperCase(Locale.ROOT);
        var row = curriculum.findCategory(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Категория не найдена"));
        List<TheorySection> sections = curriculum.listTheorySections(code).stream()
                .map(item -> new TheorySection(
                        item.key(), item.title(), item.content(), item.evidenceGrade(),
                        item.sourceKey() == null ? null : new EvidenceSource(
                                item.sourceKey(), item.sourceTitle(), item.sourceUrl(),
                                item.sourceSupports(), item.sourceGrade())))
                .toList();
        List<CategoryContrast> contrasts = curriculum.listContrasts(code).stream()
                .map(item -> new CategoryContrast(item.otherCategory(), item.otherName(), item.text()))
                .toList();
        Map<String, EvidenceSource> sourceByKey = curriculum.listEvidenceSources().stream()
                .map(item -> new EvidenceSource(
                        item.key(), item.title(), item.url(), item.supports(), item.evidenceGrade()))
                .collect(Collectors.toMap(EvidenceSource::key, Function.identity()));
        List<HistoricalCase> cases = historicalCases(row.historicalCasesJson()).stream()
                .map(item -> new HistoricalCase(
                        item.slug(), item.title(), item.actor(), item.period(),
                        item.originalFrame(), item.frameShift(), item.action(), item.outcome(),
                        item.whyItFits(), item.limitations(), item.classification(),
                        item.sourceIds().stream()
                                .map(sourceKey -> source(sourceByKey, sourceKey))
                                .toList()))
                .toList();
        return new CategoryDetail(
                row.code(), row.number(), row.name(), row.nickname(), row.operation(),
                row.signal(), row.when(), row.definition(), row.mechanism(),
                strings(row.formulaJson()), nestedStrings(row.examplesJson()),
                object(row.workedExampleJson(), WorkedExample.class, "worked example"),
                questionTemplates(row.questionTemplatesJson()), row.quickExercise(), row.experiment(), cases,
                row.mistake(), row.cue(), strings(row.strengthAnchorsJson()),
                sections, contrasts);
    }

    private CategorySummary summary(CurriculumRepository.CategoryRow row) {
        return new CategorySummary(row.code(), row.number(), row.name(), row.nickname(),
                row.operation(), row.signal(), row.when());
    }

    private List<String> strings(String value) {
        return parse(value, new TypeReference<>() { }, "curriculum strings");
    }

    private List<List<String>> nestedStrings(String value) {
        return parse(value, new TypeReference<>() { }, "curriculum examples");
    }

    private List<QuestionTemplate> questionTemplates(String value) {
        return parse(value, new TypeReference<>() { }, "question templates");
    }

    private List<HistoricalCaseData> historicalCases(String value) {
        return parse(value, new TypeReference<>() { }, "historical cases");
    }

    private <T> T parse(String value, TypeReference<T> type, String description) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid imported " + description + " JSON", error);
        }
    }

    private <T> T object(String value, Class<T> type, String description) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid imported " + description + " JSON", error);
        }
    }

    private EvidenceSource source(Map<String, EvidenceSource> sources, String key) {
        EvidenceSource source = sources.get(key);
        if (source == null) throw new IllegalStateException("Unknown imported evidence source: " + key);
        return source;
    }

    public record CategorySummary(
            String code, String number, String name, String nickname,
            String operation, String signal, String when) {
    }

    public record CategoryDetail(
            String code,
            String number,
            String name,
            String nickname,
            String operation,
            String signal,
            String when,
            String definition,
            String mechanism,
            List<String> formula,
            List<List<String>> examples,
            WorkedExample workedExample,
            List<QuestionTemplate> questionTemplates,
            String quickExercise,
            String experiment,
            List<HistoricalCase> cases,
            String mistake,
            String cue,
            List<String> strengthAnchors,
            List<TheorySection> sections,
            List<CategoryContrast> contrasts) {
    }

    public record TheorySection(
            String key, String title, String content, String evidenceGrade,
            EvidenceSource source) {
    }

    public record EvidenceSource(
            String key, String title, String url, String supports, String evidenceGrade) {
    }

    public record WorkedExample(
            String title,
            String situation,
            String ordinaryQuestion,
            String hackerQuestion,
            List<ReasoningStep> reasoningSteps,
            String solution,
            String whyItFits,
            WorkedExampleConfusion confusion) {
    }

    public record ReasoningStep(String label, String text) {
    }

    public record WorkedExampleConfusion(String otherCategory, String explanation) {
    }

    public record QuestionTemplate(String domain, String question) {
    }

    public record HistoricalCase(
            String slug,
            String title,
            String actor,
            String period,
            String originalFrame,
            String frameShift,
            String action,
            String outcome,
            String whyItFits,
            String limitations,
            String classification,
            List<EvidenceSource> sources) {
    }

    private record HistoricalCaseData(
            String slug,
            String title,
            String actor,
            String period,
            String context,
            String originalFrame,
            String frameShift,
            String action,
            String outcome,
            String whyItFits,
            String limitations,
            String classification,
            List<String> sourceIds) {
    }

    public record CategoryContrast(String otherCategory, String otherName, String text) {
    }
}
