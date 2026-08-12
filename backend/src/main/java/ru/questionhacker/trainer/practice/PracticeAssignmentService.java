package ru.questionhacker.trainer.practice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PracticeAssignmentService {

    private static final Set<String> CATEGORIES = Set.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");

    private final PracticeRepository practice;

    public PracticeAssignmentService(PracticeRepository practice) {
        this.practice = practice;
    }

    @Transactional
    public AssignmentView create(UUID ownerId, String requestedCategory) {
        String category = normalizeCategory(requestedCategory);
        var source = practice.selectAssignmentSource(ownerId, category)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Нет доступной ситуации для практики"));
        String guidance = source.operation() + " Контрольный ориентир: " + source.cue();
        return view(practice.createAssignment(
                ownerId, source, guidance, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Transactional(readOnly = true)
    public AssignmentView get(UUID ownerId, UUID assignmentId) {
        return practice.findAssignment(ownerId, assignmentId)
                .map(this::view)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Практика не найдена"));
    }

    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестная категория");
        }
        return value;
    }

    private AssignmentView view(PracticeRepository.AssignmentRow row) {
        return new AssignmentView(
                row.id(), row.domain(), row.situation(),
                new TargetCategory(row.categoryCode(), row.categoryName(), row.guidance()),
                row.createdAt());
    }

    public record AssignmentView(
            UUID assignmentId,
            String domain,
            String situation,
            TargetCategory targetCategory,
            OffsetDateTime createdAt) {
    }

    public record TargetCategory(String code, String name, String guidance) {
    }
}
