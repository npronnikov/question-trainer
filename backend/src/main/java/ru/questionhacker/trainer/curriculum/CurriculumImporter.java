package ru.questionhacker.trainer.curriculum;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CurriculumImporter implements ApplicationRunner {

    private static final Set<String> CATEGORY_CODES = Set.of(
            "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
            "PROVOCATION", "REFRAMING", "SIMPLIFICATION");
    private static final Set<String> DIFFICULTIES = Set.of("L1", "L2", "L3");
    private static final Set<String> EVIDENCE_GRADES = Set.of(
            "RESEARCH_SUPPORTED", "PRACTITIONER_METHOD", "HEURISTIC");
    private static final Set<String> CASE_CLASSIFICATIONS = Set.of(
            "explicit", "research-interpretation");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CurriculumImporter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        JsonNode curriculum = read("curriculum/categories.json");
        JsonNode scenarios = read("curriculum/scenarios.json");
        validate(curriculum, scenarios);
        importCategories(curriculum.path("categories"));
        importSources(curriculum.path("sources"));
        importTheoryAndContrasts(curriculum.path("categories"));
        importScenarios(scenarios.path("scenarios"));
    }

    private JsonNode read(String resource) throws IOException {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return json.readTree(input);
        }
    }

    private void validate(JsonNode curriculum, JsonNode scenarioDocument) {
        if (curriculum.path("schemaVersion").asInt() != 1
                || scenarioDocument.path("schemaVersion").asInt() != 1) {
            throw new IllegalStateException("Unsupported curriculum schema version");
        }

        Set<String> sourceKeys = new HashSet<>();
        JsonNode sources = curriculum.path("sources");
        if (!sources.isArray() || sources.isEmpty()) {
            throw new IllegalStateException("Curriculum requires evidence sources");
        }
        for (JsonNode source : sources) {
            String key = required(source, "key");
            if (!sourceKeys.add(key)) throw new IllegalStateException("Duplicate source key: " + key);
            required(source, "title");
            if (!EVIDENCE_GRADES.contains(required(source, "grade"))) {
                throw new IllegalStateException("Unknown evidence grade");
            }
        }

        JsonNode categories = curriculum.path("categories");
        if (!categories.isArray() || categories.size() != 7) {
            throw new IllegalStateException("Curriculum must contain exactly seven categories");
        }
        Set<String> codes = new HashSet<>();
        for (JsonNode category : categories) {
            String code = required(category, "code");
            codes.add(code);
            required(category, "name");
            required(category, "operation");
            if (!category.path("strengthAnchors").isArray() || category.path("strengthAnchors").size() < 2) {
                throw new IllegalStateException(code + " requires strength anchors");
            }
            validateAppliedTheory(category, code, sourceKeys);
        }
        if (!codes.equals(CATEGORY_CODES)) {
            throw new IllegalStateException("Unexpected category set: " + codes);
        }

        JsonNode scenarios = scenarioDocument.path("scenarios");
        if (!scenarios.isArray() || scenarios.size() != 98) {
            throw new IllegalStateException("Curriculum must contain exactly 98 scenarios");
        }
        Set<String> keys = new HashSet<>();
        Map<String, Map<String, Integer>> distribution = new HashMap<>();
        for (JsonNode scenario : scenarios) {
            String key = required(scenario, "key");
            if (!keys.add(key)) throw new IllegalStateException("Duplicate scenario key: " + key);
            String category = required(scenario, "category");
            String difficulty = required(scenario, "difficulty");
            if (!CATEGORY_CODES.contains(category) || !DIFFICULTIES.contains(difficulty)) {
                throw new IllegalStateException("Invalid scenario classification: " + key);
            }
            required(scenario, "domain");
            required(scenario, "situation");
            required(scenario, "question");
            required(scenario, "explanation");
            if ("L3".equals(difficulty)) {
                String confusedWith = required(scenario, "confusedWith");
                required(scenario, "contrast");
                if (!CATEGORY_CODES.contains(confusedWith) || confusedWith.equals(category)) {
                    throw new IllegalStateException("Invalid L3 confusion pair: " + key);
                }
            }
            distribution.computeIfAbsent(category, ignored -> new HashMap<>())
                    .merge(difficulty, 1, Integer::sum);
        }
        for (String code : CATEGORY_CODES) {
            Map<String, Integer> levels = distribution.getOrDefault(code, Map.of());
            if (levels.getOrDefault("L1", 0) != 8
                    || levels.getOrDefault("L2", 0) != 3
                    || levels.getOrDefault("L3", 0) != 3) {
                throw new IllegalStateException("Invalid card distribution for " + code + ": " + levels);
            }
        }
    }

    private void validateAppliedTheory(JsonNode category, String code, Set<String> sourceKeys) {
        JsonNode example = category.path("workedExample");
        required(example, "title");
        required(example, "situation");
        required(example, "ordinaryQuestion");
        required(example, "hackerQuestion");
        required(example, "solution");
        required(example, "whyItFits");
        JsonNode reasoningSteps = array(example, "reasoningSteps", 3, 5, code);
        for (JsonNode step : reasoningSteps) {
            required(step, "label");
            required(step, "text");
        }

        JsonNode confusion = example.path("confusion");
        String otherCategory = required(confusion, "otherCategory");
        required(confusion, "explanation");
        if (!CATEGORY_CODES.contains(otherCategory) || code.equals(otherCategory)) {
            throw new IllegalStateException(code + " has invalid worked-example confusion: " + otherCategory);
        }

        for (JsonNode template : array(category, "questionTemplates", 2, Integer.MAX_VALUE, code)) {
            required(template, "domain");
            required(template, "question");
        }
        required(category, "quickExercise");
        required(category, "experiment");

        JsonNode cases = array(category, "cases", 3, 3, code);
        for (JsonNode item : cases) {
            String slug = required(item, "slug");
            for (String field : List.of(
                    "title", "actor", "period", "originalFrame", "frameShift", "action",
                    "outcome", "whyItFits", "limitations")) {
                required(item, field);
            }
            String classification = required(item, "classification");
            if (!CASE_CLASSIFICATIONS.contains(classification)) {
                throw new IllegalStateException(code + " case " + slug + " has invalid classification");
            }
            JsonNode caseSources = array(item, "sourceIds", 1, Integer.MAX_VALUE, code + "/" + slug);
            for (JsonNode sourceId : caseSources) {
                if (!sourceId.isTextual() || !sourceKeys.contains(sourceId.asText())) {
                    throw new IllegalStateException(code + " case " + slug
                            + " references unknown source: " + sourceId.asText());
                }
            }
        }
    }

    private static JsonNode array(
            JsonNode parent, String field, int minimum, int maximum, String context) {
        JsonNode value = parent.path(field);
        if (!value.isArray() || value.size() < minimum || value.size() > maximum) {
            throw new IllegalStateException(context + " requires " + field
                    + " size between " + minimum + " and " + maximum);
        }
        return value;
    }

    private void importCategories(JsonNode categories) throws IOException {
        for (JsonNode category : categories) {
            jdbc.update("""
                    MERGE INTO category(
                      code, sort_order, display_number, name, nickname, operation_text,
                      signal_text, when_text, definition_text, mechanism_text, formula_json,
                      examples_json, worked_example_json, question_templates_json,
                      quick_exercise_text, experiment_text, historical_cases_json,
                      mistake_text, cue_text, strength_anchors_json
                    ) KEY(code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    category.path("code").asText(), category.path("sortOrder").asInt(),
                    category.path("number").asText(), category.path("name").asText(),
                    category.path("nickname").asText(), category.path("operation").asText(),
                    category.path("signal").asText(), category.path("when").asText(),
                    category.path("definition").asText(), category.path("mechanism").asText(),
                    json.writeValueAsString(category.path("formula")),
                    json.writeValueAsString(category.path("examples")),
                    json.writeValueAsString(category.path("workedExample")),
                    json.writeValueAsString(category.path("questionTemplates")),
                    category.path("quickExercise").asText(), category.path("experiment").asText(),
                    json.writeValueAsString(category.path("cases")),
                    category.path("mistake").asText(), category.path("cue").asText(),
                    json.writeValueAsString(category.path("strengthAnchors")));
        }
    }

    private void importSources(JsonNode sources) {
        for (JsonNode source : sources) {
            jdbc.update("""
                    MERGE INTO evidence_source(
                      source_key, title, source_url, supports_text, evidence_grade
                    ) KEY(source_key) VALUES (?, ?, ?, ?, ?)
                    """, source.path("key").asText(), source.path("title").asText(),
                    nullable(source, "url"), source.path("supports").asText(),
                    source.path("grade").asText());
        }
    }

    private void importTheoryAndContrasts(JsonNode categories) {
        Map<String, String> operations = new HashMap<>();
        for (JsonNode category : categories) {
            String code = category.path("code").asText();
            operations.put(code, category.path("operation").asText());
            int order = 0;
            for (JsonNode section : category.path("sections")) {
                String sectionKey = section.path("key").asText();
                jdbc.update("""
                        MERGE INTO theory_section(
                          id, category_code, section_key, title, content_text,
                          evidence_grade, source_key, sort_order
                        ) KEY(category_code, section_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, stableId("theory:" + code + ":" + sectionKey), code, sectionKey,
                        section.path("title").asText(), section.path("content").asText(),
                        section.path("grade").asText(), nullable(section, "sourceKey"), ++order);
            }
            for (JsonNode contrast : category.path("contrasts")) {
                mergeContrast(code, contrast.path("otherCategory").asText(), contrast.path("text").asText());
            }
        }
        for (String code : CATEGORY_CODES) {
            for (String other : CATEGORY_CODES) {
                if (code.equals(other)) continue;
                Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM category_contrast
                        WHERE category_code=? AND other_category_code=?
                        """, Integer.class, code, other);
                if (count != null && count == 0) {
                    mergeContrast(code, other,
                            operations.get(code) + " В отличие от " + other + ": " + operations.get(other));
                }
            }
        }
    }

    private void mergeContrast(String code, String other, String text) {
        jdbc.update("""
                MERGE INTO category_contrast(
                  category_code, other_category_code, contrast_text
                ) KEY(category_code, other_category_code) VALUES (?, ?, ?)
                """, code, other, text);
    }

    private void importScenarios(JsonNode scenarios) {
        List<String> categoryOrder = List.of(
                "INVERSION", "HYPERBOLE", "CROSS_DISCIPLINE", "BACKCASTING",
                "PROVOCATION", "REFRAMING", "SIMPLIFICATION");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (JsonNode scenario : scenarios) {
            String key = scenario.path("key").asText();
            String category = scenario.path("category").asText();
            UUID id = stableId("scenario:" + key);
            jdbc.update("""
                    MERGE INTO scenario(
                      id, external_key, category_code, difficulty, domain_text,
                      situation_text, question_text, explanation_text, confused_with,
                      contrast_explanation, content_hash, published, created_at, updated_at
                    ) KEY(external_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                    """, id, key, category, scenario.path("difficulty").asText(),
                    scenario.path("domain").asText(), scenario.path("situation").asText(),
                    scenario.path("question").asText(), scenario.path("explanation").asText(),
                    nullable(scenario, "confusedWith"), nullable(scenario, "contrast"),
                    hash(scenario.path("situation").asText() + "\n" + scenario.path("question").asText()),
                    now, now);

            jdbc.update("DELETE FROM scenario_option WHERE scenario_id=?", id);
            LinkedHashSet<String> optionSet = new LinkedHashSet<>();
            optionSet.add(category);
            String confusedWith = nullable(scenario, "confusedWith");
            if (confusedWith != null) optionSet.add(confusedWith);
            int start = categoryOrder.indexOf(category);
            for (int offset = 1; optionSet.size() < 4; offset++) {
                optionSet.add(categoryOrder.get((start + offset) % categoryOrder.size()));
            }
            List<String> options = new ArrayList<>(optionSet);
            Collections.rotate(options, Math.floorMod(key.hashCode(), options.size()));
            for (int index = 0; index < options.size(); index++) {
                jdbc.update("""
                        INSERT INTO scenario_option(scenario_id, category_code, sort_order)
                        VALUES (?, ?, ?)
                        """, id, options.get(index), index + 1);
            }
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalStateException("Missing curriculum field: " + field);
        }
        return value.asText();
    }

    private static String nullable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(UTF_8));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
