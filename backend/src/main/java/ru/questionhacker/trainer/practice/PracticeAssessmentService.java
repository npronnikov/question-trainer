package ru.questionhacker.trainer.practice;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

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
    private static final Set<String> FIELDS = Set.of("question", "answer", "reasoning", "solution");

    private final PracticeRepository practice;
    private final PracticeAssessmentGateway gateway;
    private final ModelAssessmentParser parser;
    private final ExecutorService executor;
    private final ObjectMapper json;
    private final PracticeEventRegistry events;
    private final Clock clock;

    @Autowired
    public PracticeAssessmentService(PracticeRepository practice,
                                     PracticeAssessmentGateway gateway,
                                     ModelAssessmentParser parser,
                                     ExecutorService executor,
                                     ObjectMapper json,
                                     PracticeEventRegistry events) {
        this(practice, gateway, parser, executor, json, events, Clock.systemUTC());
    }

    PracticeAssessmentService(PracticeRepository practice,
                              PracticeAssessmentGateway gateway,
                              ModelAssessmentParser parser,
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
        var existing = practice.findAttemptByIdempotency(ownerId, input.idempotencyKey());
        if (existing.isPresent()) return view(existing.get());
        var assignment = practice.findAssignment(ownerId, input.assignmentId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Практика не найдена"));
        var attempt = practice.createAttempt(
                ownerId, assignment, null,
                input.question().strip(), input.answer().strip(), input.reasoning().strip(),
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
        var existing = practice.findAttemptByIdempotency(ownerId, normalize(input.idempotencyKey()));
        if (existing.isPresent()) return view(existing.get());
        var parent = practice.findAttempt(ownerId, parentAttemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Попытка не найдена"));
        if (!"NEEDS_REVISION".equals(parent.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Исправлять можно только попытку со статусом NEEDS_REVISION");
        }
        Set<String> allowed = new HashSet<>(read(
                parent.fieldsToReviseJson(), new TypeReference<List<String>>() { }));
        String question = revised("question", input.question(), parent.question(), allowed);
        String answer = revised("answer", input.answer(), parent.answer(), allowed);
        String reasoning = revised("reasoning", input.reasoning(), parent.reasoning(), allowed);
        String solution = revised("solution", input.solution(), parent.solution(), allowed);
        List<String> changed = FIELDS.stream()
                .filter(field -> !fieldValue(field, parent).equals(fieldValue(
                        field, question, answer, reasoning, solution)))
                .sorted().toList();
        if (changed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "В исправлении нет изменений");
        }
        var submission = new Submission(parent.assignmentId(), question, answer, reasoning,
                solution, input.model() == null ? parent.requestedModel() : input.model(),
                input.idempotencyKey());
        validateInput(submission);
        var assignment = practice.findAssignment(ownerId, parent.assignmentId()).orElseThrow();
        var attempt = practice.createAttempt(
                ownerId, assignment, parent.id(), question, answer, reasoning, solution,
                write(changed), normalize(submission.model()), normalize(input.idempotencyKey()),
                OffsetDateTime.now(clock));
        practice.deleteDraft(ownerId, assignment.id());
        scheduleEvaluation(attempt.id());
        return view(attempt);
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
                    attempt.question(), attempt.answer(), attempt.reasoning(), attempt.solution()),
                    attempt.requestedModel());
            ModelAssessment assessment = parser.parse(result.json(), attempt.categoryCode());
            String status = verdict(assessment);
            saveVerified(attempt, assessment, status, result.modelId(), elapsedMillis(started));
        } catch (Exception error) {
            log.warn("Practice assessment {} became unverified: {}",
                    attemptId, error.getClass().getSimpleName());
            saveUnverified(attempt, error, elapsedMillis(started));
        }
    }

    private String verdict(ModelAssessment assessment) {
        return "PASS".equals(assessment.completeness().status())
                && assessment.categoryFit().score() >= 2
                && assessment.questionStrength().score() >= 3
                && !"LOW".equals(assessment.confidence())
                ? "PASSED" : "NEEDS_REVISION";
    }

    private void saveVerified(PracticeRepository.AttemptRow attempt,
                              ModelAssessment value, String status,
                              String modelId, long latency) throws JsonProcessingException {
        var correction = value.priorityCorrection();
        var row = new PracticeRepository.AssessmentRow(
                UUID.randomUUID(), attempt.id(), "VERIFIED", value.completeness().status(),
                json.writeValueAsString(value.completeness().steps()),
                value.categoryFit().score(), value.categoryFit().evidence(),
                value.categoryFit().confusedWith(), value.questionStrength().score(),
                json.writeValueAsString(value.questionStrength().dimensions()),
                value.confidence(), json.writeValueAsString(value.strengths()),
                correction.what(), correction.why(), correction.example(),
                json.writeValueAsString(value.fieldsToRevise()), value.feedback(),
                modelId, latency, null, OffsetDateTime.now(clock));
        publishIfSaved(row, status);
    }

    private void saveUnverified(PracticeRepository.AttemptRow attempt, Exception error, long latency) {
        String reason = error instanceof IllegalArgumentException ? "INVALID_MODEL_RESPONSE" : "MODEL_UNAVAILABLE";
        var steps = List.of(
                new ModelAssessment.StepResult("question", "PASS", "Поле принято сервером."),
                new ModelAssessment.StepResult("answer", "PASS", "Поле принято сервером."),
                new ModelAssessment.StepResult("reasoning", "PASS", "Поле принято сервером."),
                new ModelAssessment.StepResult("solution", "PASS", "Поле принято сервером."));
        var row = new PracticeRepository.AssessmentRow(
                UUID.randomUUID(), attempt.id(), "UNVERIFIED", "PASS", write(steps),
                null, null, null, null, "[]", null, "[]",
                "Повторить семантическую проверку", "Модель не вернула проверяемый результат",
                "Отправьте ту же попытку на повторную проверку позднее.",
                "[]", "Техническая семантическая оценка недоступна; сервер не присваивал баллы и не ставил зачёт.",
                null, latency, reason, OffsetDateTime.now(clock));
        publishIfSaved(row, "UNVERIFIED");
    }

    private void publishIfSaved(PracticeRepository.AssessmentRow row, String status) {
        if (practice.saveCompletion(row, status, OffsetDateTime.now(clock))) {
            practice.findAttemptBySystem(row.attemptId()).map(this::view).ifPresent(events::publish);
        }
    }

    AttemptView view(PracticeRepository.AttemptRow row) {
        AssessmentView assessment = row.outcome() == null ? null : new AssessmentView(
                row.outcome(), row.completenessStatus(), read(row.stepResultsJson(), new TypeReference<>() { }),
                row.categoryFitScore(), row.categoryFitEvidence(), row.confusedWith(),
                row.questionStrengthScore(), read(row.strengthDimensionsJson(), new TypeReference<>() { }),
                row.confidence(), read(row.strengthsJson(), new TypeReference<>() { }),
                new Correction(row.correctionWhat(), row.correctionWhy(), row.correctionExample()),
                read(row.fieldsToReviseJson(), new TypeReference<>() { }),
                row.feedback(), row.modelId(), row.failureReason());
        return new AttemptView(
                row.id(), row.assignmentId(), row.parentAttemptId(), row.attemptNumber(),
                row.status(), row.question(), row.answer(), row.reasoning(), row.solution(),
                new Category(row.categoryCode(), row.categoryName()), assessment,
                row.createdAt(), row.completedAt());
    }

    private void validateInput(Submission value) {
        requireLength(value.question(), 30, "question");
        requireLength(value.answer(), 40, "answer");
        requireLength(value.reasoning(), 50, "reasoning");
        requireLength(value.solution(), 35, "solution");
        Set<String> normalized = new HashSet<>();
        for (String item : List.of(value.question(), value.answer(), value.reasoning(), value.solution())) {
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

    private String fieldValue(String field, PracticeRepository.AttemptRow row) {
        return switch (field) {
            case "question" -> row.question();
            case "answer" -> row.answer();
            case "reasoning" -> row.reasoning();
            case "solution" -> row.solution();
            default -> throw new IllegalArgumentException(field);
        };
    }

    private String fieldValue(String field, String question, String answer,
                              String reasoning, String solution) {
        return switch (field) {
            case "question" -> question;
            case "answer" -> answer;
            case "reasoning" -> reasoning;
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
            String answer,
            String reasoning,
            String solution,
            String model,
            String idempotencyKey) {
    }

    public record Revision(
            String question,
            String answer,
            String reasoning,
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
            String answer,
            String reasoning,
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
            List<ModelAssessment.StepResult> steps,
            Integer categoryFitScore,
            String categoryFitEvidence,
            String confusedWith,
            Integer questionStrengthScore,
            List<ModelAssessment.StrengthDimension> strengthDimensions,
            String confidence,
            List<String> strengths,
            Correction priorityCorrection,
            List<String> fieldsToRevise,
            String feedback,
            String modelId,
            String failureReason) {
    }

    public record Correction(String what, String why, String example) {
    }
}
