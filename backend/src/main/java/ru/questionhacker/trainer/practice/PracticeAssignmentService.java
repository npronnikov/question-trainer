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

    private final PracticeRepository practice;

    public PracticeAssignmentService(PracticeRepository practice) {
        this.practice = practice;
    }

    @Transactional
    public AssignmentView create(UUID ownerId) {
        practice.lockOwner(ownerId);
        List<String> categories = practice.categoryCodes();
        if (categories.isEmpty()) {
            throw PracticeAssignmentUnavailableException.exhausted();
        }
        long position = practice.assignmentCount(ownerId);
        String category = categories.get((int) (position % categories.size()));
        var source = practice.selectAssignmentSource(ownerId, category)
                .orElseThrow(PracticeAssignmentUnavailableException::exhausted);
        String guidance = source.operation() + " Контрольный ориентир: " + source.cue();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var assignment = practice.createAssignment(ownerId, source, guidance, now);
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
                row.createdAt());
    }

    public record AssignmentView(
            UUID assignmentId,
            String domain,
            String situation,
            String hint,
            TargetCategory targetCategory,
            OffsetDateTime createdAt) {
    }

    public record TargetCategory(String code, String name, String guidance) {
    }
}
