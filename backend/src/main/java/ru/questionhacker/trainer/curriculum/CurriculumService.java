package ru.questionhacker.trainer.curriculum;

import java.util.List;
import java.util.Locale;

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
        return new CategoryDetail(
                row.code(), row.number(), row.name(), row.nickname(), row.operation(),
                row.signal(), row.when(), row.definition(), row.mechanism(),
                strings(row.formulaJson()), nestedStrings(row.examplesJson()),
                row.mistake(), row.cue(), strings(row.strengthAnchorsJson()),
                sections, contrasts);
    }

    private CategorySummary summary(CurriculumRepository.CategoryRow row) {
        return new CategorySummary(row.code(), row.number(), row.name(), row.nickname(),
                row.operation(), row.signal(), row.when());
    }

    private List<String> strings(String value) {
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid imported curriculum JSON", error);
        }
    }

    private List<List<String>> nestedStrings(String value) {
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid imported curriculum examples", error);
        }
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

    public record CategoryContrast(String otherCategory, String otherName, String text) {
    }
}
