package ru.questionhacker.trainer.practice;

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
public class PracticeRepository {

    private final JdbcTemplate jdbc;

    public PracticeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AssignmentSource> selectAssignmentSource(UUID ownerId, String targetCategory) {
        String categoryClause = targetCategory == null ? "" : " AND s.category_code=? ";
        String sql = """
                SELECT s.id AS scenario_id, s.category_code, c.name,
                       c.operation_text, c.cue_text, s.domain_text, s.situation_text
                FROM scenario s
                JOIN category c ON c.code=s.category_code
                WHERE s.published=TRUE
                """ + categoryClause + """
                ORDER BY CASE WHEN EXISTS (
                  SELECT 1 FROM practice_assignment pa
                  WHERE pa.owner_id=? AND pa.scenario_id=s.id
                ) THEN 1 ELSE 0 END,
                CASE s.difficulty WHEN 'L2' THEN 1 WHEN 'L3' THEN 2 ELSE 3 END,
                s.external_key
                LIMIT 1
                """;
        List<Object> args = new ArrayList<>();
        if (targetCategory != null) args.add(targetCategory);
        args.add(ownerId);
        return jdbc.query(sql, this::source, args.toArray()).stream().findFirst();
    }

    public AssignmentRow createAssignment(UUID ownerId, AssignmentSource source,
                                          String guidance, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO practice_assignment(
                  id, owner_id, scenario_id, target_category_code, domain_text,
                  situation_text, guidance_text, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerId, source.scenarioId(), source.categoryCode(), source.domain(),
                source.situation(), guidance, now);
        return new AssignmentRow(id, ownerId, source.scenarioId(), source.categoryCode(),
                source.categoryName(), source.domain(), source.situation(), guidance, now);
    }

    public Optional<AssignmentRow> findAssignment(UUID ownerId, UUID assignmentId) {
        return jdbc.query("""
                SELECT pa.id, pa.owner_id, pa.scenario_id, pa.target_category_code,
                       c.name, pa.domain_text, pa.situation_text, pa.guidance_text,
                       pa.created_at
                FROM practice_assignment pa
                JOIN category c ON c.code=pa.target_category_code
                WHERE pa.owner_id=? AND pa.id=?
                """, (rs, row) -> new AssignmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("scenario_id", UUID.class),
                rs.getString("target_category_code"),
                rs.getString("name"),
                rs.getString("domain_text"),
                rs.getString("situation_text"),
                rs.getString("guidance_text"),
                rs.getObject("created_at", OffsetDateTime.class)), ownerId, assignmentId)
                .stream().findFirst();
    }

    private AssignmentSource source(ResultSet rs, int ignored) throws SQLException {
        return new AssignmentSource(
                rs.getObject("scenario_id", UUID.class),
                rs.getString("category_code"),
                rs.getString("name"),
                rs.getString("operation_text"),
                rs.getString("cue_text"),
                rs.getString("domain_text"),
                rs.getString("situation_text"));
    }

    public record AssignmentSource(
            UUID scenarioId,
            String categoryCode,
            String categoryName,
            String operation,
            String cue,
            String domain,
            String situation) {
    }

    public record AssignmentRow(
            UUID id,
            UUID ownerId,
            UUID scenarioId,
            String categoryCode,
            String categoryName,
            String domain,
            String situation,
            String guidance,
            OffsetDateTime createdAt) {
    }
}
