package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class GeneratedQuestionV4ImportIntegrationTest {
    private static final String REVISED =
            "fixtures/question-handoff/generated-matrix-v4-rev1.json";
    private static final String FIRST_PASS =
            "fixtures/question-handoff/generated-matrix-v4-first-pass.json";
    private static final String REVIEWS =
            "fixtures/question-handoff/reviews-linear-algebra-display-grounded.jsonl";
    private static final String GROUNDING =
            "fixtures/question-handoff/grounding-matrix-v2.json";
    private static final String REVISED_ID = "gq_66f3360464d1336ec1612715ebbdd3ed";
    private static final String FIRST_PASS_ID = "gq_c0e6f6c774e467bcb8ab2c10a4cd9379";
    private static final String DISPLAY_HASH =
            "sha256:c5126e3650a7c2329f2a4281efc774465bb9954e19091808d7162938926850a4";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @Autowired ApprovedQuestionImportService importer;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ensureLinearAlgebraTopic() {
        jdbc.update("""
                insert into backend.topic(code,name,ml_topic_id)
                values ('LA-V4-TEST','Linear Algebra v4 contract','linear-algebra')
                on conflict (code) do nothing
                """);
    }

    @Test
    void importsApprovedV4WithExactGroundingAndIsIdempotent() throws Exception {
        String generated = fixture(REVISED);
        String reviews = fixture(REVIEWS);
        String grounding = fixture(GROUNDING);

        assertThat(fileHash(GROUNDING)).isEqualTo(
                "a9d7f5f1b1487040c6aff681fbfe8591800a274aa0b5e2231cf784ea8c1fe2a6");
        assertThat(fileHash(REVISED)).isEqualTo(
                "ea384ab3f3098018003a4c12cd79397ece4f35b169bd7a269bde17ff33b291dd");

        ApprovedQuestionImportService.ImportResult first =
                importer.importApproved(
                        fixturePath(REVISED), fixturePath(REVIEWS), fixturePath(GROUNDING));
        ApprovedQuestionImportService.ImportResult second =
                importer.importApproved(generated, reviews, grounding);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.questionId()).isEqualTo(first.questionId());
        assertThat(jdbc.queryForMap("""
                select measurement_area, difficulty, passage, prompt, choices,
                       correct_choice_index, explanation, version, generated_question_id,
                       generated_content_hash,
                       upstream_provenance->>'groundingVersion' as grounding_version,
                       upstream_provenance->>'displayPassageHash' as display_hash,
                       upstream_provenance#>>'{groundingSource,provider}' as provider,
                       upstream_provenance#>>'{humanReview,status}' as review_status
                from backend.question where id=?
                """, first.questionId()))
                .containsEntry("measurement_area", "COMPREHENSION")
                .containsEntry("difficulty", 2)
                .containsEntry("passage", passage(generated))
                .containsEntry(
                        "prompt",
                        "제시된 본문의 정의와 규칙을 적용할 때, 4개의 행과 3개의 열로 이루어진 수 배열에 대한 올바른 행렬 크기 표기 및 설명으로 가장 적절한 것은?")
                .containsEntry("correct_choice_index", 3)
                .containsEntry("version", "generated-question-v4")
                .containsEntry("generated_question_id", REVISED_ID)
                .containsEntry(
                        "generated_content_hash",
                        "sha256:30a3fe232e96d0fe3a2c57c8d77f47c57162323d8cca2e790b9e22a38a0dcf22")
                .containsEntry("grounding_version", "generation-grounding-v2")
                .containsEntry("display_hash", DISPLAY_HASH)
                .containsEntry("provider", "hefferon")
                .containsEntry("review_status", "approve");
        assertThat(jdbc.queryForObject(
                "select jsonb_array_length(choices) from backend.question where id=?",
                Integer.class,
                first.questionId())).isEqualTo(4);

        String changed = generated.replace(
                "stated된다.", "명시된다.");
        assertThatThrownBy(() -> importer.importApproved(changed, reviews, grounding))
                .isInstanceOf(QuestionImportException.class);
    }

    @Test
    void enforcesTheCanonicalHumanReviewLifecycle() throws Exception {
        String reviews = fixture(REVIEWS);
        String grounding = fixture(GROUNDING);
        String firstPass = fixture(FIRST_PASS);
        String revised = fixture(REVISED);

        assertThatThrownBy(() -> importer.importApproved(firstPass, reviews, grounding))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("status=approve");

        String approvedReview = matchingReview(reviews, REVISED_ID);
        assertRejectedReview(revised, "", grounding, "exactly one");
        assertRejectedReview(
                revised,
                approvedReview + "\n" + approvedReview,
                grounding,
                "exactly one");
        assertRejectedReview(
                revised,
                approvedReview.replace("\"status\":\"approve\"", "\"status\":\"reject\""),
                grounding,
                "status=approve");
        assertRejectedReview(
                revised,
                approvedReview.replace("\"correct\":true", "\"correct\":false"),
                grounding,
                "correct=true");
        assertThat(matchingReview(reviews, FIRST_PASS_ID)).contains("needs_revision");
    }

    @Test
    void rejectsMissingOrMismatchedGrounding() throws Exception {
        String generated = fixture(REVISED);
        String reviews = fixture(REVIEWS);
        String grounding = fixture(GROUNDING);

        assertRejected(generated, reviews, null);
        assertRejected(generated, reviews, grounding + "\n");
        assertRejected(generated, reviews, grounding.replace(
                "\"grounding_version\": \"generation-grounding-v2\"",
                "\"grounding_version\": \"generation-grounding-v1\""));
        assertRejected(generated, reviews, grounding.replace(
                "q_375e5b6bef551015f67c", "q_aaaaaaaaaaaaaaaaaaaa"));
        assertRejected(generated, reviews, grounding.replace(
                "doc_de934d33d551223812e8", "doc_aaaaaaaaaaaaaaaaaaaa"));
        assertRejected(generated, reviews, grounding.replace(
                "sha256:7220ee5501766f97edabc506e871470356fa2aeb40ec92acfd6f7e44d9ce4ce0",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertRejected(generated, reviews, grounding.replace(
                "sha256:c5126e3650a7c2329f2a4281efc774465bb9954e19091808d7162938926850a4",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertRejected(generated, reviews, grounding.replace(
                "pdf-display-normalization-v1", "pdf-display-normalization-v2"));
        assertRejected(generated, reviews, grounding.replace(
                "Definition An m×n", "Definition: An m×n"));
        assertRejected(generated, reviews, grounding.replace(
                "sha256:c5126e3650a7c2329f2a4281efc774465bb9954e19091808d7162938926850a4",
                "not-a-sha256"));
    }

    @Test
    void dispatchesOnlyStrictV2AndV4Shapes() throws Exception {
        String v2 = fixture("fixtures/question-handoff/generated-process.json");
        String v2Review = fixture("fixtures/question-handoff/reviews.jsonl");
        String v4 = fixture(REVISED);
        String reviews = fixture(REVIEWS);
        String grounding = fixture(GROUNDING);

        assertThat(importer.importApproved(v2, v2Review).generatedQuestionId())
                .isEqualTo("gq_99d2b731a84d35c35ca56650b372d5f2");
        assertThat(importer.importApproved(v4, reviews, grounding).generatedQuestionId())
                .isEqualTo(REVISED_ID);
        assertRejected(
                v4.replace("generated-question-v4", "generated-question-v3"), reviews, grounding);
        assertRejected(
                v4.replace("generated-question-v4", "generated-question-v5"), reviews, grounding);
        assertRejected(v2, v2Review, grounding);
        assertThatThrownBy(() -> importer.importApproved(
                v2.replace("\"stem\":", "\"passage\": \"unexpected\",\n  \"stem\":"),
                v2Review))
                .isInstanceOf(QuestionImportException.class);
        assertThatThrownBy(() -> importer.importApproved(
                v2.replace("generated-question-v2", "generated-question-v4"),
                v2Review,
                grounding))
                .isInstanceOf(QuestionImportException.class);
    }

    private void assertRejectedReview(
            String generated,
            String reviews,
            String grounding,
            String message) {
        assertThatThrownBy(() -> importer.importApproved(generated, reviews, grounding))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining(message);
    }

    private void assertRejected(String generated, String reviews, String grounding) {
        assertThatThrownBy(() -> importer.importApproved(generated, reviews, grounding))
                .isInstanceOf(QuestionImportException.class);
    }

    private String matchingReview(String reviews, String id) {
        return reviews.lines().filter(line -> line.contains(id)).findFirst().orElseThrow();
    }

    private String passage(String generated) throws Exception {
        return new tools.jackson.databind.ObjectMapper()
                .readTree(generated).path("passage").asString();
    }

    private String fixture(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private Path fixturePath(String path) throws Exception {
        return new ClassPathResource(path).getFile().toPath();
    }

    private String fileHash(String path) throws Exception {
        byte[] bytes = new ClassPathResource(path).getContentAsByteArray();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
