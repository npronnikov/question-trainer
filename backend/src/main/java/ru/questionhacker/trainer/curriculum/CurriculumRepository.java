package ru.questionhacker.trainer.curriculum;

import java.util.List;

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
}
