package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PracticeIdeaPotentialMigrationTest {

    @Test
    void migrationBackfillsOwnerLocalCycleCoordinatesDeterministically() {
        String url = "jdbc:h2:mem:idea-cycle-migration-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource)
                .target(MigrationVersion.fromVersion("12"))
                .load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        insertCategory(jdbc);
        UUID alice = insertUser(jdbc, "migration-alice");
        UUID bob = insertUser(jdbc, "migration-bob");
        OffsetDateTime tied = OffsetDateTime.of(2026, 8, 16, 0, 0, 0, 0, ZoneOffset.UTC);
        for (int index = 0; index < 8; index++) insertAssignment(jdbc, alice, tied);
        for (int index = 0; index < 2; index++) insertAssignment(jdbc, bob, tied);

        Flyway.configure().dataSource(dataSource).load().migrate();

        List<Long> aliceSequence = jdbc.queryForList("""
                SELECT sequence_number FROM practice_assignment
                WHERE owner_id=? ORDER BY sequence_number
                """, Long.class, alice);
        assertThat(aliceSequence).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(jdbc.queryForList("""
                SELECT cycle_number FROM practice_assignment
                WHERE owner_id=? ORDER BY sequence_number
                """, Integer.class, alice)).containsExactly(1, 1, 1, 1, 1, 1, 1, 2);
        assertThat(jdbc.queryForList("""
                SELECT cycle_position FROM practice_assignment
                WHERE owner_id=? ORDER BY sequence_number
                """, Integer.class, alice)).containsExactly(1, 2, 3, 4, 5, 6, 7, 1);
        assertThat(jdbc.queryForList("""
                SELECT sequence_number FROM practice_assignment
                WHERE owner_id=? ORDER BY sequence_number
                """, Long.class, bob)).containsExactly(1L, 2L);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO practice_assignment(
                  id, owner_id, target_category_code, domain_text, situation_text,
                  guidance_text, sequence_number, cycle_number, cycle_position, created_at
                ) VALUES (?, ?, 'INVERSION', 'Домен', 'Ситуация', 'Ориентир',
                          1, 1, 1, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), alice))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertCategory(JdbcTemplate jdbc) {
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
    }

    private UUID insertUser(JdbcTemplate jdbc, String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(
                  id, username, normalized_username, password_hash,
                  system_account, enabled, created_at, updated_at
                ) VALUES (?, ?, ?, '$2a$test', FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, username, username);
        return id;
    }

    private void insertAssignment(JdbcTemplate jdbc, UUID ownerId, OffsetDateTime createdAt) {
        jdbc.update("""
                INSERT INTO practice_assignment(
                  id, owner_id, target_category_code, domain_text,
                  situation_text, guidance_text, created_at
                ) VALUES (?, ?, 'INVERSION', 'Домен', 'Ситуация', 'Ориентир', ?)
                """, UUID.randomUUID(), ownerId, createdAt);
    }
}
