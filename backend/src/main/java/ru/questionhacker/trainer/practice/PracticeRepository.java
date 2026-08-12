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
import org.springframework.transaction.annotation.Transactional;

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

    public Optional<AttemptRow> findAttemptByIdempotency(UUID ownerId, String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        return queryAttempts("pa.owner_id=? AND pa.idempotency_key=?", ownerId, idempotencyKey)
                .stream().findFirst();
    }

    public AttemptRow createAttempt(UUID ownerId, AssignmentRow assignment, UUID parentAttemptId,
                                    String question, String answer, String reasoning, String solution,
                                    String revisedFieldsJson, String requestedModel,
                                    String idempotencyKey, OffsetDateTime now) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number), 0) FROM practice_attempt
                WHERE assignment_id=?
                """, Integer.class, assignment.id());
        int attemptNumber = (current == null ? 0 : current) + 1;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO practice_attempt(
                  id, assignment_id, owner_id, parent_attempt_id, attempt_number,
                  question_text, answer_text, reasoning_text, solution_text,
                  revised_fields_json, status, requested_model, idempotency_key, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EVALUATING', ?, ?, ?)
                """, id, assignment.id(), ownerId, parentAttemptId, attemptNumber,
                question, answer, reasoning, solution, revisedFieldsJson,
                requestedModel, idempotencyKey, now);
        return findAttempt(ownerId, id).orElseThrow();
    }

    public Optional<AttemptRow> findAttempt(UUID ownerId, UUID attemptId) {
        return queryAttempts("pa.owner_id=? AND pa.id=?", ownerId, attemptId).stream().findFirst();
    }

    public Optional<AttemptRow> findAttemptBySystem(UUID attemptId) {
        return queryAttempts("pa.id=?", attemptId).stream().findFirst();
    }

    private List<AttemptRow> queryAttempts(String where, Object... args) {
        return jdbc.query("""
                SELECT pa.id, pa.assignment_id, pa.owner_id, pa.parent_attempt_id,
                       pa.attempt_number, pa.question_text, pa.answer_text,
                       pa.reasoning_text, pa.solution_text, pa.revised_fields_json,
                       pa.status, pa.requested_model, pa.idempotency_key,
                       pa.created_at, pa.completed_at,
                       assignment.target_category_code, category.name AS category_name,
                       assignment.domain_text, assignment.situation_text,
                       assignment.guidance_text,
                       assessment.outcome, assessment.completeness_status,
                       assessment.step_results_json, assessment.category_fit_score,
                       assessment.category_fit_evidence, assessment.confused_with,
                       assessment.question_strength_score,
                       assessment.strength_dimensions_json, assessment.confidence,
                       assessment.strengths_json, assessment.correction_what,
                       assessment.correction_why, assessment.correction_example,
                       assessment.fields_to_revise_json, assessment.feedback_text,
                       assessment.model_id, assessment.failure_reason
                FROM practice_attempt pa
                JOIN practice_assignment assignment ON assignment.id=pa.assignment_id
                JOIN category category ON category.code=assignment.target_category_code
                LEFT JOIN practice_assessment assessment ON assessment.attempt_id=pa.id
                WHERE
                """ + where, (rs, row) -> new AttemptRow(
                rs.getObject("id", UUID.class),
                rs.getObject("assignment_id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("parent_attempt_id", UUID.class),
                rs.getInt("attempt_number"),
                rs.getString("question_text"),
                rs.getString("answer_text"),
                rs.getString("reasoning_text"),
                rs.getString("solution_text"),
                rs.getString("revised_fields_json"),
                rs.getString("status"),
                rs.getString("requested_model"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getString("target_category_code"),
                rs.getString("category_name"),
                rs.getString("domain_text"),
                rs.getString("situation_text"),
                rs.getString("guidance_text"),
                rs.getString("outcome"),
                rs.getString("completeness_status"),
                rs.getString("step_results_json"),
                (Integer) rs.getObject("category_fit_score"),
                rs.getString("category_fit_evidence"),
                rs.getString("confused_with"),
                (Integer) rs.getObject("question_strength_score"),
                rs.getString("strength_dimensions_json"),
                rs.getString("confidence"),
                rs.getString("strengths_json"),
                rs.getString("correction_what"),
                rs.getString("correction_why"),
                rs.getString("correction_example"),
                rs.getString("fields_to_revise_json"),
                rs.getString("feedback_text"),
                rs.getString("model_id"),
                rs.getString("failure_reason")), args);
    }

    public boolean completeAttempt(UUID attemptId, String status, OffsetDateTime completedAt) {
        return jdbc.update("""
                UPDATE practice_attempt SET status=?, completed_at=?
                WHERE id=? AND status='EVALUATING'
                """, status, completedAt, attemptId) == 1;
    }

    public void insertAssessment(AssessmentRow value) {
        jdbc.update("""
                INSERT INTO practice_assessment(
                  id, attempt_id, outcome, completeness_status, step_results_json,
                  category_fit_score, category_fit_evidence, confused_with,
                  question_strength_score, strength_dimensions_json, confidence,
                  strengths_json, correction_what, correction_why, correction_example,
                  fields_to_revise_json, feedback_text, prompt_key, prompt_version,
                  schema_version, model_id, latency_ms, failure_reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.id(), value.attemptId(), value.outcome(), value.completenessStatus(),
                value.stepResultsJson(), value.categoryFitScore(), value.categoryFitEvidence(),
                value.confusedWith(), value.questionStrengthScore(), value.strengthDimensionsJson(),
                value.confidence(), value.strengthsJson(), value.correctionWhat(),
                value.correctionWhy(), value.correctionExample(), value.fieldsToReviseJson(),
                value.feedback(), PracticePromptCatalog.PROMPT_KEY,
                PracticePromptCatalog.PROMPT_VERSION, ModelAssessmentParser.SCHEMA_VERSION,
                value.modelId(), value.latencyMs(), value.failureReason(), value.createdAt());
    }

    @Transactional
    public boolean saveCompletion(AssessmentRow assessment, String status,
                                  OffsetDateTime completedAt) {
        if (!completeAttempt(assessment.attemptId(), status, completedAt)) return false;
        insertAssessment(assessment);
        return true;
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

    public record AttemptRow(
            UUID id,
            UUID assignmentId,
            UUID ownerId,
            UUID parentAttemptId,
            int attemptNumber,
            String question,
            String answer,
            String reasoning,
            String solution,
            String revisedFieldsJson,
            String status,
            String requestedModel,
            String idempotencyKey,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            String categoryCode,
            String categoryName,
            String domain,
            String situation,
            String guidance,
            String outcome,
            String completenessStatus,
            String stepResultsJson,
            Integer categoryFitScore,
            String categoryFitEvidence,
            String confusedWith,
            Integer questionStrengthScore,
            String strengthDimensionsJson,
            String confidence,
            String strengthsJson,
            String correctionWhat,
            String correctionWhy,
            String correctionExample,
            String fieldsToReviseJson,
            String feedback,
            String modelId,
            String failureReason) {
    }

    public record AssessmentRow(
            UUID id,
            UUID attemptId,
            String outcome,
            String completenessStatus,
            String stepResultsJson,
            Integer categoryFitScore,
            String categoryFitEvidence,
            String confusedWith,
            Integer questionStrengthScore,
            String strengthDimensionsJson,
            String confidence,
            String strengthsJson,
            String correctionWhat,
            String correctionWhy,
            String correctionExample,
            String fieldsToReviseJson,
            String feedback,
            String modelId,
            long latencyMs,
            String failureReason,
            OffsetDateTime createdAt) {
    }
}
