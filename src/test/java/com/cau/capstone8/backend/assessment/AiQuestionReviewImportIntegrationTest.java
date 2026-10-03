package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers
class AiQuestionReviewImportIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired ApprovedQuestionImportService importer;
    @Autowired JdbcTemplate jdbc;
    String generated;
    String id;

    @BeforeEach void setup() throws Exception {
        generated = Files.readString(Path.of("src/test/resources/fixtures/question-handoff/generated-concept-v5.json"));
        id = importer.parseGeneratedQuestion(generated).generatedQuestionId();
        jdbc.update("delete from backend.question where generated_question_id=?", id);
        jdbc.update("insert into backend.topic(code,name,ml_topic_id) values ('AI-REVIEW-TEST','Synthetic AI review contract','linear-algebra') on conflict (code) do nothing");
    }

    String judgment() {
        return """
            {"generated_question_id":"%s","status":"approve","correct":true,
             "concept_alignment":4,"difficulty_appropriate":true,"distractor_quality":3,
             "explanation_quality":4,"notes":"Synthetic test judgment only"}
            """.formatted(id).replace("\n", "");
    }

    String aiReview() {
        return """
            {"review_version":"ai-question-review-v1","reviewer_type":"ai",
             "reviewer_name":"Synthetic reviewer","validation_scope":"content-only","review":%s}
            """.formatted(judgment()).replace("\n", "");
    }

    @Test void importsAiProvenanceWithoutInventingAHumanReviewAndIsIdempotent() {
        var first = importer.importApproved(generated, aiReview());
        assertThat(first.created()).isTrue();
        assertThat(importer.importApproved(generated, aiReview()).created()).isFalse();
        assertThat(jdbc.queryForMap("""
            select jsonb_exists(upstream_provenance, 'humanReview') as has_human,
                   upstream_provenance#>>'{aiReview,reviewerType}' as reviewer_type,
                   upstream_provenance#>>'{aiReview,reviewerName}' as reviewer_name,
                   upstream_provenance#>>'{aiReview,validationScope}' as scope,
                   upstream_provenance#>>'{aiReview,status}' as status
            from backend.question where id=?
            """, first.questionId()))
                .containsEntry("has_human", false)
                .containsEntry("reviewer_type", "ai")
                .containsEntry("reviewer_name", "Synthetic reviewer")
                .containsEntry("scope", "content-only")
                .containsEntry("status", "approve");
    }

    @Test void rejectsDuplicateMixedReviewTypesAndStaleContentIds() {
        reject(aiReview() + "\n" + judgment());
        reject(aiReview().replace(id, "gq_ffffffffffffffffffffffffffffffff"));
        reject(aiReview().replace("\"correct\":true", "\"correct\":false"));
        reject(aiReview().replace("\"approve\"", "\"needs_revision\""));
        assertThatThrownBy(() -> importer.importApproved(
                generated.replace("1*1+2*0=1", "1*1+2*0=9"), aiReview()))
                .isInstanceOf(QuestionImportException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"review_version", "reviewer_type", "reviewer_name", "validation_scope", "review"})
    void rejectsMissingAiMetadata(String field) {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var object = (tools.jackson.databind.node.ObjectNode) mapper.readTree(aiReview());
        object.remove(field);
        reject(mapper.writeValueAsString(object));
    }

    @Test void rejectsInvalidScopeTypeScoresAndUnknownFields() {
        reject(aiReview().replace("content-only", "empirically-validated"));
        reject(aiReview().replace("ai-question-review-v1", "ai-question-review-v2"));
        reject(aiReview().replace("\"ai\"", "\"human\""));
        reject(aiReview().replace("Synthetic reviewer", " "));
        reject(aiReview().replace("\"correct\":true", "\"correct\":\"true\""));
        reject(aiReview().replace("\"concept_alignment\":4", "\"concept_alignment\":6"));
        reject(aiReview().replace("\"review\":", "\"unrecognized\":true,\"review\":"));
    }

    @Test void keepsAiSupportScopedToConceptV5() throws Exception {
        String old = Files.readString(Path.of("src/test/resources/fixtures/question-handoff/generated-process.json"));
        String oldId = importer.parseGeneratedQuestion(old).generatedQuestionId();
        assertThatThrownBy(() -> importer.importApproved(old, aiReview().replace(id, oldId)))
                .isInstanceOf(QuestionImportException.class).hasMessageContaining("only generated-question-v5");
    }

    private void reject(String review) {
        assertThatThrownBy(() -> importer.importApproved(generated, review))
                .isInstanceOf(QuestionImportException.class);
        assertThat(jdbc.queryForObject("select count(*) from backend.question where generated_question_id=?",
                Long.class, id)).isZero();
    }
}
