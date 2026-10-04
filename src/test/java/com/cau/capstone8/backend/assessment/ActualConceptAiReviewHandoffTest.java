package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Optional real candidate handoff in an isolated DB; no fabricated approvals or live DB writes. */
@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers
@EnabledIfEnvironmentVariable(named = "ACTUAL_CONCEPT_REVIEW_DIR", matches = ".+")
class ActualConceptAiReviewHandoffTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired ApprovedQuestionImportService importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired AssessmentService assessments;

    @Test void importsActualReviewedCandidatesAndIssuesAProtectedBalancedAssessment() throws Exception {
        Path directory = Path.of(System.getenv("ACTUAL_CONCEPT_REVIEW_DIR"));
        long topic = jdbc.queryForObject("insert into backend.topic(code,name,ml_topic_id) values ('AI-ACTUAL-LA','Actual AI-reviewed linear algebra','linear-algebra') returning id", Long.class);
        try (var paths = Files.list(directory)) {
            var candidates = paths.filter(p -> p.getFileName().toString().matches("q_[0-9a-f]{20}\\.json")).sorted().toList();
            assertThat(candidates).hasSize(18);
        }
        Path manifest = directory.resolve("import-manifest.json");
        assertThat(importer.importManifest(manifest)).hasSize(18)
                .allMatch(ApprovedQuestionImportService.ImportResult::created);
        assertThat(importer.importManifest(manifest)).hasSize(18)
                .noneMatch(ApprovedQuestionImportService.ImportResult::created);
        assertThat(jdbc.queryForObject("select count(*) from backend.question where topic_id=? and upstream_provenance#>>'{aiReview,reviewerType}'='ai' and not (jsonb_exists(upstream_provenance, 'humanReview'))", Long.class, topic)).isEqualTo(18);
        long user = jdbc.queryForObject("insert into backend.app_user(display_name) values ('isolated actual candidate check') returning id", Long.class);
        var session = assessments.createConceptAssessment(user, topic);
        assertThat(session.questions()).hasSize(9);
        assertThat(session.questions().stream().map(AssessmentResponse.IssuedQuestion::cognitiveOperation).distinct()).hasSize(3);
        assertThat(session.questions().stream().map(AssessmentResponse.IssuedQuestion::conceptId).distinct()).hasSize(6);
        assertThat(session.questions()).allMatch(q -> "prior-knowledge".equals(q.measurementContext()));
        String payload = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(session);
        assertThat(payload).doesNotContain("correctChoiceIndex", "correct_choice_index", "explanation", "aiReview");
    }
}
