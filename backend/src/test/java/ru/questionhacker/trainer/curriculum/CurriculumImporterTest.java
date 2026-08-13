package ru.questionhacker.trainer.curriculum;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "app.acp.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:curriculum-import;DB_CLOSE_DELAY=-1"
})
class CurriculumImporterTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CurriculumImporter importer;

    @Test
    void importsSevenCategoriesAndExactlyNinetyEightReviewedCards() {
        assertThat(count("category")).isEqualTo(7);
        assertThat(count("scenario")).isEqualTo(98);
        assertThat(jdbc.queryForList("""
                SELECT category_code, COUNT(*) AS card_count
                FROM scenario
                GROUP BY category_code
                ORDER BY category_code
                """))
                .allSatisfy(row -> assertThat(row.get("CARD_COUNT")).isEqualTo(14L))
                .hasSize(7);
    }

    @Test
    void mapsFuturismToBackcastingAndRequiresContrastMetadataForL3() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE code='BACKCASTING'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE code='FUTURISM'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM scenario
                WHERE difficulty='L3'
                  AND (confused_with IS NULL OR contrast_explanation IS NULL)
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario WHERE difficulty='L3'", Integer.class)).isEqualTo(21);
    }

    @Test
    void exposesExplicitEvidenceGrades() {
        assertThat(count("evidence_source")).isGreaterThan(0);
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT evidence_grade FROM evidence_source", String.class))
                .isNotEmpty()
                .allMatch(grade -> grade.equals("RESEARCH_SUPPORTED")
                        || grade.equals("PRACTITIONER_METHOD")
                        || grade.equals("HEURISTIC"));
    }

    @Test
    void importsAppliedTheoryForEveryCategory() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM category
                WHERE worked_example_json IS NOT NULL
                  AND question_templates_json IS NOT NULL
                  AND quick_exercise_text IS NOT NULL
                  AND experiment_text IS NOT NULL
                  AND historical_cases_json IS NOT NULL
                """, Integer.class)).isEqualTo(7);
    }

    @Test
    void repeatedImportIsIdempotent() throws Exception {
        importer.run(new DefaultApplicationArguments(new String[0]));

        assertThat(count("category")).isEqualTo(7);
        assertThat(count("scenario")).isEqualTo(98);
        assertThat(count("scenario_option")).isEqualTo(392);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
