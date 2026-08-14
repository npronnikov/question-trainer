package ru.questionhacker.trainer.trainer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrainerRepository {

    private final JdbcTemplate jdbc;

    public TrainerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ScenarioRow> selectForIssuance(UUID ownerId, String difficulty) {
        String difficultyClause = difficulty == null ? "" : " AND s.difficulty=? ";
        String sql = """
                SELECT s.id, s.external_key, s.category_code, s.difficulty, s.domain_text,
                       s.situation_text, s.question_text, s.explanation_text,
                       s.confused_with, s.contrast_explanation
                FROM scenario s
                WHERE s.published=TRUE AND s.content_target='TRAINER'
                """ + difficultyClause + """
                ORDER BY CASE WHEN EXISTS (
                  SELECT 1 FROM trainer_issuance ti
                  WHERE ti.owner_id=? AND ti.scenario_id=s.id
                ) THEN 1 ELSE 0 END,
                s.external_key
                LIMIT 1
                """;
        List<ScenarioRow> rows = difficulty == null
                ? jdbc.query(sql, this::scenarioRow, ownerId)
                : jdbc.query(sql, this::scenarioRow, difficulty, ownerId);
        return rows.stream().findFirst();
    }

    public Optional<ScenarioRow> selectWeak(UUID ownerId, String difficulty) {
        String difficultyClause = difficulty == null ? "" : " AND s.difficulty=? ";
        String sql = """
                SELECT s.id, s.external_key, s.category_code, s.difficulty, s.domain_text,
                       s.situation_text, s.question_text, s.explanation_text,
                       s.confused_with, s.contrast_explanation
                FROM scenario s
                LEFT JOIN category_mastery cm
                  ON cm.owner_id=? AND cm.category_code=s.category_code
                WHERE s.published=TRUE AND s.content_target='TRAINER'
                """ + difficultyClause + """
                ORDER BY
                  CASE WHEN EXISTS (
                    SELECT 1 FROM trainer_issuance recent
                    JOIN scenario recent_scenario ON recent_scenario.id=recent.scenario_id
                    WHERE recent.owner_id=?
                      AND recent_scenario.category_code=s.category_code
                      AND recent.issued_at >= DATEADD('DAY', -2, CURRENT_TIMESTAMP)
                  ) THEN 1 ELSE 0 END,
                  COALESCE(cm.mastery_score, 0),
                  COALESCE((
                    SELECT AVG(CASE WHEN ta.correct THEN 1.0 ELSE 0.0 END)
                    FROM trainer_attempt ta
                    JOIN scenario attempted ON attempted.id=ta.scenario_id
                    WHERE ta.owner_id=? AND attempted.category_code=s.category_code
                  ), 0),
                  CASE s.difficulty WHEN 'L1' THEN 1 WHEN 'L2' THEN 2 ELSE 3 END,
                  s.external_key
                LIMIT 1
                """;
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (difficulty != null) args.add(difficulty);
        args.add(ownerId);
        args.add(ownerId);
        return jdbc.query(sql, this::scenarioRow, args.toArray()).stream().findFirst();
    }

    public Optional<ScenarioRow> selectConfusion(UUID ownerId, String difficulty) {
        List<String> targets = jdbc.query("""
                SELECT correct_category_code
                FROM category_confusion
                WHERE owner_id=?
                ORDER BY confusion_count DESC, last_confused_at DESC
                LIMIT 1
                """, (rs, row) -> rs.getString("correct_category_code"), ownerId);
        if (targets.isEmpty()) return Optional.empty();
        return selectCategoryCard(ownerId, difficulty, targets.getFirst());
    }

    public Optional<ScenarioRow> selectReview(UUID ownerId, String difficulty) {
        String difficultyClause = difficulty == null ? "" : " AND s.difficulty=? ";
        String sql = """
                SELECT s.id, s.external_key, s.category_code, s.difficulty, s.domain_text,
                       s.situation_text, s.question_text, s.explanation_text,
                       s.confused_with, s.contrast_explanation
                FROM scenario s
                JOIN category_mastery cm
                  ON cm.owner_id=? AND cm.category_code=s.category_code
                WHERE s.published=TRUE AND s.content_target='TRAINER'
                  AND (cm.next_review_at IS NULL OR cm.next_review_at <= CURRENT_TIMESTAMP)
                """ + difficultyClause + """
                ORDER BY
                  CASE WHEN EXISTS (
                    SELECT 1 FROM trainer_issuance recent
                    JOIN scenario recent_scenario ON recent_scenario.id=recent.scenario_id
                    WHERE recent.owner_id=?
                      AND recent_scenario.category_code=s.category_code
                      AND recent.issued_at >= DATEADD('DAY', -2, CURRENT_TIMESTAMP)
                  ) THEN 1 ELSE 0 END,
                  cm.next_review_at NULLS FIRST,
                  cm.last_seen_at NULLS FIRST,
                  cm.mastery_score,
                  s.external_key
                LIMIT 1
                """;
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (difficulty != null) args.add(difficulty);
        args.add(ownerId);
        return jdbc.query(sql, this::scenarioRow, args.toArray()).stream().findFirst();
    }

    private Optional<ScenarioRow> selectCategoryCard(UUID ownerId, String difficulty,
                                                     String categoryCode) {
        String difficultyClause = difficulty == null ? "" : " AND s.difficulty=? ";
        String sql = """
                SELECT s.id, s.external_key, s.category_code, s.difficulty, s.domain_text,
                       s.situation_text, s.question_text, s.explanation_text,
                       s.confused_with, s.contrast_explanation
                FROM scenario s
                WHERE s.published=TRUE AND s.content_target='TRAINER' AND s.category_code=?
                """ + difficultyClause + """
                ORDER BY
                  CASE WHEN EXISTS (
                    SELECT 1 FROM trainer_issuance recent
                    JOIN scenario recent_scenario ON recent_scenario.id=recent.scenario_id
                    WHERE recent.owner_id=?
                      AND recent_scenario.category_code=s.category_code
                      AND recent.issued_at >= DATEADD('DAY', -2, CURRENT_TIMESTAMP)
                  ) THEN 1 ELSE 0 END,
                  CASE s.difficulty WHEN 'L3' THEN 1 WHEN 'L2' THEN 2 ELSE 3 END,
                  s.external_key
                LIMIT 1
                """;
        List<Object> args = new ArrayList<>();
        args.add(categoryCode);
        if (difficulty != null) args.add(difficulty);
        args.add(ownerId);
        return jdbc.query(sql, this::scenarioRow, args.toArray()).stream().findFirst();
    }

    public List<OptionRow> options(UUID scenarioId) {
        return jdbc.query("""
                SELECT so.category_code, c.name
                FROM scenario_option so
                JOIN category c ON c.code=so.category_code
                WHERE so.scenario_id=?
                ORDER BY so.sort_order
                """, (rs, row) -> new OptionRow(rs.getString("category_code"), rs.getString("name")),
                scenarioId);
    }

    public IssuanceRow createIssuance(UUID ownerId, UUID scenarioId,
                                      OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trainer_issuance(
                  id, owner_id, scenario_id, status, issued_at, expires_at
                ) VALUES (?, ?, ?, 'ISSUED', ?, ?)
                """, id, ownerId, scenarioId, issuedAt, expiresAt);
        return new IssuanceRow(id, ownerId, scenarioId, "ISSUED", issuedAt, expiresAt);
    }

    public Optional<IssuedScenarioRow> findIssuedScenarioForUpdate(UUID ownerId, UUID issuanceId) {
        List<IssuedScenarioRow> rows = jdbc.query("""
                SELECT ti.id AS issuance_id, ti.owner_id, ti.scenario_id, ti.status,
                       ti.issued_at, ti.expires_at,
                       s.category_code, s.difficulty, s.explanation_text,
                       s.confused_with, s.contrast_explanation
                FROM trainer_issuance ti
                JOIN scenario s ON s.id=ti.scenario_id
                WHERE ti.owner_id=? AND ti.id=?
                FOR UPDATE
                """, (rs, row) -> new IssuedScenarioRow(
                rs.getObject("issuance_id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("scenario_id", UUID.class),
                rs.getString("status"),
                rs.getObject("issued_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("category_code"),
                rs.getString("difficulty"),
                rs.getString("explanation_text"),
                rs.getString("confused_with"),
                rs.getString("contrast_explanation")), ownerId, issuanceId);
        return rows.stream().findFirst();
    }

    public Optional<AttemptRow> findAttempt(UUID ownerId, UUID issuanceId) {
        List<AttemptRow> rows = jdbc.query("""
                SELECT id, issuance_id, owner_id, scenario_id, selected_category_code,
                       rationale_text, correct, mastery_delta, created_at
                FROM trainer_attempt
                WHERE owner_id=? AND issuance_id=?
                """, (rs, row) -> new AttemptRow(
                rs.getObject("id", UUID.class),
                rs.getObject("issuance_id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("scenario_id", UUID.class),
                rs.getString("selected_category_code"),
                rs.getString("rationale_text"),
                rs.getBoolean("correct"),
                rs.getDouble("mastery_delta"),
                rs.getObject("created_at", OffsetDateTime.class)), ownerId, issuanceId);
        return rows.stream().findFirst();
    }

    public boolean isScenarioOption(UUID scenarioId, String categoryCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM scenario_option
                WHERE scenario_id=? AND category_code=?
                """, Integer.class, scenarioId, categoryCode);
        return count != null && count > 0;
    }

    public Optional<String> contrast(String categoryCode, String otherCategoryCode) {
        List<String> rows = jdbc.query("""
                SELECT contrast_text FROM category_contrast
                WHERE category_code=? AND other_category_code=?
                """, (rs, row) -> rs.getString("contrast_text"), categoryCode, otherCategoryCode);
        return rows.stream().findFirst();
    }

    public AttemptRow createAttempt(UUID issuanceId, UUID ownerId, UUID scenarioId,
                                    String selectedCategory, String rationale, boolean correct,
                                    double masteryDelta, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trainer_attempt(
                  id, issuance_id, owner_id, scenario_id, selected_category_code,
                  rationale_text, correct, mastery_delta, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, issuanceId, ownerId, scenarioId, selectedCategory,
                rationale, correct, masteryDelta, createdAt);
        return new AttemptRow(id, issuanceId, ownerId, scenarioId, selectedCategory,
                rationale, correct, masteryDelta, createdAt);
    }

    public void markAnswered(UUID ownerId, UUID issuanceId, OffsetDateTime answeredAt) {
        jdbc.update("""
                UPDATE trainer_issuance SET status='ANSWERED', answered_at=?
                WHERE owner_id=? AND id=?
                """, answeredAt, ownerId, issuanceId);
    }

    public void markExpired(UUID ownerId, UUID issuanceId) {
        jdbc.update("""
                UPDATE trainer_issuance SET status='EXPIRED'
                WHERE owner_id=? AND id=? AND status='ISSUED'
                """, ownerId, issuanceId);
    }

    public Optional<MasteryRow> mastery(UUID ownerId, String categoryCode) {
        List<MasteryRow> rows = jdbc.query("""
                SELECT owner_id, category_code, mastery_score, attempt_count,
                       correct_count, last_seen_at, next_review_at
                FROM category_mastery
                WHERE owner_id=? AND category_code=?
                """, (rs, row) -> new MasteryRow(
                rs.getObject("owner_id", UUID.class),
                rs.getString("category_code"),
                rs.getDouble("mastery_score"),
                rs.getInt("attempt_count"),
                rs.getInt("correct_count"),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getObject("next_review_at", OffsetDateTime.class)), ownerId, categoryCode);
        return rows.stream().findFirst();
    }

    public void saveMastery(MasteryRow value) {
        jdbc.update("""
                MERGE INTO category_mastery(
                  owner_id, category_code, mastery_score, attempt_count, correct_count,
                  last_seen_at, next_review_at
                ) KEY(owner_id, category_code) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, value.ownerId(), value.categoryCode(), value.score(), value.attempts(),
                value.correctAnswers(), value.lastSeenAt(), value.nextReviewAt());
    }

    public void incrementConfusion(UUID ownerId, String selectedCategory,
                                   String correctCategory, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE category_confusion
                SET confusion_count=confusion_count+1, last_confused_at=?
                WHERE owner_id=? AND selected_category_code=? AND correct_category_code=?
                """, now, ownerId, selectedCategory, correctCategory);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO category_confusion(
                      owner_id, selected_category_code, correct_category_code,
                      confusion_count, last_confused_at
                    ) VALUES (?, ?, ?, 1, ?)
                    """, ownerId, selectedCategory, correctCategory, now);
        }
    }

    public List<CategoryProgressRow> categoryProgress(UUID ownerId) {
        return jdbc.query("""
                SELECT c.code, c.name,
                       COALESCE(cm.mastery_score, 0) AS mastery_score,
                       COALESCE(cm.attempt_count, 0) AS attempt_count,
                       COALESCE(cm.correct_count, 0) AS correct_count,
                       cm.last_seen_at, cm.next_review_at
                FROM category c
                LEFT JOIN category_mastery cm
                  ON cm.owner_id=? AND cm.category_code=c.code
                ORDER BY c.sort_order
                """, (rs, row) -> new CategoryProgressRow(
                rs.getString("code"),
                rs.getString("name"),
                rs.getDouble("mastery_score"),
                rs.getInt("attempt_count"),
                rs.getInt("correct_count"),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getObject("next_review_at", OffsetDateTime.class)), ownerId);
    }

    public List<ConfusionRow> confusions(UUID ownerId) {
        return jdbc.query("""
                SELECT cc.selected_category_code, selected.name AS selected_name,
                       cc.correct_category_code, correct.name AS correct_name,
                       cc.confusion_count, cc.last_confused_at
                FROM category_confusion cc
                JOIN category selected ON selected.code=cc.selected_category_code
                JOIN category correct ON correct.code=cc.correct_category_code
                WHERE cc.owner_id=?
                ORDER BY cc.confusion_count DESC, cc.last_confused_at DESC
                """, (rs, row) -> new ConfusionRow(
                rs.getString("selected_category_code"),
                rs.getString("selected_name"),
                rs.getString("correct_category_code"),
                rs.getString("correct_name"),
                rs.getInt("confusion_count"),
                rs.getObject("last_confused_at", OffsetDateTime.class)), ownerId);
    }

    public void resetProgress(UUID ownerId) {
        jdbc.update("DELETE FROM trainer_attempt WHERE owner_id=?", ownerId);
        jdbc.update("DELETE FROM trainer_issuance WHERE owner_id=?", ownerId);
        jdbc.update("DELETE FROM category_confusion WHERE owner_id=?", ownerId);
        jdbc.update("DELETE FROM category_mastery WHERE owner_id=?", ownerId);
    }

    private ScenarioRow scenarioRow(ResultSet rs, int ignored) throws SQLException {
        return new ScenarioRow(
                rs.getObject("id", UUID.class), rs.getString("external_key"),
                rs.getString("category_code"), rs.getString("difficulty"),
                rs.getString("domain_text"), rs.getString("situation_text"),
                rs.getString("question_text"), rs.getString("explanation_text"),
                rs.getString("confused_with"), rs.getString("contrast_explanation"));
    }

    public record ScenarioRow(
            UUID id,
            String externalKey,
            String correctCategory,
            String difficulty,
            String domain,
            String situation,
            String question,
            String explanation,
            String confusedWith,
            String contrastExplanation) {
    }

    public record OptionRow(String code, String name) {
    }

    public record IssuanceRow(
            UUID id, UUID ownerId, UUID scenarioId, String status,
            OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
    }

    public record IssuedScenarioRow(
            UUID issuanceId,
            UUID ownerId,
            UUID scenarioId,
            String status,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            String correctCategory,
            String difficulty,
            String explanation,
            String confusedWith,
            String contrastExplanation) {
    }

    public record AttemptRow(
            UUID id,
            UUID issuanceId,
            UUID ownerId,
            UUID scenarioId,
            String selectedCategory,
            String rationale,
            boolean correct,
            double masteryDelta,
            OffsetDateTime createdAt) {
    }

    public record MasteryRow(
            UUID ownerId,
            String categoryCode,
            double score,
            int attempts,
            int correctAnswers,
            OffsetDateTime lastSeenAt,
            OffsetDateTime nextReviewAt) {
    }

    public record CategoryProgressRow(
            String code,
            String name,
            double score,
            int attempts,
            int correctAnswers,
            OffsetDateTime lastSeenAt,
            OffsetDateTime nextReviewAt) {
    }

    public record ConfusionRow(
            String selectedCategory,
            String selectedName,
            String correctCategory,
            String correctName,
            int count,
            OffsetDateTime lastConfusedAt) {
    }
}
