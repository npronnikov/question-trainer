package ru.questionhacker.trainer.practice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PracticeCycleService {

    private static final List<String> ALL_FIELDS = List.of("question", "rationale", "solution");

    private final PracticeRepository practice;
    private final PracticeAssignmentService assignments;
    private final PracticeAssessmentService assessments;

    public PracticeCycleService(PracticeRepository practice,
                                PracticeAssignmentService assignments,
                                PracticeAssessmentService assessments) {
        this.practice = practice;
        this.assignments = assignments;
        this.assessments = assessments;
    }

    @Transactional(readOnly = true)
    public List<CycleSummary> list(UUID ownerId) {
        return practice.listCycles(ownerId).stream().map(row -> new CycleSummary(
                row.assignmentId(), new Category(row.categoryCode(), row.categoryName()),
                row.domain(), row.situation(), row.sequenceNumber(), row.cycleNumber(),
                row.cyclePosition(), row.status(), row.attemptCount(),
                row.createdAt(), row.updatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public CycleView get(UUID ownerId, UUID assignmentId) {
        var assignment = requireAssignment(ownerId, assignmentId);
        List<PracticeAssessmentService.AttemptView> attempts = practice
                .listAttempts(ownerId, assignmentId).stream().map(assessments::view).toList();
        DraftView draft = practice.findDraft(ownerId, assignmentId).map(this::draftView).orElse(null);
        return new CycleView(assignments.view(assignment), attempts, draft, editor(attempts, draft));
    }

    @Transactional
    public DraftView saveDraft(UUID ownerId, UUID assignmentId, DraftInput input) {
        practice.lockOwner(ownerId);
        requireAssignment(ownerId, assignmentId);
        List<PracticeAssessmentService.AttemptView> attempts = practice
                .listAttempts(ownerId, assignmentId).stream().map(assessments::view).toList();
        validateBase(input, attempts);
        return draftView(practice.saveDraft(
                ownerId, assignmentId, input.baseAttemptId(), input.question(), input.rationale(),
                input.solution(), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Transactional(readOnly = true)
    public ExampleView randomExample() {
        var row = practice.randomExample().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Нет опубликованного примера практики"));
        return new ExampleView(row.id(), new Category(row.categoryCode(), row.categoryName()),
                row.domain(), row.situation(), row.question(), row.rationale(),
                row.solution(), row.recommendation());
    }

    private PracticeRepository.AssignmentRow requireAssignment(UUID ownerId, UUID assignmentId) {
        return practice.findAssignment(ownerId, assignmentId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Практика не найдена"));
    }

    private void validateBase(DraftInput input,
                              List<PracticeAssessmentService.AttemptView> attempts) {
        if (attempts.isEmpty()) {
            if (input.baseAttemptId() != null) conflict("У нового цикла нет базовой попытки");
            return;
        }
        var latest = attempts.getLast();
        if (!latest.attemptId().equals(input.baseAttemptId())
                || !("NEEDS_REVISION".equals(latest.status())
                || "UNVERIFIED".equals(latest.status()))) {
            conflict("Черновик должен продолжать последнюю редактируемую попытку");
        }
        Set<String> editable = Set.copyOf(editableFields(attempts));
        if ((!editable.contains("question") && !latest.question().equals(input.question()))
                || (!editable.contains("rationale") && !latest.rationale().equals(input.rationale()))
                || (!editable.contains("solution") && !latest.solution().equals(input.solution()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Черновик изменяет поле, не отмеченное для исправления");
        }
    }

    private void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private EditorView editor(List<PracticeAssessmentService.AttemptView> attempts, DraftView draft) {
        if (draft != null) {
            List<String> fields = draft.baseAttemptId() == null
                    ? ALL_FIELDS : latestEditableFields(attempts, draft.baseAttemptId());
            return new EditorView(draft.baseAttemptId(), draft.question(), draft.rationale(),
                    draft.solution(), fields);
        }
        if (attempts.isEmpty()) return new EditorView(null, "", "", "", ALL_FIELDS);
        var latest = attempts.getLast();
        List<String> fields = editableFields(attempts);
        return new EditorView(latest.attemptId(), latest.question(), latest.rationale(),
                latest.solution(), fields);
    }

    private List<String> latestEditableFields(
            List<PracticeAssessmentService.AttemptView> attempts, UUID baseAttemptId) {
        if (attempts.isEmpty()) return ALL_FIELDS;
        var latest = attempts.getLast();
        return latest.attemptId().equals(baseAttemptId)
                ? editableFields(attempts) : List.of();
    }

    private List<String> editableFields(
            List<PracticeAssessmentService.AttemptView> attempts) {
        return assessments.editableFields(attempts);
    }

    private DraftView draftView(PracticeRepository.DraftRow row) {
        return new DraftView(row.baseAttemptId(), row.question(), row.rationale(),
                row.solution(), row.updatedAt());
    }

    public record DraftInput(
            UUID baseAttemptId, String question, String rationale, String solution) {
    }

    public record CycleSummary(
            UUID assignmentId, Category targetCategory, String domain, String situation,
            long sequenceNumber, int cycleNumber, int cyclePosition,
            String status, int attemptCount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record CycleView(
            PracticeAssignmentService.AssignmentView assignment,
            List<PracticeAssessmentService.AttemptView> attempts,
            DraftView draft,
            EditorView editor) {
    }

    public record DraftView(
            UUID baseAttemptId, String question, String rationale,
            String solution, OffsetDateTime updatedAt) {
    }

    public record EditorView(
            UUID baseAttemptId, String question, String rationale,
            String solution, List<String> editableFields) {
    }

    public record ExampleView(
            UUID exampleId, Category targetCategory, String domain, String situation,
            String question, String rationale, String solution,
            String recommendation) {
    }

    public record Category(String code, String name) {
    }
}
