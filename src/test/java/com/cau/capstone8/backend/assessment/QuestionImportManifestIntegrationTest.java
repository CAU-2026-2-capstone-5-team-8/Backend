package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers
class QuestionImportManifestIntegrationTest {
    private static final String FIXTURES = "fixtures/question-handoff/";
    private static final String V2_ID = "gq_99d2b731a84d35c35ca56650b372d5f2";
    private static final String V4_ID = "gq_66f3360464d1336ec1612715ebbdd3ed";
    private static final String V2_ENTRY = """
            {"generated_path": "generated-process.json", "reviews_path": "reviews.jsonl"}""";
    private static final String V4_ENTRY = """
            {"generated_path": "generated-matrix-v4-rev1.json",
             "reviews_path": "reviews-linear-algebra-display-grounded.jsonl",
             "grounding_path": "grounding-matrix-v2.json"}""";
    private static final String NEEDS_REVISION_ENTRY = """
            {"generated_path": "generated-matrix-v4-first-pass.json",
             "reviews_path": "reviews-linear-algebra-display-grounded.jsonl",
             "grounding_path": "grounding-matrix-v2.json"}""";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @Autowired ApprovedQuestionImportService importer;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path handoff;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.update("""
                insert into backend.topic(code,name,ml_topic_id)
                values ('LA-MANIFEST-TEST','Linear Algebra manifest contract','linear-algebra')
                on conflict (code) do nothing
                """);
        jdbc.update(
                "delete from backend.question where generated_question_id in (?, ?)",
                V2_ID,
                V4_ID);
        for (String name : List.of(
                "generated-process.json",
                "reviews.jsonl",
                "generated-matrix-v4-rev1.json",
                "generated-matrix-v4-first-pass.json",
                "reviews-linear-algebra-display-grounded.jsonl",
                "grounding-matrix-v2.json")) {
            Files.write(
                    handoff.resolve(name),
                    new ClassPathResource(FIXTURES + name).getContentAsByteArray());
        }
    }

    @Test
    void importsEveryListedEntryRelativeToTheManifestAndIsIdempotent() throws Exception {
        Path manifest = manifest(V2_ENTRY, V4_ENTRY);

        List<ApprovedQuestionImportService.ImportResult> first = importer.importManifest(manifest);
        List<ApprovedQuestionImportService.ImportResult> second = importer.importManifest(manifest);

        assertThat(first)
                .extracting(ApprovedQuestionImportService.ImportResult::generatedQuestionId)
                .containsExactly(V2_ID, V4_ID);
        assertThat(first).allMatch(ApprovedQuestionImportService.ImportResult::created);
        assertThat(second).noneMatch(ApprovedQuestionImportService.ImportResult::created);
        assertThat(second)
                .extracting(ApprovedQuestionImportService.ImportResult::questionId)
                .containsExactlyElementsOf(first.stream()
                        .map(ApprovedQuestionImportService.ImportResult::questionId)
                        .toList());
        assertThat(importedCount()).isEqualTo(2);
    }

    @Test
    void rollsBackTheWholeBatchWhenAnyEntryIsRejected() throws Exception {
        Path manifest = manifest(V2_ENTRY, NEEDS_REVISION_ENTRY);

        assertThatThrownBy(() -> importer.importManifest(manifest))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("manifest entry 1 (generated-matrix-v4-first-pass.json)")
                .hasMessageContaining("status=approve");
        assertThat(importedCount()).isZero();
    }

    @Test
    void rejectsMalformedManifests() throws Exception {
        assertRejected("{\"manifest_version\": \"question-import-manifest-v2\", \"entries\": ["
                + V2_ENTRY + "]}", "manifest_version");
        assertRejected("{\"manifest_version\": \"question-import-manifest-v1\", \"entries\": []}",
                "must not be empty");
        assertRejected(manifestJson(V2_ENTRY, V2_ENTRY), "more than once");
        assertRejected(manifestJson(
                "{\"generated_path\": \"generated-process.json\"}"), "reviews_path");
        assertRejected(manifestJson(V2_ENTRY.replace("}", ", \"grounding_path\": \" \"}")),
                "reviews_path");
        assertRejected(manifestJson(V2_ENTRY.replace("}", ", \"extra\": 1}")), "malformed");
        assertRejected(manifestJson(V2_ENTRY) + "{}", "malformed");
        assertRejected(manifestJson(V2_ENTRY.replace("reviews.jsonl", "missing.jsonl")),
                "manifest entry 0");
        assertThat(importedCount()).isZero();
    }

    private void assertRejected(String json, String message) throws Exception {
        Path manifest = handoff.resolve("manifest.json");
        Files.writeString(manifest, json);
        assertThatThrownBy(() -> importer.importManifest(manifest))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining(message);
    }

    private Path manifest(String... entries) throws Exception {
        return Files.writeString(handoff.resolve("manifest.json"), manifestJson(entries));
    }

    private static String manifestJson(String... entries) {
        return "{\"manifest_version\": \"question-import-manifest-v1\", \"entries\": ["
                + String.join(",", entries) + "]}";
    }

    private int importedCount() {
        return jdbc.queryForObject(
                "select count(*) from backend.question where generated_question_id in (?, ?)",
                Integer.class,
                V2_ID,
                V4_ID);
    }
}
