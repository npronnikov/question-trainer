package ru.questionhacker.trainer.practice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeIdeaProgressService {

    private final PracticeRepository practice;
    private final ObjectMapper json;

    public PracticeIdeaProgressService(PracticeRepository practice, ObjectMapper json) {
        this.practice = practice;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ProgressView get(UUID ownerId) {
        List<PracticeRepository.IdeaProgressRow> rows = practice.listIdeaProgress(ownerId);
        int lastStartedCycle = rows.stream()
                .mapToInt(PracticeRepository.IdeaProgressRow::cycleNumber)
                .max().orElse(0);
        Map<Integer, List<PracticeRepository.IdeaProgressRow>> byCycle = new HashMap<>();
        rows.forEach(row -> byCycle.computeIfAbsent(row.cycleNumber(), ignored -> new ArrayList<>())
                .add(row));
        List<Integer> answeredCycles = new ArrayList<>();
        for (int cycle = 1; cycle <= lastStartedCycle; cycle++) {
            List<PracticeRepository.IdeaProgressRow> cycleRows = byCycle.getOrDefault(cycle, List.of());
            boolean allPositionsExist = cycleRows.stream()
                    .map(PracticeRepository.IdeaProgressRow::cyclePosition)
                    .distinct().count() == 7;
            boolean allVerified = cycleRows.stream()
                    .allMatch(row -> row.attemptId() != null);
            if (!allPositionsExist || !allVerified) break;
            answeredCycles.add(cycle);
        }
        int lastAnsweredCycle = answeredCycles.size();

        List<PracticeRepository.CategoryRow> definitions = practice.categoryDefinitions();
        List<String> categoryCodes = definitions.stream()
                .map(PracticeRepository.CategoryRow::code).toList();
        if (!PracticeAssignmentService.CANONICAL_CATEGORIES.equals(categoryCodes)) {
            throw new IllegalStateException("Practice categories do not match the canonical cycle");
        }
        List<CategoryProgress> categories = definitions.stream()
                .map(category -> new CategoryProgress(
                        category.code(), category.name(), points(
                                category.code(), lastStartedCycle, answeredCycles, rows)))
                .toList();
        return new ProgressView(
                lastStartedCycle, lastAnsweredCycle, lastAnsweredCycle >= 2, categories);
    }

    private List<ProgressPoint> points(String categoryCode,
                                       int lastStartedCycle,
                                       List<Integer> answeredCycles,
                                       List<PracticeRepository.IdeaProgressRow> rows) {
        List<ProgressPoint> points = new ArrayList<>();
        for (int cycle = 1; cycle <= lastStartedCycle; cycle++) {
            final int cycleNumber = cycle;
            var row = rows.stream()
                    .filter(item -> item.cycleNumber() == cycleNumber
                            && categoryCode.equals(item.categoryCode()))
                    .findFirst().orElse(null);
            if (row == null) {
                points.add(gap(cycle, null, "NOT_STARTED"));
            } else if (row.attemptId() == null) {
                points.add(gap(cycle, row, "NOT_VERIFIED"));
            } else if (!answeredCycles.contains(cycle)) {
                points.add(gap(cycle, row, "CYCLE_INCOMPLETE"));
            } else if (!ModelAssessmentV3Parser.SCHEMA_VERSION.equals(row.schemaVersion())
                    || row.ideaPotentialDimensionsJson() == null) {
                points.add(gap(cycle, row, "LEGACY_SCHEMA"));
            } else {
                List<ModelAssessmentV3.IdeaDimension> dimensions = readDimensions(
                        row.ideaPotentialDimensionsJson());
                if (dimensions == null) {
                    points.add(gap(cycle, row, "INVALID_PROFILE"));
                    continue;
                }
                IdeaPotential potential = new IdeaPotential(
                        dimensions, row.ideaPotentialScore(), row.ideaPotentialScore() != null);
                points.add(new ProgressPoint(
                        cycle, row.assignmentId(), row.attemptId(), row.situation(),
                        row.completedAt(), row.ideaPotentialScore() == null
                                ? "INCOMPLETE_PROFILE" : null,
                        potential, row.promptVersion(), row.modelId()));
            }
        }
        return List.copyOf(points);
    }

    private ProgressPoint gap(int cycle,
                              PracticeRepository.IdeaProgressRow row,
                              String reason) {
        return new ProgressPoint(
                cycle,
                row == null ? null : row.assignmentId(),
                row == null ? null : row.attemptId(),
                row == null ? null : row.situation(),
                row == null ? null : row.completedAt(),
                reason, null,
                row == null ? null : row.promptVersion(),
                row == null ? null : row.modelId());
    }

    private List<ModelAssessmentV3.IdeaDimension> readDimensions(String value) {
        try {
            List<ModelAssessmentV3.IdeaDimension> dimensions = json.readValue(
                    value, new TypeReference<>() { });
            ModelAssessmentV3Parser.validateIdeaDimensions(dimensions);
            return dimensions;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return null;
        }
    }

    public record ProgressView(
            int lastStartedCycle,
            int lastAnsweredCycle,
            boolean comparisonAvailable,
            List<CategoryProgress> categories) {
    }

    public record CategoryProgress(
            String code,
            String name,
            List<ProgressPoint> points) {
    }

    public record ProgressPoint(
            int cycleNumber,
            UUID assignmentId,
            UUID attemptId,
            String situation,
            OffsetDateTime completedAt,
            String gapReason,
            IdeaPotential ideaPotential,
            Integer promptVersion,
            String modelId) {
    }

    public record IdeaPotential(
            List<ModelAssessmentV3.IdeaDimension> dimensions,
            BigDecimal overallScore,
            boolean complete) {
    }
}
