package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
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
class ApprovedQuestionImportIntegrationTest {
    private static final String GENERATED_FIXTURE =
            "fixtures/question-handoff/generated-process.json";
    private static final String REVIEWS_FIXTURE =
            "fixtures/question-handoff/reviews.jsonl";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @Autowired ApprovedQuestionImportService importer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void importsOnlyApprovedArtifactWithProvenanceAndIsIdempotent() throws Exception {
        String generated = fixture(GENERATED_FIXTURE);
        String reviews = fixture(REVIEWS_FIXTURE);

        ApprovedQuestionImportService.ImportResult first = importer.importApproved(generated, reviews);
        ApprovedQuestionImportService.ImportResult second = importer.importApproved(generated, reviews);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.questionId()).isEqualTo(first.questionId());
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.question where generated_question_id=?",
                Integer.class,
                first.generatedQuestionId())).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                select answer_mode, question_spec_id, measurement_area, difficulty,
                       choices->>0 as first_choice, correct_choice_index, explanation,
                       generated_content_hash,
                       upstream_provenance->>'promptVersion' as prompt_version,
                       upstream_provenance#>>'{humanReview,status}' as review_status,
                       active
                from backend.question where id=?
                """, first.questionId()))
                .containsEntry("answer_mode", "MULTIPLE_CHOICE")
                .containsEntry("question_spec_id", "q_98d90188c8c61d2d0ff1")
                .containsEntry("measurement_area", "VOCABULARY")
                .containsEntry("difficulty", 1)
                .containsEntry("first_choice", "프로세스(process)")
                .containsEntry("correct_choice_index", 0)
                .containsEntry(
                        "explanation",
                        "프로세스(process)는 실행 중인 프로그램을 뜻하며, 운영체제에서 CPU 시간, 메모리 등의 시스템 자원을 할당받아 작업이 수행되는 기본 단위를 의미합니다.")
                .containsEntry("prompt_version", "question-generation-prompt-v2")
                .containsEntry("review_status", "approve")
                .containsEntry("active", true);
        assertThat(jdbc.queryForObject(
                "select generated_content_hash from backend.question where id=?",
                String.class,
                first.questionId())).isEqualTo(
                        "sha256:93054791477d930f9ed5c24296f93dbccdf1f50a4e72cb822b1385da96df4992");

        String changedStem = generated.replace(
                "기본 단위가 되는 개념은 무엇인가?",
                "기본 단위는 무엇인가?");
        assertThatThrownBy(() -> importer.importApproved(changedStem, reviews))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("different immutable content");
    }

    @Test
    void rejectsMissingDuplicateOrUnapprovedReview() throws Exception {
        String generated = withGeneratedId(
                fixture(GENERATED_FIXTURE), "gq_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String review = withGeneratedId(
                fixture(REVIEWS_FIXTURE), "gq_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        assertThatThrownBy(() -> importer.importApproved(generated, ""))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> importer.importApproved(generated, review + review))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> importer.importApproved(
                generated, review.replace("\"status\":\"approve\"", "\"status\":\"reject\"")))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("status=approve");
        assertThatThrownBy(() -> importer.importApproved(
                generated,
                review.replace("\"status\":\"approve\"", "\"status\":\"needs_revision\"")))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("status=approve");
        assertThatThrownBy(() -> importer.importApproved(
                generated, review.replace("\"correct\":true", "\"correct\":false")))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("correct=true");
        assertThatThrownBy(() -> importer.importApproved(
                generated,
                review.replace(
                        "gq_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "gq_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")))
                .isInstanceOf(QuestionImportException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsMalformedChoicesIndexTypeAndUnknownFields() throws Exception {
        String generated = withGeneratedId(
                fixture(GENERATED_FIXTURE), "gq_cccccccccccccccccccccccccccccccc");
        String review = withGeneratedId(
                fixture(REVIEWS_FIXTURE), "gq_cccccccccccccccccccccccccccccccc");

        assertRejected(generated.replace(
                "\"인터럽트 벡터(interrupt vector)\"",
                "\"프로세스(process)\""), review);
        assertRejected(generated.replace(
                "\"correct_choice_index\": 0",
                "\"correct_choice_index\": 4"), review);
        assertRejected(generated.replace(
                "\"question_type\": \"vocabulary\"",
                "\"question_type\": \"essay\""), review);
        assertRejected(generated.replace(
                "\"generated_question_version\": \"generated-question-v2\"",
                "\"unexpected\": true,\n  \"generated_question_version\": \"generated-question-v2\""), review);
    }

    @Test
    void acceptsNullableTokenUsageFromTheAuthoritativeUpstreamSchema() throws Exception {
        String generated = withGeneratedId(
                fixture(GENERATED_FIXTURE), "gq_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
                .replace("\"input_tokens\": 1231", "\"input_tokens\": null")
                .replace("\"output_tokens\": 244", "\"output_tokens\": null")
                .replace("\"total_tokens\": 1475", "\"total_tokens\": null");
        String review = withGeneratedId(
                fixture(REVIEWS_FIXTURE), "gq_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");

        assertThat(importer.importApproved(generated, review).created()).isTrue();
    }

    private void assertRejected(String generated, String review) {
        assertThatThrownBy(() -> importer.importApproved(generated, review))
                .isInstanceOf(QuestionImportException.class);
    }

    private String fixture(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private String withGeneratedId(String artifact, String id) {
        return artifact.replace("gq_99d2b731a84d35c35ca56650b372d5f2", id);
    }
}
