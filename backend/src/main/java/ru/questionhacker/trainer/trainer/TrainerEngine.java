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
    private final Clock clock;

    @Autowired
    public TrainerEngine(TrainerRepository trainer) {
        this(trainer, Clock.systemUTC());
    }

    TrainerEngine(TrainerRepository trainer, Clock clock) {
        this.trainer = trainer;
        this.clock = clock;
    }

    @Transactional
    public NextCard next(UUID ownerId, String requestedDifficulty) {
        String difficulty = normalizeDifficulty(requestedDifficulty);
        var scenario = trainer.selectForIssuance(ownerId, difficulty)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Нет доступных карточек"));
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
}
