package ru.questionhacker.trainer.practice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PracticeAssignmentService {

    static final List<String> CANONICAL_CATEGORIES = List.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");

    private final PracticeRepository practice;

    public PracticeAssignmentService(PracticeRepository practice) {
        this.practice = practice;
    }

    @Transactional
    public AssignmentView create(UUID ownerId) {
        practice.lockOwner(ownerId);
        List<String> categories = practice.categoryCodes();
        if (!CANONICAL_CATEGORIES.equals(categories)) {
            throw new IllegalStateException("Practice categories do not match the canonical cycle");
        }
        long sequenceNumber = practice.nextAssignmentSequence(ownerId);
        int cyclePosition = Math.toIntExact(((sequenceNumber - 1) % 7) + 1);
        int cycleNumber = Math.toIntExact(((sequenceNumber - 1) / 7) + 1);
        String category = CANONICAL_CATEGORIES.get(cyclePosition - 1);
        var source = practice.selectAssignmentSource(ownerId, category)
                .orElseThrow(PracticeAssignmentUnavailableException::exhausted);
        String guidance = source.operation() + " Контрольный ориентир: " + source.cue();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var assignment = practice.createAssignment(
                ownerId, source, guidance, sequenceNumber, cycleNumber, cyclePosition, now);
        practice.createEmptyDraft(ownerId, assignment.id(), now);
        return view(assignment);
    }

    @Transactional(readOnly = true)
    public AssignmentView get(UUID ownerId, UUID assignmentId) {
        return practice.findAssignment(ownerId, assignmentId)
                .map(this::view)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Практика не найдена"));
    }

    AssignmentView view(PracticeRepository.AssignmentRow row) {
        return new AssignmentView(
                row.id(), row.domain(), row.situation(), row.hint(),
                new TargetCategory(row.categoryCode(), row.categoryName(), row.guidance()),
                row.sequenceNumber(), row.cycleNumber(), row.cyclePosition(),
                row.createdAt());
    }

    public record AssignmentView(
            UUID assignmentId,
            String domain,
            String situation,
            String hint,
            TargetCategory targetCategory,
            long sequenceNumber,
            int cycleNumber,
            int cyclePosition,
            OffsetDateTime createdAt) {
    }

    public record TargetCategory(String code, String name, String guidance) {
    }
}
