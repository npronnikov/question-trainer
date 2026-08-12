package ru.questionhacker.trainer.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AdaptiveSelectionTest {

    private final UUID ownerId = UUID.randomUUID();
    private final TrainerRepository repository = mock(TrainerRepository.class);
    private final TrainerRepository.ScenarioRow scenario = new TrainerRepository.ScenarioRow(
            UUID.randomUUID(), "adaptive-test", "INVERSION", "L2", "ПРОДУКТ",
            "Ситуация", "Вопрос", "Объяснение", null, null);

    @Test
    void firstHalfSelectsWeakCategoryBranch() {
        when(repository.selectWeak(ownerId, null)).thenReturn(Optional.of(scenario));

        var selection = new AdaptiveSelector(repository, () -> 0.49).select(ownerId, null);

        assertThat(selection.mode()).isEqualTo(AdaptiveSelector.SelectionMode.WEAK);
        verify(repository).selectWeak(ownerId, null);
    }

    @Test
    void nextThirtyPercentSelectsKnownConfusionBranch() {
        when(repository.selectConfusion(ownerId, "L3")).thenReturn(Optional.of(scenario));

        var selection = new AdaptiveSelector(repository, () -> 0.50).select(ownerId, "L3");

        assertThat(selection.mode()).isEqualTo(AdaptiveSelector.SelectionMode.CONFUSION);
        verify(repository).selectConfusion(ownerId, "L3");
    }

    @Test
    void finalTwentyPercentSelectsDueReviewBranch() {
        when(repository.selectReview(ownerId, null)).thenReturn(Optional.of(scenario));

        var selection = new AdaptiveSelector(repository, () -> 0.80).select(ownerId, null);

        assertThat(selection.mode()).isEqualTo(AdaptiveSelector.SelectionMode.REVIEW);
        verify(repository).selectReview(ownerId, null);
    }

    @Test
    void emptySpecializedBranchFallsBackToWeakSelection() {
        when(repository.selectConfusion(ownerId, null)).thenReturn(Optional.empty());
        when(repository.selectWeak(ownerId, null)).thenReturn(Optional.of(scenario));

        var selection = new AdaptiveSelector(repository, () -> 0.65).select(ownerId, null);

        assertThat(selection.mode()).isEqualTo(AdaptiveSelector.SelectionMode.WEAK);
        assertThat(selection.scenario()).isEqualTo(scenario);
    }
}
