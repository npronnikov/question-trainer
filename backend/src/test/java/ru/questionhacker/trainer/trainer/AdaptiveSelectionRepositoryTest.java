package ru.questionhacker.trainer.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import ru.questionhacker.trainer.auth.AppUser;
import ru.questionhacker.trainer.auth.UserAccountRepository;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:adaptive-ranking;DB_CLOSE_DELAY=-1"
})
class AdaptiveSelectionRepositoryTest {

    @Autowired
    private TrainerRepository trainer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserAccountRepository users;

    private AppUser alice;
    private OffsetDateTime now;

    @BeforeEach
    void resetUserData() {
        jdbc.update("DELETE FROM trainer_attempt");
        jdbc.update("DELETE FROM trainer_issuance");
        jdbc.update("DELETE FROM category_confusion");
        jdbc.update("DELETE FROM category_mastery");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM app_user WHERE id <> ?", UserAccountRepository.SYSTEM_USER_ID);
        alice = users.create("ranking-alice", null, "$2a$alice", Set.of("USER"), false);
        now = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Test
    void weakRankingCombinesMasteryWithRecentCardDiversity() {
        jdbc.update("""
                INSERT INTO category_mastery(
                  owner_id, category_code, mastery_score, attempt_count, correct_count,
                  last_seen_at, next_review_at
                )
                SELECT ?, code, 80, 10, 8, ?, ? FROM category
                WHERE code NOT IN ('INVERSION', 'HYPERBOLE')
                """, alice.id(), now.minusDays(1), now.plusDays(1));
        mastery("INVERSION", 10, now.minusDays(1), now.plusDays(1));
        mastery("HYPERBOLE", 5, now.minusDays(1), now.plusDays(1));
        jdbc.update("""
                INSERT INTO trainer_issuance(id, owner_id, scenario_id, status, issued_at, expires_at)
                SELECT ?, ?, id, 'ISSUED', ?, ? FROM scenario
                WHERE category_code='HYPERBOLE' AND difficulty='L2'
                ORDER BY external_key LIMIT 1
                """, UUID.randomUUID(), alice.id(), now, now.plusMinutes(30));

        var selected = trainer.selectWeak(alice.id(), "L2").orElseThrow();

        assertThat(selected.correctCategory()).isEqualTo("INVERSION");
        assertThat(selected.difficulty()).isEqualTo("L2");
    }

    @Test
    void confusionRankingTargetsMostFrequentCorrectCategory() {
        jdbc.update("""
                INSERT INTO category_confusion(
                  owner_id, selected_category_code, correct_category_code,
                  confusion_count, last_confused_at
                ) VALUES (?, 'REFRAMING', 'BACKCASTING', 4, ?)
                """, alice.id(), now);

        var selected = trainer.selectConfusion(alice.id(), "L3").orElseThrow();

        assertThat(selected.correctCategory()).isEqualTo("BACKCASTING");
        assertThat(selected.difficulty()).isEqualTo("L3");
    }

    @Test
    void reviewRankingOnlyUsesCategoriesWhoseReviewIsDue() {
        mastery("CROSS_DISCIPLINE", 75, now.minusDays(5), now.minusHours(1));
        mastery("SIMPLIFICATION", 20, now.minusDays(10), now.plusDays(3));

        var selected = trainer.selectReview(alice.id(), "L1").orElseThrow();

        assertThat(selected.correctCategory()).isEqualTo("CROSS_DISCIPLINE");
        assertThat(selected.difficulty()).isEqualTo("L1");
    }

    private void mastery(String category, double score,
                         OffsetDateTime lastSeen, OffsetDateTime nextReview) {
        jdbc.update("""
                INSERT INTO category_mastery(
                  owner_id, category_code, mastery_score, attempt_count, correct_count,
                  last_seen_at, next_review_at
                ) VALUES (?, ?, ?, 10, 7, ?, ?)
                """, alice.id(), category, score, lastSeen, nextReview);
    }
}
