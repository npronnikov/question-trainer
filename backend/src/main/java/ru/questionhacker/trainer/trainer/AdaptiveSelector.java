package ru.questionhacker.trainer.trainer;

import java.util.UUID;
import java.util.function.DoubleSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AdaptiveSelector {

    private final TrainerRepository trainer;
    private final DoubleSupplier random;

    @Autowired
    public AdaptiveSelector(TrainerRepository trainer) {
        this(trainer, Math::random);
    }

    AdaptiveSelector(TrainerRepository trainer, DoubleSupplier random) {
        this.trainer = trainer;
        this.random = random;
    }

    public Selection select(UUID ownerId, String difficulty) {
        double roll = random.getAsDouble();
        if (roll < 0 || roll >= 1) {
            throw new IllegalStateException("Random source must return a value in [0, 1)");
        }

        SelectionMode requested = roll < 0.50
                ? SelectionMode.WEAK
                : roll < 0.80 ? SelectionMode.CONFUSION : SelectionMode.REVIEW;
        var scenario = switch (requested) {
            case WEAK -> trainer.selectWeak(ownerId, difficulty);
            case CONFUSION -> trainer.selectConfusion(ownerId, difficulty);
            case REVIEW -> trainer.selectReview(ownerId, difficulty);
        };
        if (scenario.isPresent()) return new Selection(requested, scenario.get());

        var fallback = trainer.selectWeak(ownerId, difficulty)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Нет доступных карточек"));
        return new Selection(SelectionMode.WEAK, fallback);
    }

    public enum SelectionMode {
        WEAK,
        CONFUSION,
        REVIEW
    }

    public record Selection(SelectionMode mode, TrainerRepository.ScenarioRow scenario) {
    }
}
