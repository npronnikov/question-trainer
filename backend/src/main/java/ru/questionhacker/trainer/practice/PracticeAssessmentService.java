package ru.questionhacker.trainer.practice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class PracticeAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(PracticeAssessmentService.class);
    private static final List<String> ALL_FIELDS = List.of("question", "rationale", "solution");
    private static final Set<String> FIELDS = Set.copyOf(ALL_FIELDS);

    private final PracticeRepository practice;
    private final PracticeAssessmentGateway gateway;
    private final ModelAssessmentV3Parser parser;
    private final ExecutorService executor;
    private final ObjectMapper json;
    private final PracticeEventRegistry events;
    private final Clock clock;

    @Autowired
    public PracticeAssessmentService(PracticeRepository practice,
                                     PracticeAssessmentGateway gateway,
                                     ModelAssessmentV3Parser parser,
                                     ExecutorService executor,
                                     ObjectMapper json,
                                     PracticeEventRegistry events) {
        this(practice, gateway, parser, executor, json, events, Clock.systemUTC());
    }

    PracticeAssessmentService(PracticeRepository practice,
                              PracticeAssessmentGateway gateway,
                              ModelAssessmentV3Parser parser,
                              ExecutorService executor,
                              ObjectMapper json,
                              PracticeEventRegistry events,
                              Clock clock) {
        this.practice = practice;
        this.gateway = gateway;
        this.parser = parser;
        this.executor = executor;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public AttemptView submit(UUID ownerId, Submission input) {
        validateInput(input);
        practice.lockOwner(ownerId);
        var existing = practice.findAttemptByIdempotency(ownerId, input.idempotencyKey());
        if (existing.isPresent()) return view(existing.get());
        var assignment = practice.findAssignment(ownerId, input.assignmentId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Практика не найдена"));
        var attempt = practice.createAttempt(
                ownerId, assignment, null,
                input.question().strip(), input.rationale().strip(),
                input.solution().strip(), "[]", normalize(input.model()),
                normalize(input.idempotencyKey()), OffsetDateTime.now(clock));
        practice.deleteDraft(ownerId, assignment.id());
        scheduleEvaluation(attempt.id());
        return view(attempt);
    }

    public AttemptView get(UUID ownerId, UUID attemptId) {
        return practice.findAttempt(ownerId, attemptId)
                .map(this::view)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Попытка не найдена"));
    }

    @Transactional
    public AttemptView revise(UUID ownerId, UUID parentAttemptId, Revision input) {
        practice.lockOwner(ownerId);
        String key = normalize(input.idempotencyKey());
        var parent = practice.findAttempt(ownerId, parentAttemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Попытка не найдена"));
        var existing = practice.findAttemptByIdempotency(ownerId, key);
        if (existing.isPresent()) {
            if (!parentAttemptId.equals(existing.get().parentAttemptId())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Ключ повторной отправки относится к другой попытке");
            }
            requireMatchingIdempotentPayload(existing.get(), parent, input.question(),
                    input.rationale(), input.solution(), input.model());
            return view(existing.get());
        }
        if (!"NEEDS_REVISION".equals(parent.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Исправлять можно только попытку со статусом NEEDS_REVISION");
        }
        List<AttemptView> attempts = practice.listAttempts(ownerId, parent.assignmentId())
                .stream().map(this::view).toList();
        if (!attempts.getLast().attemptId().equals(parent.id())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Исправлять можно только последнюю попытку");
        }
        Set<String> allowed = new HashSet<>(revisionFields(parent));
        String question = revised("question", input.question(), parent.question(), allowed);
        String rationale = revised("rationale", input.rationale(), parent.rationale(), allowed);
        String solution = revised("solution", input.solution(), parent.solution(), allowed);
        List<String> changed = FIELDS.stream()
                .filter(field -> !fieldValue(field, parent).equals(fieldValue(
                        field, question, rationale, solution)))
                .sorted().toList();
        if (changed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "В исправлении нет изменений");
        }
        var submission = new Submission(parent.assignmentId(), question, rationale,
                solution, input.model() == null ? parent.requestedModel() : input.model(),
                key);
        validateInput(submission);
        var assignment = practice.findAssignment(ownerId, parent.assignmentId()).orElseThrow();
        var attempt = practice.createAttempt(
                ownerId, assignment, parent.id(), question, rationale, solution,
                write(changed), normalize(submission.model()), key,
                OffsetDateTime.now(clock));
        practice.deleteDraft(ownerId, assignment.id());
        scheduleEvaluation(attempt.id());
        return view(attempt);
    }

    @Transactional
    public AttemptView retry(UUID ownerId, UUID parentAttemptId, Retry input) {
        practice.lockOwner(ownerId);
        String key = normalize(input.idempotencyKey());
        var parent = practice.findAttempt(ownerId, parentAttemptId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Попытка не найдена"));
        var existing = practice.findAttemptByIdempotency(ownerId, key);
        if (existing.isPresent()) {
            if (!parentAttemptId.equals(existing.get().parentAttemptId())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Ключ повторной отправки относится к другой попытке");
            }
            requireMatchingIdempotentPayload(existing.get(), parent, input.question(),
                    input.rationale(), input.solution(), input.model());
            return view(existing.get());
        }
        if (!"UNVERIFIED".equals(parent.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Повторять можно только непроверенную попытку");
        }
        List<AttemptView> attempts = practice.listAttempts(ownerId, parent.assignmentId())
                .stream().map(this::view).toList();
        if (!attempts.getLast().attemptId().equals(parent.id())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Повторять можно только последнюю попытку");
        }
        Set<String> allowed = Set.copyOf(editableFields(attempts));
        String question = retried("question", input.question(), parent.question(), allowed);
        String rationale = retried("rationale", input.rationale(), parent.rationale(), allowed);
        String solution = retried("solution", input.solution(), parent.solution(), allowed);
        var submission = new Submission(parent.assignmentId(), question, rationale,
                solution, input.model() == null ? parent.requestedModel() : input.model(), key);
        validateInput(submission);
        List<String> changed = FIELDS.stream()
                .filter(field -> !fieldValue(field, parent).equals(fieldValue(
                        field, question, rationale, solution)))
                .sorted().toList();
        var assignment = practice.findAssignment(ownerId, parent.assignmentId()).orElseThrow();
        var attempt = practice.createAttempt(
                ownerId, assignment, parent.id(), question, rationale, solution,
                write(changed), normalize(submission.model()), key, OffsetDateTime.now(clock));
        practice.deleteDraft(ownerId, assignment.id());
        scheduleEvaluation(attempt.id());
        return view(attempt);
    }

    List<String> editableFields(List<AttemptView> attempts) {
        if (attempts.isEmpty()) return ALL_FIELDS;
        AttemptView latest = attempts.getLast();
        if ("NEEDS_REVISION".equals(latest.status())) {
            return List.copyOf(latest.assessment().fieldsToRevise());
        }
        if (!"UNVERIFIED".equals(latest.status())) return List.of();
        Map<UUID, AttemptView> byId = attempts.stream().collect(Collectors.toMap(
                AttemptView::attemptId, Function.identity()));
        AttemptView cursor = latest;
        while (cursor.parentAttemptId() != null) {
            cursor = byId.get(cursor.parentAttemptId());
            if (cursor == null) conflictRetryScope();
            if ("NEEDS_REVISION".equals(cursor.status())) {
                return List.copyOf(cursor.assessment().fieldsToRevise());
            }
            if (!"UNVERIFIED".equals(cursor.status())) conflictRetryScope();
        }
        return ALL_FIELDS;
    }

    private void conflictRetryScope() {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Не удалось восстановить область повторной проверки");
    }

    private void scheduleEvaluation(UUID attemptId) {
        Runnable task = () -> executor.submit(() -> evaluate(attemptId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    public SseEmitter events(UUID ownerId, UUID attemptId) {
        return events.subscribe(attemptId, get(ownerId, attemptId));
    }

    private void evaluate(UUID attemptId) {
        var attempt = practice.findAttemptBySystem(attemptId).orElse(null);
        if (attempt == null || !"EVALUATING".equals(attempt.status())) return;
        long started = System.nanoTime();
        try {
            var result = gateway.assess(new PracticeAssessmentGateway.Input(
                    attempt.situation(), attempt.categoryCode(), attempt.guidance(),
                    attempt.question(), attempt.rationale(), attempt.solution()),
                    attempt.requestedModel());
            ModelAssessmentV3 assessment = parser.parse(result.json(), attempt.categoryCode());
            AssessmentDecision decision = decide(assessment);
            if ("UNVERIFIED".equals(decision.status())) {
                saveLowConfidence(attempt, result.modelId(), elapsedMillis(started));
            } else {
                saveVerified(attempt, assessment, decision, result.modelId(), elapsedMillis(started));
            }
        } catch (Exception error) {
            log.warn("Practice assessment {} became unverified: {}: {}",
                    attemptId, error.getClass().getSimpleName(), error.getMessage(), error);
            saveUnverified(attempt, error, elapsedMillis(started));
        }
    }

    static AssessmentDecision decide(ModelAssessmentV3 assessment) {
        if ("LOW".equals(assessment.confidence())) {
            return new AssessmentDecision("UNVERIFIED", List.of());
        }
        List<ModelAssessmentV2.StepResult> steps = assessment.chain().steps();
        var fields = new java.util.ArrayList<String>();
        if (!"PASS".equals(step(steps, "question").status())
                || assessment.categoryFit().score() < 2
                || assessment.questionStrength().score() < 3) {
            fields.add("question");
        }
        if ("CONTRADICTS".equals(step(steps, "rationale").status())) {
            fields.add("rationale");
        }
        if (!"PASS".equals(step(steps, "solution").status())) {
            fields.add("solution");
        }
        return fields.isEmpty()
                ? new AssessmentDecision("PASSED", List.of())
                : new AssessmentDecision("NEEDS_REVISION", List.copyOf(fields));
    }

    private static ModelAssessmentV2.StepResult step(
            List<ModelAssessmentV2.StepResult> steps, String field) {
        return steps.stream().filter(item -> field.equals(item.field())).findFirst().orElseThrow();
    }

    private void saveVerified(PracticeRepository.AttemptRow attempt,
                              ModelAssessmentV3 value, AssessmentDecision decision,
                              String modelId, long latency) throws JsonProcessingException {
        var correction = value.priorityCorrection();
        String completeness = chainComplete(value) ? "PASS" : "FAIL";
        BigDecimal overall = ideaPotentialScore(value.ideaPotential().dimensions());
        var row = new PracticeRepository.AssessmentRow(
                UUID.randomUUID(), attempt.id(), "VERIFIED", completeness,
                json.writeValueAsString(value.chain().steps()),
                value.categoryFit().score(), value.categoryFit().evidence(),
                value.categoryFit().confusedWith(), value.questionStrength().score(),
                json.writeValueAsString(value.questionStrength().dimensions()),
                value.confidence(), overall,
                json.writeValueAsString(value.ideaPotential().dimensions()),
                json.writeValueAsString(value.strengths()),
                correction.what(), correction.why(), correction.example(),
                json.writeValueAsString(decision.fieldsToRevise()), value.feedback(),
                modelId, latency, null, OffsetDateTime.now(clock));
        publishIfSaved(row, decision.status());
    }

    private BigDecimal ideaPotentialScore(List<ModelAssessmentV3.IdeaDimension> dimensions) {
        List<Integer> scores = dimensions.stream()
                .filter(dimension -> "SCORED".equals(dimension.status()))
                .map(ModelAssessmentV3.IdeaDimension::score)
                .toList();
        if (scores.size() != 4) return null;
        return BigDecimal.valueOf(scores.stream().mapToInt(Integer::intValue).sum())
                .divide(BigDecimal.valueOf(4), 2, RoundingMode.UNNECESSARY);
    }

    private boolean chainComplete(ModelAssessmentV3 value) {
        return "PASS".equals(step(value.chain().steps(), "question").status())
                && !"CONTRADICTS".equals(step(value.chain().steps(), "rationale").status())
                && "PASS".equals(step(value.chain().steps(), "solution").status());
    }

    private void saveUnverified(PracticeRepository.AttemptRow attempt, Exception error, long latency) {
        String reason = error instanceof IllegalArgumentException ? "INVALID_MODEL_RESPONSE" : "MODEL_UNAVAILABLE";
        var steps = List.of(
                new ModelAssessmentV2.StepResult("question", "PASS", "Поле принято сервером."),
                new ModelAssessmentV2.StepResult("rationale", "SUPPORTS", "Поле принято сервером."),
                new ModelAssessmentV2.StepResult("solution", "PASS", "Поле принято сервером."));
        var row = new PracticeRepository.AssessmentRow(
                UUID.randomUUID(), attempt.id(), "UNVERIFIED", "PASS", write(steps),
                null, null, null, null, "[]", null, null, null, "[]",
                "Повторить семантическую проверку", "Модель не вернула проверяемый результат",
                "Отправьте ту же попытку на повторную проверку позднее.",
                "[]", "Техническая семантическая оценка недоступна; сервер не присваивал баллы и не ставил зачёт.",
                null, latency, reason, OffsetDateTime.now(clock));
        publishIfSaved(row, "UNVERIFIED");
    }

    private void saveLowConfidence(
            PracticeRepository.AttemptRow attempt, String modelId, long latency) {
        var steps = List.of(
                new ModelAssessmentV2.StepResult("question", "PASS", "Поле принято сервером."),
                new ModelAssessmentV2.StepResult("rationale", "SUPPORTS", "Поле принято сервером."),
                new ModelAssessmentV2.StepResult("solution", "PASS", "Поле принято сервером."));
        var row = new PracticeRepository.AssessmentRow(
                UUID.randomUUID(), attempt.id(), "UNVERIFIED", "PASS", write(steps),
                null, null, null, null, "[]", null, null, null, "[]",
                "Повторить семантическую проверку", "Уверенность модели недостаточна для зачёта",
                "Повторите ту же попытку позднее или выберите другую модель.",
                "[]", "Модель вернула низкую уверенность; сервер не назначил правку и не поставил зачёт.",
                modelId, latency, "LOW_MODEL_CONFIDENCE", OffsetDateTime.now(clock));
        publishIfSaved(row, "UNVERIFIED");
    }

    private void publishIfSaved(PracticeRepository.AssessmentRow row, String status) {
        if (practice.saveCompletion(row, status, OffsetDateTime.now(clock))) {
            practice.findAttemptBySystem(row.attemptId()).map(this::view).ifPresent(events::publish);
        }
    }

    AttemptView view(PracticeRepository.AttemptRow row) {
        AssessmentView assessment = row.outcome() == null ? null : new AssessmentView(
                row.outcome(), row.completenessStatus(), assessmentSteps(row),
                row.categoryFitScore(), row.categoryFitEvidence(), row.confusedWith(),
                row.questionStrengthScore(), read(row.strengthDimensionsJson(), new TypeReference<>() { }),
                ideaPotential(row),
                row.confidence(), read(row.strengthsJson(), new TypeReference<>() { }),
                new Correction(row.correctionWhat(), row.correctionWhy(), row.correctionExample()),
                revisionFields(row),
                row.feedback(), row.modelId(), row.failureReason());
        return new AttemptView(
                row.id(), row.assignmentId(), row.parentAttemptId(), row.attemptNumber(),
                row.status(), row.question(), row.rationale(), row.solution(),
                new Category(row.categoryCode(), row.categoryName()), assessment,
                row.createdAt(), row.completedAt());
    }

    private List<ModelAssessmentV2.StepResult> assessmentSteps(PracticeRepository.AttemptRow row) {
        List<ModelAssessmentV2.StepResult> steps = read(row.stepResultsJson(), new TypeReference<>() { });
        if (!ModelAssessmentParser.SCHEMA_VERSION.equals(row.schemaVersion())) return steps;
        var question = legacyStep(steps, "question");
        var answer = legacyStep(steps, "answer");
        var reasoning = legacyStep(steps, "reasoning");
        var solution = legacyStep(steps, "solution");
        String rationaleStatus = "FAIL".equals(answer.status()) || "FAIL".equals(reasoning.status())
                ? "CONTRADICTS" : "SUPPORTS";
        return List.of(
                new ModelAssessmentV2.StepResult("question", question.status(), question.evidence()),
                new ModelAssessmentV2.StepResult("rationale", rationaleStatus,
                        answer.evidence() + " " + reasoning.evidence()),
                new ModelAssessmentV2.StepResult("solution", solution.status(), solution.evidence()));
    }

    private IdeaPotentialView ideaPotential(PracticeRepository.AttemptRow row) {
        if (!ModelAssessmentV3Parser.SCHEMA_VERSION.equals(row.schemaVersion())
                || row.ideaPotentialDimensionsJson() == null) return null;
        try {
            List<ModelAssessmentV3.IdeaDimension> dimensions = read(
                    row.ideaPotentialDimensionsJson(), new TypeReference<>() { });
            ModelAssessmentV3Parser.validateIdeaDimensions(dimensions);
            return new IdeaPotentialView(
                    dimensions, row.ideaPotentialScore(), row.ideaPotentialScore() != null);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return null;
        }
    }

    private ModelAssessmentV2.StepResult legacyStep(
            List<ModelAssessmentV2.StepResult> steps, String field) {
        return steps.stream().filter(step -> field.equals(step.field())).findFirst()
                .orElse(new ModelAssessmentV2.StepResult(field, "PASS", "Поле принято сервером."));
    }

    private List<String> revisionFields(PracticeRepository.AttemptRow row) {
        List<String> stored = read(row.fieldsToReviseJson(), new TypeReference<>() { });
        if (!ModelAssessmentParser.SCHEMA_VERSION.equals(row.schemaVersion())) return stored;
        var normalized = new java.util.ArrayList<String>();
        if (stored.contains("question")) normalized.add("question");
        if (stored.contains("answer") || stored.contains("reasoning")) normalized.add("rationale");
        if (stored.contains("solution")) normalized.add("solution");
        return List.copyOf(normalized);
    }

    private void validateInput(Submission value) {
        requireLength(value.question(), 30, "question");
        requireLength(value.rationale(), 40, "rationale");
        requireLength(value.solution(), 35, "solution");
        Set<String> normalized = new HashSet<>();
        for (String item : List.of(value.question(), value.rationale(), value.solution())) {
            if (!normalized.add(item.strip().toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Шаги практики не должны дублировать друг друга");
            }
        }
    }

    private void requireLength(String value, int minimum, String field) {
        if (value == null || value.strip().length() < minimum) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Поле " + field + " слишком короткое для содержательной проверки");
        }
    }

    private String revised(String field, String supplied, String original, Set<String> allowed) {
        if (supplied == null) return original;
        String value = supplied.strip();
        if (!allowed.contains(field) && !value.equals(original)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Поле " + field + " не отмечено для исправления");
        }
        return value;
    }

    private String retried(String field, String supplied, String original, Set<String> allowed) {
        if (supplied == null) return original;
        String value = supplied.strip();
        if (!allowed.contains(field) && !value.equals(original)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Поле " + field + " недоступно для повторной проверки");
        }
        return value;
    }

    private void requireMatchingIdempotentPayload(
            PracticeRepository.AttemptRow existing,
            PracticeRepository.AttemptRow parent,
            String question, String rationale, String solution, String model) {
        if (!Objects.equals(resolved(question, parent.question()), existing.question())
                || !Objects.equals(resolved(rationale, parent.rationale()), existing.rationale())
                || !Objects.equals(resolved(solution, parent.solution()), existing.solution())
                || !Objects.equals(resolvedModel(model, parent.requestedModel()),
                        existing.requestedModel())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ключ повторной отправки уже использован с другими данными");
        }
    }

    private String resolved(String supplied, String parentValue) {
        return supplied == null ? parentValue : supplied.strip();
    }

    private String resolvedModel(String supplied, String parentValue) {
        return supplied == null ? parentValue : normalize(supplied);
    }

    private String fieldValue(String field, PracticeRepository.AttemptRow row) {
        return switch (field) {
            case "question" -> row.question();
            case "rationale" -> row.rationale();
            case "solution" -> row.solution();
            default -> throw new IllegalArgumentException(field);
        };
    }

    private String fieldValue(String field, String question, String rationale, String solution) {
        return switch (field) {
            case "question" -> question;
            case "rationale" -> rationale;
            case "solution" -> solution;
            default -> throw new IllegalArgumentException(field);
        };
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid persisted practice JSON", error);
        }
    }

    public record Submission(
            UUID assignmentId,
            String question,
            String rationale,
            String solution,
            String model,
            String idempotencyKey) {
    }

    public record Revision(
            String question,
            String rationale,
            String solution,
            String model,
            String idempotencyKey) {
    }

    public record Retry(
            String question,
            String rationale,
            String solution,
            String model,
            String idempotencyKey) {
    }

    public record AttemptView(
            UUID attemptId,
            UUID assignmentId,
            UUID parentAttemptId,
            int attemptNumber,
            String status,
            String question,
            String rationale,
            String solution,
            Category targetCategory,
            AssessmentView assessment,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {
    }

    public record Category(String code, String name) {
    }

    public record AssessmentView(
            String outcome,
            String completeness,
            List<ModelAssessmentV2.StepResult> steps,
            Integer categoryFitScore,
            String categoryFitEvidence,
            String confusedWith,
            Integer questionStrengthScore,
            List<ModelAssessmentV2.StrengthDimension> strengthDimensions,
            IdeaPotentialView ideaPotential,
            String confidence,
            List<String> strengths,
            Correction priorityCorrection,
            List<String> fieldsToRevise,
            String feedback,
            String modelId,
            String failureReason) {
    }

    public record IdeaPotentialView(
            List<ModelAssessmentV3.IdeaDimension> dimensions,
            BigDecimal overallScore,
            boolean complete) {
    }

    public record Correction(String what, String why, String example) {
    }

    record AssessmentDecision(String status, List<String> fieldsToRevise) {
    }
}
