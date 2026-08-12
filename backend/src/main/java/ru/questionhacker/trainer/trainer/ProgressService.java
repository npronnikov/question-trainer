package ru.questionhacker.trainer.trainer;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProgressService {

    private final TrainerRepository trainer;

    public ProgressService(TrainerRepository trainer) {
        this.trainer = trainer;
    }

    @Transactional(readOnly = true)
    public ProgressView progress(UUID ownerId) {
        List<CategoryProgress> categories = trainer.categoryProgress(ownerId).stream()
                .map(this::category)
                .toList();
        List<Confusion> confusions = trainer.confusions(ownerId).stream()
                .map(item -> new Confusion(
                        item.selectedCategory(), item.selectedName(),
                        item.correctCategory(), item.correctName(),
                        item.count(), item.lastConfusedAt()))
                .toList();
        return new ProgressView(categories, confusions, recommendation(categories, confusions));
    }

    private CategoryProgress category(TrainerRepository.CategoryProgressRow row) {
        double accuracy = row.attempts() == 0
                ? 0
                : Math.round(row.correctAnswers() * 1000.0 / row.attempts()) / 10.0;
        return new CategoryProgress(
                row.code(), row.name(), row.score(), level(row.score()),
                row.attempts(), row.correctAnswers(), accuracy,
                row.lastSeenAt(), row.nextReviewAt());
    }

    private String recommendation(List<CategoryProgress> categories, List<Confusion> confusions) {
        if (!confusions.isEmpty()) {
            Confusion top = confusions.getFirst();
            return "Разберите различие «" + top.selectedName() + "» и «"
                    + top.correctName() + "»: это самая частая направленная путаница.";
        }
        return categories.stream()
                .min(Comparator.comparingDouble(CategoryProgress::score)
                        .thenComparing(CategoryProgress::code))
                .map(item -> "Следующая цель — «" + item.name()
                        + "»: начните с карточки L1 и проговорите операцию вопроса.")
                .orElse("Выполните первую карточку, чтобы получить персональную рекомендацию.");
    }

    private String level(double score) {
        if (score >= 85) return "MASTERED";
        if (score >= 60) return "CONFIDENT";
        if (score >= 30) return "DEVELOPING";
        return "NEW";
    }

    public record ProgressView(
            List<CategoryProgress> categories,
            List<Confusion> confusions,
            String recommendation) {
    }

    public record CategoryProgress(
            String code,
            String name,
            double score,
            String level,
            int attempts,
            int correctAnswers,
            double accuracyPercent,
            OffsetDateTime lastSeenAt,
            OffsetDateTime nextReviewAt) {
    }

    public record Confusion(
            String selectedCategory,
            String selectedName,
            String correctCategory,
            String correctName,
            int count,
            OffsetDateTime lastConfusedAt) {
    }
}
