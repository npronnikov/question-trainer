package ru.questionhacker.trainer.trainer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
                WHERE s.published=TRUE
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
}
