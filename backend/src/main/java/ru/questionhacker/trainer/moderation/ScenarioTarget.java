package ru.questionhacker.trainer.moderation;

import java.util.Locale;

public enum ScenarioTarget {
    PRACTICE,
    TRAINER;

    public static ScenarioTarget parse(String value) {
        try {
            return value == null ? null : valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
