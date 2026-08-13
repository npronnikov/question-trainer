package ru.questionhacker.trainer.moderation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ModerationRepository {

    private final JdbcTemplate jdbc;

    public ModerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockGenerationSequence() {
        jdbc.queryForObject("""
                SELECT code FROM category
                ORDER BY sort_order
                LIMIT 1 FOR UPDATE
                """, String.class);
    }

    public List<String> categoryCodes() {
        return jdbc.queryForList("SELECT code FROM category ORDER BY sort_order", String.class);
    }

    public long candidateCount(String target) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_candidate WHERE content_target=?", Long.class, target);
        return count == null ? 0L : count;
    }

    public void insert(CandidateRow row) {
        jdbc.update("""
                INSERT INTO scenario_candidate(
                  id, status, content_target, version_number, category_code, secondary_category_code,
                  difficulty, domain_text, situation_text, question_text, hint_text,
                  options_json, correct_category_code, explanation_text, confused_with,
                  contrast_explanation, content_hash, source_model,
                  rejection_reasons_json, warnings_json, published_scenario_id,
                  created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.status(), row.target(), row.version(), row.category(), row.secondaryCategory(),
                row.difficulty(), row.domain(), row.situation(), row.question(), row.hint(),
                row.optionsJson(), row.correctCategory(), row.explanation(), row.confusedWith(),
                row.contrast(), row.contentHash(), row.sourceModel(), row.rejectionReasonsJson(),
                row.warningsJson(), row.publishedScenarioId(), row.createdAt(), row.updatedAt());
    }

    public Optional<CandidateRow> find(UUID id) {
        return jdbc.query("SELECT * FROM scenario_candidate WHERE id=?", this::candidate, id)
                .stream().findFirst();
    }

    public List<CandidateRow> list(String status) {
        return status == null
                ? jdbc.query("SELECT * FROM scenario_candidate ORDER BY updated_at, id", this::candidate)
                : jdbc.query("SELECT * FROM scenario_candidate WHERE status=? ORDER BY updated_at, id",
                        this::candidate, status);
    }

    public List<String> existingTexts() {
        return jdbc.query("""
                SELECT situation_text FROM scenario WHERE published=TRUE
                UNION ALL
                SELECT situation_text FROM scenario_candidate WHERE situation_text IS NOT NULL
                """, (rs, row) -> rs.getString(1));
    }

    public List<String> existingTextsExcluding(UUID candidateId) {
        return jdbc.query("""
                SELECT situation_text FROM scenario WHERE published=TRUE
                UNION ALL
                SELECT situation_text FROM scenario_candidate
                WHERE situation_text IS NOT NULL AND id<>?
                """, (rs, row) -> rs.getString(1), candidateId);
    }

    public boolean updateStatus(UUID id, int expectedVersion, String expectedStatus,
                                String newStatus, UUID publishedScenarioId,
                                String reasonsJson, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE scenario_candidate
                SET status=?, version_number=version_number+1, published_scenario_id=?,
                    rejection_reasons_json=?, updated_at=?
                WHERE id=? AND version_number=? AND status=?
                """, newStatus, publishedScenarioId, reasonsJson, now,
                id, expectedVersion, expectedStatus) == 1;
    }

    public boolean updateDraft(UUID id, int expectedVersion, CandidateRow value) {
        return jdbc.update("""
                UPDATE scenario_candidate SET
                  status=?, version_number=version_number+1, category_code=?,
                  secondary_category_code=?, difficulty=?, domain_text=?, situation_text=?,
                  question_text=?, hint_text=?, options_json=?, correct_category_code=?,
                  explanation_text=?, confused_with=?, contrast_explanation=?, content_hash=?,
                  rejection_reasons_json=?, warnings_json=?, updated_at=?
                WHERE id=? AND version_number=? AND status='PENDING_REVIEW'
                """, value.status(), value.category(), value.secondaryCategory(), value.difficulty(),
                value.domain(), value.situation(), value.question(), value.hint(), value.optionsJson(),
                value.correctCategory(), value.explanation(), value.confusedWith(), value.contrast(),
                value.contentHash(), value.rejectionReasonsJson(), value.warningsJson(),
                value.updatedAt(), id, expectedVersion) == 1;
    }

    public UUID publishScenario(CandidateRow candidate, List<String> options, OffsetDateTime now) {
        UUID scenarioId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO scenario(
                  id, external_key, content_target, category_code, difficulty, domain_text,
                  situation_text, question_text, hint_text, explanation_text, confused_with,
                  contrast_explanation, content_hash, published, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                """, scenarioId, "moderated-" + candidate.id(), candidate.target(), candidate.category(),
                candidate.difficulty(), candidate.domain(), candidate.situation(), candidate.question(),
                candidate.hint(), candidate.explanation(), candidate.confusedWith(), candidate.contrast(),
                candidate.contentHash(), now, now);
        for (int index = 0; index < options.size(); index++) {
            jdbc.update("""
                    INSERT INTO scenario_option(scenario_id, category_code, sort_order)
                    VALUES (?, ?, ?)
                    """, scenarioId, options.get(index), index);
        }
        return scenarioId;
    }

    public void setPublishedScenario(UUID candidateId, UUID scenarioId) {
        jdbc.update("UPDATE scenario_candidate SET published_scenario_id=? WHERE id=?",
                scenarioId, candidateId);
    }

    public void action(ActionRow action) {
        jdbc.update("""
                INSERT INTO moderation_action(
                  id, candidate_id, actor_id, action_type, reason_code, comment_text,
                  previous_status, new_status, previous_version, new_version,
                  before_json, after_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, action.id(), action.candidateId(), action.actorId(), action.type(),
                action.reason(), action.comment(), action.previousStatus(), action.newStatus(),
                action.previousVersion(), action.newVersion(), action.beforeJson(),
                action.afterJson(), action.createdAt());
    }

    private CandidateRow candidate(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new CandidateRow(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("content_target"), rs.getInt("version_number"), rs.getString("category_code"),
                rs.getString("secondary_category_code"), rs.getString("difficulty"),
                rs.getString("domain_text"), rs.getString("situation_text"),
                rs.getString("question_text"), rs.getString("hint_text"),
                rs.getString("options_json"), rs.getString("correct_category_code"),
                rs.getString("explanation_text"), rs.getString("confused_with"),
                rs.getString("contrast_explanation"), rs.getString("content_hash"),
                rs.getString("source_model"), rs.getString("rejection_reasons_json"),
                rs.getString("warnings_json"), rs.getObject("published_scenario_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public record CandidateRow(
            UUID id, String status, String target, int version, String category, String secondaryCategory,
            String difficulty, String domain, String situation, String question, String hint,
            String optionsJson, String correctCategory, String explanation, String confusedWith,
            String contrast, String contentHash, String sourceModel,
            String rejectionReasonsJson, String warningsJson, UUID publishedScenarioId,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ActionRow(
            UUID id, UUID candidateId, UUID actorId, String type, String reason, String comment,
            String previousStatus, String newStatus, int previousVersion, int newVersion,
            String beforeJson, String afterJson, OffsetDateTime createdAt) {
    }
}
