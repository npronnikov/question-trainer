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
}
