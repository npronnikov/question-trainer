package ru.questionhacker.trainer.moderation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ScenarioModerationService {

    private static final Set<String> CATEGORIES = Set.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");
    private static final Set<String> DIFFICULTIES = Set.of("L1", "L2", "L3");
    private static final Set<String> REJECTION_REASONS = Set.of(
            "WEAK_LEARNING_VALUE", "WRONG_CATEGORY", "DUPLICATE",
            "UNSAFE_CONTENT", "POOR_WRITING", "OTHER");

    private final ModerationRepository moderation;
    private final ScenarioGenerationGateway generator;
    private final ObjectMapper json;

    public ScenarioModerationService(ModerationRepository moderation,
                                     ScenarioGenerationGateway generator,
                                     ObjectMapper json) {
        this.moderation = moderation;
        this.generator = generator;
        this.json = json;
    }

    @Transactional
    public List<CandidateView> generate(UUID actorId, int count, String model) {
        if (count < 1 || count > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Количество должно быть от 1 до 20");
        }
        moderation.lockGenerationSequence();
        List<String> categoryOrder = moderation.categoryCodes();
        if (categoryOrder.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Категории программы не загружены");
        }
        long start = moderation.candidateCount();
        List<String> requestedCategories = IntStream.range(0, count)
                .mapToObj(index -> categoryOrder.get(
                        (int) ((start + index) % categoryOrder.size())))
                .toList();
        List<ScenarioDraft> drafts = generator.generate(requestedCategories, model);
        if (drafts == null || drafts.size() != count) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Генератор вернул неверное количество кейсов");
        }
        List<CandidateView> result = new ArrayList<>();
        List<String> existing = new ArrayList<>(moderation.existingTexts());
        for (ScenarioDraft draft : drafts) {
            Screened screened = screen(draft, existing);
            var row = row(UUID.randomUUID(), 1, screened, model);
            moderation.insert(row);
            moderation.action(action(actorId, row.id(),
                    screened.reasons().isEmpty() ? "GENERATE" : "AUTO_REJECT",
                    null, null, "GENERATING", row.status(), 0, 1, "{}", snapshot(row)));
            if (draft != null && draft.situation() != null) existing.add(draft.situation());
            result.add(view(row));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<CandidateView> list(String rawStatus) {
        String status = normalizeStatus(rawStatus);
        return moderation.list(status).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public CandidateView get(UUID id) {
        return view(requireCandidate(id));
    }

    @Transactional
    public CandidateView reject(UUID actorId, UUID id, int expectedVersion,
                                String reason, String comment) {
        if (reason == null || !REJECTION_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Требуется допустимая причина отказа");
        }
        var before = requirePending(id, expectedVersion);
        OffsetDateTime now = now();
        if (!moderation.updateStatus(id, expectedVersion, "PENDING_REVIEW", "REJECTED", null,
                write(List.of(reason)), now)) {
            throw conflict();
        }
        var after = requireCandidate(id);
        moderation.action(action(actorId, id, "REJECT", reason, normalize(comment),
                before.status(), after.status(), before.version(), after.version(),
                snapshot(before), snapshot(after)));
        return view(after);
    }

    @Transactional
    public CandidateView approve(UUID actorId, UUID id, int expectedVersion) {
        var before = requirePending(id, expectedVersion);
        OffsetDateTime now = now();
        if (!moderation.updateStatus(id, expectedVersion, "PENDING_REVIEW", "PUBLISHED", null,
                "[]", now)) {
            throw conflict();
        }
        UUID scenarioId = moderation.publishScenario(before, readOptions(before.optionsJson()), now);
        moderation.setPublishedScenario(id, scenarioId);
        var after = requireCandidate(id);
        moderation.action(action(actorId, id, "APPROVE_PUBLISH", null, null,
                before.status(), after.status(), before.version(), after.version(),
                snapshot(before), snapshot(after)));
        return view(after);
    }

    @Transactional
    public CandidateView edit(UUID actorId, UUID id, int expectedVersion, ScenarioDraft draft) {
        var before = requirePending(id, expectedVersion);
        Screened screened = screen(draft, moderation.existingTextsExcluding(id));
        var replacement = row(id, before.version() + 1, screened, before.sourceModel());
        if (!moderation.updateDraft(id, expectedVersion, replacement)) throw conflict();
        var after = requireCandidate(id);
        moderation.action(action(actorId, id, "EDIT", null, null,
                before.status(), after.status(), before.version(), after.version(),
                snapshot(before), snapshot(after)));
        return view(after);
    }

    private Screened screen(ScenarioDraft draft, List<String> existing) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (draft == null) {
            reasons.add("INVALID_SCHEMA");
            return new Screened(null, List.copyOf(reasons), List.of(), hash("invalid"));
        }
        String category = normalizeCode(draft.category());
        String secondary = normalizeCode(draft.secondaryCategory());
        String correct = normalizeCode(draft.correctCategory());
        String confused = normalizeCode(draft.confusedWith());
        String difficulty = normalizeCode(draft.difficulty());
        if (secondary != null) reasons.add("MULTIPLE_TECHNIQUES");
        if (!CATEGORIES.contains(category) || !CATEGORIES.contains(correct) || !category.equals(correct)) {
            reasons.add("UNKNOWN_OR_MISMATCHED_CATEGORY");
        }
        if (!DIFFICULTIES.contains(difficulty)) reasons.add("INVALID_DIFFICULTY");
        if (blankOrOutside(draft.domain(), 2, 120)
                || blankOrOutside(draft.situation(), 80, 1200)
                || blankOrOutside(draft.question(), 25, 1000)
                || blankOrOutside(draft.hint(), 15, 600)
                || blankOrOutside(draft.explanation(), 30, 1200)) {
            reasons.add("INVALID_LENGTH");
        }
        if (draft.question() == null || !draft.question().contains("?")) reasons.add("INVALID_QUESTION");
        List<String> options = normalizedOptions(draft.options());
        if (options.size() != 4 || new HashSet<>(options).size() != 4
                || !options.stream().allMatch(CATEGORIES::contains) || !options.contains(correct)) {
            reasons.add("INVALID_OPTIONS");
        }
        if (hintLeaks(draft.hint(), category)) reasons.add("HINT_LEAKS_ANSWER");
        if (unsafe(draft.situation()) || unsafe(draft.question()) || unsafe(draft.hint())) {
            reasons.add("UNSAFE_CONTENT");
        }
        if ("L3".equals(difficulty)
                && (!CATEGORIES.contains(confused) || confused.equals(category)
                || blankOrOutside(draft.contrast(), 20, 1200))) {
            reasons.add("INVALID_L3_CONTRAST");
        }
        if (nearDuplicate(draft.situation(), existing)) reasons.add("DUPLICATE");
        String contentHash = hash(normalizeText(String.join("|",
                safe(draft.situation()), safe(draft.question()), safe(category), safe(difficulty))));
        return new Screened(draft, List.copyOf(reasons), List.of(), contentHash);
    }

    private ModerationRepository.CandidateRow row(UUID id, int version, Screened screened, String model) {
        ScenarioDraft draft = screened.draft();
        OffsetDateTime now = now();
        String category = known(draft == null ? null : draft.category());
        String secondary = known(draft == null ? null : draft.secondaryCategory());
        String correct = known(draft == null ? null : draft.correctCategory());
        String confused = known(draft == null ? null : draft.confusedWith());
        return new ModerationRepository.CandidateRow(
                id, screened.reasons().isEmpty() ? "PENDING_REVIEW" : "AUTO_REJECTED", version,
                category, secondary, normalizeCode(draft == null ? null : draft.difficulty()),
                strip(draft == null ? null : draft.domain()), strip(draft == null ? null : draft.situation()),
                strip(draft == null ? null : draft.question()), strip(draft == null ? null : draft.hint()),
                write(normalizedOptions(draft == null ? null : draft.options())), correct,
                strip(draft == null ? null : draft.explanation()), confused,
                strip(draft == null ? null : draft.contrast()), screened.hash(), normalize(model),
                write(screened.reasons()), write(screened.warnings()), null, now, now);
    }

    private CandidateView view(ModerationRepository.CandidateRow row) {
        return new CandidateView(
                row.id(), row.status(), row.version(), row.category(), row.secondaryCategory(),
                row.difficulty(), row.domain(), row.situation(), row.question(), row.hint(),
                readOptions(row.optionsJson()), row.correctCategory(), row.explanation(),
                row.confusedWith(), row.contrast(), readStrings(row.rejectionReasonsJson()),
                readStrings(row.warningsJson()), row.sourceModel(), row.publishedScenarioId(),
                row.createdAt(), row.updatedAt());
    }

    private ModerationRepository.CandidateRow requirePending(UUID id, int expectedVersion) {
        var row = requireCandidate(id);
        if (!"PENDING_REVIEW".equals(row.status()) || row.version() != expectedVersion) throw conflict();
        return row;
    }

    private ModerationRepository.CandidateRow requireCandidate(UUID id) {
        return moderation.find(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Кандидат не найден"));
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Кандидат уже изменён другим модератором; обновите очередь");
    }

    private ModerationRepository.ActionRow action(
            UUID actor, UUID candidate, String type, String reason, String comment,
            String previousStatus, String newStatus, int previousVersion, int newVersion,
            String before, String after) {
        return new ModerationRepository.ActionRow(
                UUID.randomUUID(), candidate, actor, type, reason, comment,
                previousStatus, newStatus, previousVersion, newVersion, before, after, now());
    }

    private boolean hintLeaks(String hint, String category) {
        if (hint == null || category == null) return false;
        String value = normalizeText(hint);
        String stem = switch (category) {
            case "INVERSION" -> "инверс";
            case "HYPERBOLE" -> "гипербол";
            case "CROSS_DISCIPLINE" -> "кросс дисцип";
            case "BACKCASTING" -> "backcasting";
            case "PROVOCATION" -> "провокац";
            case "REFRAMING" -> "рефрейм";
            case "SIMPLIFICATION" -> "упрощ";
            default -> category.toLowerCase(Locale.ROOT);
        };
        return value.contains(category.toLowerCase(Locale.ROOT))
                || value.contains(stem) || value.contains("правильн");
    }

    private boolean unsafe(String value) {
        if (value == null) return false;
        String text = normalizeText(value);
        return List.of("убить", "причинить вред", "ненавист", "дискриминац", "самоубий")
                .stream().anyMatch(text::contains);
    }

    private boolean nearDuplicate(String value, List<String> existing) {
        if (value == null) return false;
        Set<String> source = tokens(value);
        if (source.size() < 6) return false;
        for (String other : existing) {
            Set<String> target = tokens(other);
            Set<String> union = new HashSet<>(source);
            union.addAll(target);
            Set<String> intersection = new HashSet<>(source);
            intersection.retainAll(target);
            if (!union.isEmpty() && intersection.size() / (double) union.size() >= 0.82) return true;
        }
        return false;
    }

    private Set<String> tokens(String value) {
        return new HashSet<>(List.of(normalizeText(value).split("[^а-яa-z0-9]+")));
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String status = value.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING_REVIEW", "AUTO_REJECTED", "REJECTED", "PUBLISHED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный статус очереди");
        }
        return status;
    }

    private List<String> normalizedOptions(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(this::normalizeCode).toList();
    }

    private String known(String value) {
        String code = normalizeCode(value);
        return code != null && CATEGORIES.contains(code) ? code : null;
    }

    private String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private boolean blankOrOutside(String value, int min, int max) {
        return value == null || value.strip().length() < min || value.strip().length() > max;
    }

    private String normalizeText(String value) {
        return safe(value).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String strip(String value) {
        return value == null ? null : value.strip();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private String snapshot(ModerationRepository.CandidateRow row) {
        return write(view(row));
    }

    private List<String> readOptions(String value) {
        return readStrings(value);
    }

    private List<String> readStrings(String value) {
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Screened(ScenarioDraft draft, List<String> reasons,
                            List<String> warnings, String hash) {
    }

    public record CandidateView(
            UUID id, String status, int version, String category, String secondaryCategory,
            String difficulty, String domain, String situation, String question, String hint,
            List<String> options, String correctCategory, String explanation,
            String confusedWith, String contrast, List<String> rejectionReasons,
            List<String> warnings, String sourceModel, UUID publishedScenarioId,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
