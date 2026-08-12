package ru.questionhacker.trainer.trainer;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TrainerEngine {

    private static final Set<String> DIFFICULTIES = Set.of("L1", "L2", "L3");
    private static final Duration ISSUANCE_TTL = Duration.ofMinutes(30);

    private final TrainerRepository trainer;
    private final AdaptiveSelector selector;
    private final Clock clock;

    @Autowired
    public TrainerEngine(TrainerRepository trainer, AdaptiveSelector selector) {
        this(trainer, selector, Clock.systemUTC());
    }

    TrainerEngine(TrainerRepository trainer, AdaptiveSelector selector, Clock clock) {
        this.trainer = trainer;
        this.selector = selector;
        this.clock = clock;
    }

    @Transactional
    public NextCard next(UUID ownerId, String requestedDifficulty) {
        String difficulty = normalizeDifficulty(requestedDifficulty);
        var selection = selector.select(ownerId, difficulty);
        var scenario = selection.scenario();
        List<CardOption> options = trainer.options(scenario.id()).stream()
                .map(option -> new CardOption(option.code(), option.name()))
                .toList();
        if (options.size() != 4) {
            throw new IllegalStateException("Published scenario must have four options");
        }
        OffsetDateTime issuedAt = OffsetDateTime.now(clock);
        var issuance = trainer.createIssuance(
                ownerId, scenario.id(), issuedAt, issuedAt.plus(ISSUANCE_TTL));
        return new NextCard(
                issuance.id(), issuance.expiresAt(),
                new Card(scenario.id(), scenario.difficulty(), scenario.domain(),
                        scenario.situation(), scenario.question(), options));
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AttemptFeedback answer(UUID ownerId, UUID issuanceId,
                                  String selectedCategory, String rationale) {
        String selected = selectedCategory.strip().toUpperCase(Locale.ROOT);
        var issued = trainer.findIssuedScenarioForUpdate(ownerId, issuanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка не найдена"));

        var existing = trainer.findAttempt(ownerId, issuanceId);
        if (existing.isPresent()) {
            return feedback(issued, existing.get(), currentMastery(ownerId, issued.correctCategory()));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (issued.expiresAt().isBefore(now)) {
            trainer.markExpired(ownerId, issuanceId);
            throw new ResponseStatusException(HttpStatus.GONE, "Время ответа на карточку истекло");
        }
        if (!"ISSUED".equals(issued.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Карточка уже закрыта");
        }
        if (!trainer.isScenarioOption(issued.scenarioId(), selected)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Выбранная категория отсутствует среди вариантов карточки");
        }

        boolean correct = issued.correctCategory().equals(selected);
        double delta = masteryDelta(issued.difficulty(), correct);
        var previous = currentMastery(ownerId, issued.correctCategory());
        var updated = new TrainerRepository.MasteryRow(
                ownerId,
                issued.correctCategory(),
                clamp(previous.score() + delta),
                previous.attempts() + 1,
                previous.correctAnswers() + (correct ? 1 : 0),
                now,
                nextReview(now, issued.difficulty(), correct));
        trainer.saveMastery(updated);
        if (!correct) {
            trainer.incrementConfusion(ownerId, selected, issued.correctCategory(), now);
        }

        var attempt = trainer.createAttempt(
                issuanceId, ownerId, issued.scenarioId(), selected, rationale.strip(),
                correct, delta, now);
        trainer.markAnswered(ownerId, issuanceId, now);
        return feedback(issued, attempt, updated);
    }

    private TrainerRepository.MasteryRow currentMastery(UUID ownerId, String category) {
        return trainer.mastery(ownerId, category)
                .orElseGet(() -> new TrainerRepository.MasteryRow(
                        ownerId, category, 0, 0, 0, null, null));
    }

    private AttemptFeedback feedback(TrainerRepository.IssuedScenarioRow issued,
                                     TrainerRepository.AttemptRow attempt,
                                     TrainerRepository.MasteryRow mastery) {
        String contrast;
        if (attempt.correct()) {
            contrast = "Категория определена верно: выбранная операция совпадает с эталоном карточки.";
        } else if (issued.confusedWith() != null
                && issued.confusedWith().equals(attempt.selectedCategory())
                && issued.contrastExplanation() != null) {
            contrast = issued.contrastExplanation();
        } else {
            contrast = trainer.contrast(issued.correctCategory(), attempt.selectedCategory())
                    .orElse("Сравните операцию вопроса с определением правильной категории.");
        }
        String nextStep = attempt.correct()
                ? "Закрепите различие на следующей карточке повышенной сложности."
                : "Переформулируйте вопрос так, чтобы он однозначно выполнял операцию категории "
                        + issued.correctCategory() + ".";
        return new AttemptFeedback(
                attempt.id(), attempt.correct(), attempt.selectedCategory(),
                issued.correctCategory(), attempt.rationale(), issued.explanation(),
                contrast, nextStep, masteryView(mastery));
    }

    private Mastery masteryView(TrainerRepository.MasteryRow row) {
        return new Mastery(row.categoryCode(), row.score(), level(row.score()),
                row.attempts(), row.correctAnswers());
    }

    private double masteryDelta(String difficulty, boolean correct) {
        if (correct) {
            return switch (difficulty) {
                case "L1" -> 8;
                case "L2" -> 11;
                case "L3" -> 14;
                default -> throw new IllegalStateException("Unknown scenario difficulty: " + difficulty);
            };
        }
        return switch (difficulty) {
            case "L1" -> -4;
            case "L2" -> -5;
            case "L3" -> -6;
            default -> throw new IllegalStateException("Unknown scenario difficulty: " + difficulty);
        };
    }

    private OffsetDateTime nextReview(OffsetDateTime now, String difficulty, boolean correct) {
        if (!correct) return now.plusDays(1);
        return now.plusDays(switch (difficulty) {
            case "L1" -> 2;
            case "L2" -> 4;
            case "L3" -> 7;
            default -> 1;
        });
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private String level(double score) {
        if (score >= 85) return "MASTERED";
        if (score >= 60) return "CONFIDENT";
        if (score >= 30) return "DEVELOPING";
        return "NEW";
    }

    private String normalizeDifficulty(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String normalized = requested.strip().toUpperCase(Locale.ROOT);
        if (!DIFFICULTIES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный уровень сложности");
        }
        return normalized;
    }

    public record NextCard(UUID issuanceId, OffsetDateTime expiresAt, Card card) {
    }

    public record Card(
            UUID id,
            String difficulty,
            String domain,
            String situation,
            String question,
            List<CardOption> options) {
    }

    public record CardOption(String code, String name) {
    }

    public record AttemptFeedback(
            UUID attemptId,
            boolean correct,
            String selectedCategory,
            String correctCategory,
            String rationale,
            String operationExplanation,
            String contrast,
            String nextStep,
            Mastery mastery) {
    }

    public record Mastery(
            String category,
            double score,
            String level,
            int attempts,
            int correctAnswers) {
    }
}
