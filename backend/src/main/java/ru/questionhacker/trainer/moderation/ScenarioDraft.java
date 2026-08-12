package ru.questionhacker.trainer.moderation;

import java.util.List;

public record ScenarioDraft(
        String category,
        String secondaryCategory,
        String difficulty,
        String domain,
        String situation,
        String question,
        String hint,
        List<String> options,
        String correctCategory,
        String explanation,
        String confusedWith,
        String contrast) {
}
