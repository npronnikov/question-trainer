package ru.questionhacker.trainer.curriculum;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CurriculumRepository {

    private final JdbcTemplate jdbc;

    public CurriculumRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int categoryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class);
    }

    public int publishedScenarioCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM scenario WHERE published=TRUE", Integer.class);
    }

    public List<String> categoryCodes() {
        return jdbc.queryForList("SELECT code FROM category ORDER BY sort_order", String.class);
    }

    public List<CategoryRow> listCategories() {
        return jdbc.query("""
                SELECT code, display_number, name, nickname, operation_text, signal_text,
                       when_text, definition_text, mechanism_text, formula_json, examples_json,
                       worked_example_json, question_templates_json, quick_exercise_text,
                       experiment_text, historical_cases_json, mistake_text, cue_text,
                       strength_anchors_json
                FROM category
                ORDER BY sort_order
                """, (rs, row) -> categoryRow(rs));
    }

    public Optional<CategoryRow> findCategory(String code) {
        return jdbc.query("""
                SELECT code, display_number, name, nickname, operation_text, signal_text,
                       when_text, definition_text, mechanism_text, formula_json, examples_json,
                       worked_example_json, question_templates_json, quick_exercise_text,
                       experiment_text, historical_cases_json, mistake_text, cue_text,
                       strength_anchors_json
                FROM category
                WHERE code=?
                """, (rs, row) -> categoryRow(rs), code).stream().findFirst();
    }

    public List<TheorySectionRow> listTheorySections(String categoryCode) {
        return jdbc.query("""
                SELECT ts.section_key, ts.title, ts.content_text, ts.evidence_grade,
                       es.source_key, es.title AS source_title, es.source_url,
                       es.supports_text, es.evidence_grade AS source_grade
                FROM theory_section ts
                LEFT JOIN evidence_source es ON es.source_key=ts.source_key
                WHERE ts.category_code=?
                ORDER BY ts.sort_order
                """, (rs, row) -> new TheorySectionRow(
                rs.getString("section_key"), rs.getString("title"),
                rs.getString("content_text"), rs.getString("evidence_grade"),
                rs.getString("source_key"), rs.getString("source_title"),
                rs.getString("source_url"), rs.getString("supports_text"),
                rs.getString("source_grade")), categoryCode);
    }

    public List<ContrastRow> listContrasts(String categoryCode) {
        return jdbc.query("""
                SELECT cc.other_category_code, c.name AS other_name, cc.contrast_text
                FROM category_contrast cc
                JOIN category c ON c.code=cc.other_category_code
                WHERE cc.category_code=?
                ORDER BY c.sort_order
                """, (rs, row) -> new ContrastRow(
                rs.getString("other_category_code"), rs.getString("other_name"),
                rs.getString("contrast_text")), categoryCode);
    }

    public List<EvidenceSourceRow> listEvidenceSources() {
        return jdbc.query("""
                SELECT source_key, title, source_url, supports_text, evidence_grade
                FROM evidence_source
                ORDER BY source_key
                """, (rs, row) -> new EvidenceSourceRow(
                rs.getString("source_key"), rs.getString("title"),
                rs.getString("source_url"), rs.getString("supports_text"),
                rs.getString("evidence_grade")));
    }

    private static CategoryRow categoryRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CategoryRow(
                rs.getString("code"), rs.getString("display_number"), rs.getString("name"),
                rs.getString("nickname"), rs.getString("operation_text"),
                rs.getString("signal_text"), rs.getString("when_text"),
                rs.getString("definition_text"), rs.getString("mechanism_text"),
                rs.getString("formula_json"), rs.getString("examples_json"),
                rs.getString("worked_example_json"), rs.getString("question_templates_json"),
                rs.getString("quick_exercise_text"), rs.getString("experiment_text"),
                rs.getString("historical_cases_json"),
                rs.getString("mistake_text"), rs.getString("cue_text"),
                rs.getString("strength_anchors_json"));
    }

    public record CategoryRow(
            String code,
            String number,
            String name,
            String nickname,
            String operation,
            String signal,
            String when,
            String definition,
            String mechanism,
            String formulaJson,
            String examplesJson,
            String workedExampleJson,
            String questionTemplatesJson,
            String quickExercise,
            String experiment,
            String historicalCasesJson,
            String mistake,
            String cue,
            String strengthAnchorsJson) {
    }

    public record TheorySectionRow(
            String key,
            String title,
            String content,
            String evidenceGrade,
            String sourceKey,
            String sourceTitle,
            String sourceUrl,
            String sourceSupports,
            String sourceGrade) {
    }

    public record ContrastRow(String otherCategory, String otherName, String text) {
    }

    public record EvidenceSourceRow(
            String key, String title, String url, String supports, String evidenceGrade) {
    }
}
