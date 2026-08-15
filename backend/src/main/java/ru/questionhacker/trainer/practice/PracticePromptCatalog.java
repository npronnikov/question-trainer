package ru.questionhacker.trainer.practice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PracticePromptCatalog implements ApplicationRunner {

    public static final String PROMPT_KEY = "practice-assessment";
    public static final int PROMPT_VERSION = 2;

    private final JdbcTemplate jdbc;
    private final String template;
    private final String legacyTemplate;

    public PracticePromptCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.template = read("prompts/practice-assessment-v2.md");
        this.legacyTemplate = read("prompts/practice-assessment-v1.md");
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("""
                MERGE INTO prompt_version(
                  prompt_key, version_number, schema_version, content_hash, active, created_at
                ) KEY(prompt_key, version_number) VALUES (?, 1, ?, ?, FALSE, ?)
                """, PROMPT_KEY, ModelAssessmentParser.SCHEMA_VERSION,
                sha256(legacyTemplate), OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("""
                MERGE INTO prompt_version(
                  prompt_key, version_number, schema_version, content_hash, active, created_at
                ) KEY(prompt_key, version_number) VALUES (?, ?, ?, ?, TRUE, ?)
                """, PROMPT_KEY, PROMPT_VERSION, ModelAssessmentV2Parser.SCHEMA_VERSION,
                sha256(template), OffsetDateTime.now(ZoneOffset.UTC));
    }

    public String render(PracticeAssessmentGateway.Input input) {
        return template
                .replace("{{situation}}", input.situation())
                .replace("{{category}}", input.category())
                .replace("{{guidance}}", input.guidance())
                .replace("{{question}}", input.question())
                .replace("{{rationale}}", input.rationale())
                .replace("{{solution}}", input.solution());
    }

    private static String read(String location) {
        try (var input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load assessment prompt", error);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
