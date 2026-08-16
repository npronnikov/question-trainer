package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:migration;DB_CLOSE_DELAY=-1"
})
class MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatesBaselineTables() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE UPPER(TABLE_NAME) IN (
                  'CHAT_SESSION',
                  'CHAT_MESSAGE',
                  'GENERATED_SCENARIO',
                  'FLYWAY_SCHEMA_HISTORY'
                )
                """, Integer.class);

        assertThat(count).isEqualTo(4);
    }

    @Test
    void flywayCreatesCurriculumAndTrainerTables() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE UPPER(TABLE_NAME) IN (
                  'CATEGORY',
                  'EVIDENCE_SOURCE',
                  'THEORY_SECTION',
                  'CATEGORY_CONTRAST',
                  'SCENARIO',
                  'SCENARIO_OPTION',
                  'TRAINER_ISSUANCE',
                  'TRAINER_ATTEMPT',
                  'CATEGORY_MASTERY',
                  'CATEGORY_CONFUSION'
                )
                """, Integer.class);

        assertThat(count).isEqualTo(10);
    }

    @Test
    void flywayCreatesVersionedPracticeAssessmentTables() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE UPPER(TABLE_NAME) IN (
                  'PROMPT_VERSION',
                  'PRACTICE_ASSIGNMENT',
                  'PRACTICE_ATTEMPT',
                  'PRACTICE_ASSESSMENT'
                )
                """, Integer.class);

        assertThat(count).isEqualTo(4);
    }

    @Test
    void flywayCreatesStablePracticeCyclesAndIdeaPotentialStorage() {
        Integer assignmentColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME)='PRACTICE_ASSIGNMENT'
                  AND UPPER(COLUMN_NAME) IN (
                    'SEQUENCE_NUMBER', 'CYCLE_NUMBER', 'CYCLE_POSITION'
                  )
                  AND IS_NULLABLE='NO'
                """, Integer.class);
        Integer assessmentColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME)='PRACTICE_ASSESSMENT'
                  AND UPPER(COLUMN_NAME) IN (
                    'IDEA_POTENTIAL_SCORE', 'IDEA_POTENTIAL_DIMENSIONS_JSON'
                  )
                """, Integer.class);

        assertThat(assignmentColumns).isEqualTo(3);
        assertThat(assessmentColumns).isEqualTo(2);
    }

    @Test
    void flywayCreatesScenarioModerationTables() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                WHERE UPPER(TABLE_NAME) IN ('SCENARIO_CANDIDATE', 'MODERATION_ACTION')
                """, Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void flywayCreatesPracticeDraftsAndOneExamplePerCategory() {
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                WHERE UPPER(TABLE_NAME) IN ('PRACTICE_DRAFT', 'PRACTICE_EXAMPLE')
                """, Integer.class);

        assertThat(tables).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM practice_example", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT category_code) FROM practice_example", Integer.class))
                .isEqualTo(7);
    }

    @Test
    void flywayCreatesCanonicalThreeFieldPracticeContent() {
        Integer rationaleColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) IN (
                  'PRACTICE_ATTEMPT', 'PRACTICE_DRAFT', 'PRACTICE_EXAMPLE'
                ) AND UPPER(COLUMN_NAME)='RATIONALE_TEXT'
                """, Integer.class);

        assertThat(rationaleColumns).isEqualTo(3);

        Integer requiredRationaleColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) IN (
                  'PRACTICE_ATTEMPT', 'PRACTICE_DRAFT', 'PRACTICE_EXAMPLE'
                ) AND UPPER(COLUMN_NAME)='RATIONALE_TEXT' AND IS_NULLABLE='NO'
                """, Integer.class);
        assertThat(requiredRationaleColumns).isEqualTo(3);

        Integer legacyColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) IN (
                  'PRACTICE_ATTEMPT', 'PRACTICE_DRAFT', 'PRACTICE_EXAMPLE'
                ) AND UPPER(COLUMN_NAME) IN ('ANSWER_TEXT', 'REASONING_TEXT')
                """, Integer.class);
        assertThat(legacyColumns).isZero();
    }

    @Test
    void promptCatalogKeepsLegacySchemasAndActivatesIdeaPotentialSchema() {
        assertThat(jdbc.queryForList("""
                SELECT schema_version FROM prompt_version
                WHERE prompt_key='practice-assessment'
                ORDER BY version_number
                """, String.class))
                .containsExactly(
                        "practice-assessment-v1",
                        "practice-assessment-v2",
                        "practice-assessment-v3");
        assertThat(jdbc.queryForObject("""
                SELECT schema_version FROM prompt_version
                WHERE prompt_key='practice-assessment' AND active=TRUE
                """, String.class)).isEqualTo("practice-assessment-v3");
    }

    @Test
    void flywayTargetsExistingScenariosAtTrainerAndAddsPracticeHintSnapshots() {
        Integer columns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE (UPPER(TABLE_NAME)='SCENARIO' AND UPPER(COLUMN_NAME) IN ('CONTENT_TARGET', 'HINT_TEXT'))
                   OR (UPPER(TABLE_NAME)='SCENARIO_CANDIDATE' AND UPPER(COLUMN_NAME)='CONTENT_TARGET')
                   OR (UPPER(TABLE_NAME)='PRACTICE_ASSIGNMENT' AND UPPER(COLUMN_NAME)='HINT_TEXT')
                """, Integer.class);

        assertThat(columns).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario WHERE content_target <> 'TRAINER'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_candidate WHERE content_target <> 'TRAINER'", Integer.class))
                .isZero();
    }
}
