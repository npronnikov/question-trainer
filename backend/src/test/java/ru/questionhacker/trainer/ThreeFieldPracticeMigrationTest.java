package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ThreeFieldPracticeMigrationTest {

    @Test
    void migrationCombinesLegacyAnswerAndReasoningWithoutDataLoss() {
        String url = "jdbc:h2:mem:practice-migration-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource)
                .target(MigrationVersion.fromVersion("10"))
                .load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO category(
                  code, sort_order, display_number, name, nickname, operation_text,
                  signal_text, when_text, definition_text, mechanism_text,
                  formula_json, examples_json, mistake_text, cue_text,
                  strength_anchors_json
                ) VALUES ('INVERSION', 1, '01', 'Инверсия', 'Инверсия', 'Операция',
                          'Сигнал', 'Когда', 'Определение', 'Механизм',
                          '[]', '[]', 'Ошибка', 'Подсказка', '[]')
                """);
        UUID exampleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO practice_example(
                  id, category_code, domain_text, situation_text, question_text,
                  answer_text, reasoning_text, solution_text, recommendation_text,
                  published, created_at
                ) VALUES (?, 'INVERSION', 'Домен', 'Ситуация', 'Вопрос',
                          'Старый ответ', 'Старое рассуждение', 'Решение', 'Совет',
                          TRUE, CURRENT_TIMESTAMP)
                """, exampleId);

        var legacy = jdbc.queryForMap("""
                SELECT id, answer_text, reasoning_text
                FROM practice_example WHERE id=?
                """, exampleId);

        Flyway.configure().dataSource(dataSource).load().migrate();

        String rationale = jdbc.queryForObject(
                "SELECT rationale_text FROM practice_example WHERE id=?",
                String.class, legacy.get("id"));
        assertThat(rationale).isEqualTo("Ответ на вопрос:\n" + legacy.get("answer_text")
                + "\n\nХод рассуждения:\n" + legacy.get("reasoning_text"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME)='PRACTICE_EXAMPLE'
                  AND UPPER(COLUMN_NAME) IN ('ANSWER_TEXT', 'REASONING_TEXT')
                """, Integer.class)).isZero();
    }
}
