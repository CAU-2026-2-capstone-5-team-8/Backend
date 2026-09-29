package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.cau.capstone8.backend.integration.ml.MlGateway;
import com.cau.capstone8.backend.integration.ml.MlProfileRequest;
import com.cau.capstone8.backend.integration.ml.MlProfileResult;
import com.cau.capstone8.backend.integration.ml.MlRankRequest;
import com.cau.capstone8.backend.integration.ml.MlRankResult;
import com.cau.capstone8.backend.integration.ml.StubMlGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
@Import(GeneratedQuestionV4AssessmentIntegrationTest.CapturingGatewayConfiguration.class)
class GeneratedQuestionV4AssessmentIntegrationTest {
    private static final String GENERATED_ID = "gq_66f3360464d1336ec1612715ebbdd3ed";
    private static final String GENERATED =
            "fixtures/question-handoff/generated-matrix-v4-rev1.json";
    private static final String REVIEWS =
            "fixtures/question-handoff/reviews-linear-algebra-display-grounded.jsonl";
    private static final String GROUNDING =
            "fixtures/question-handoff/grounding-matrix-v2.json";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @LocalServerPort int port;
    @Autowired ApprovedQuestionImportService importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapturingMlGateway gateway;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void snapshotsDisplayPassageGradesOnServerAndHandsComprehensionToMl() throws Exception {
        long topicId = createTopicAndSelfReportBank();
        long userId = jdbc.queryForObject(
                "insert into backend.app_user(display_name) values ('v4 user') returning id",
                Long.class);
        JsonNode generated = json.readTree(fixture(GENERATED));
        String originalPassage = generated.path("passage").asString();
        String originalStem = generated.path("stem").asString();
        ApprovedQuestionImportService.ImportResult imported = importer.importApproved(
                fixture(GENERATED), fixture(REVIEWS), fixture(GROUNDING));

        JsonNode created = json.readTree(post(
                "/api/assessments",
                "{\"userId\":" + userId + ",\"topicId\":" + topicId + "}").body());
        assertThat(created.path("questions").size()).isEqualTo(9);
        long sessionId = created.path("id").asLong();
        JsonNode issued = findGenerated(created);
        long assessmentQuestionId = issued.path("id").asLong();

        assertThat(issued.path("passage").asString()).isEqualTo(originalPassage);
        assertThat(issued.path("prompt").asString()).isEqualTo(originalStem);
        assertThat(issued.path("answerMode").asString()).isEqualTo("MULTIPLE_CHOICE");
        assertThat(issued.path("choices").size()).isEqualTo(4);
        assertSafeResponse(issued);
        assertThat(created.toString()).doesNotContain(
                "correctChoiceIndex", "\"correct\"", "explanation", "DeﬁnitionAnm×n");

        jdbc.update("""
                update backend.question
                set passage='changed passage after issuance',
                    prompt='changed prompt after issuance',
                    choices='["changed-0","changed-1","changed-2","changed-3"]'::jsonb,
                    correct_choice_index=0,
                    explanation='changed explanation after issuance'
                where id=?
                """, imported.questionId());

        JsonNode snapshot = findQuestion(getSession(sessionId), assessmentQuestionId);
        assertThat(snapshot.path("passage").asString()).isEqualTo(originalPassage);
        assertThat(snapshot.path("prompt").asString()).isEqualTo(originalStem);
        assertThat(snapshot.path("choices").get(3).asString())
                .isEqualTo("4×3 행렬이며, 행의 수가 항상 먼저 오므로 '포-바이-스리(four-by-three)'라고 읽는다.");
        assertThat(jdbc.queryForMap("""
                select passage_snapshot, prompt_snapshot, correct_choice_index_snapshot,
                       explanation_snapshot, generated_question_id_snapshot,
                       difficulty_snapshot
                from backend.assessment_question where id=?
                """, assessmentQuestionId))
                .containsEntry("passage_snapshot", originalPassage)
                .containsEntry("prompt_snapshot", originalStem)
                .containsEntry("correct_choice_index_snapshot", 3)
                .containsEntry("generated_question_id_snapshot", GENERATED_ID)
                .containsEntry("difficulty_snapshot", 2);

        HttpResponse<String> answer = put(
                "/api/assessments/" + sessionId + "/answers/" + assessmentQuestionId,
                "{\"selectedChoiceIndex\":3}");
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).doesNotContain("correctChoiceIndex", "\"correct\"", "explanation");
        assertThat(jdbc.queryForMap("""
                select selected_choice_index, correct from backend.assessment_answer
                where assessment_question_id=?
                """, assessmentQuestionId))
                .containsEntry("selected_choice_index", 3)
                .containsEntry("correct", true);

        for (JsonNode question : getSession(sessionId).path("questions")) {
            if (question.path("id").asLong() != assessmentQuestionId) {
                assertThat(put(
                        "/api/assessments/" + sessionId + "/answers/" + question.path("id").asLong(),
                        "{\"knowsConcept\":true}").statusCode()).isEqualTo(200);
            }
        }
        assertThat(post("/api/assessments/" + sessionId + "/complete", "").statusCode())
                .isEqualTo(200);

        MlProfileRequest.Answer mlAnswer = gateway.lastRequest().answers().stream()
                .filter(candidate -> GENERATED_ID.equals(candidate.questionId()))
                .findFirst()
                .orElseThrow();
        assertThat(mlAnswer.measurementArea()).isEqualTo(MeasurementArea.COMPREHENSION);
        assertThat(mlAnswer.conceptId()).isEqualTo("matrix");
        assertThat(mlAnswer.difficulty()).isEqualTo(2);
        assertThat(mlAnswer.correct()).isTrue();
    }

    private long createTopicAndSelfReportBank() {
        long topicId = jdbc.queryForObject("""
                insert into backend.topic(code,name,ml_topic_id)
                values ('LA-V4-ASSESSMENT','Linear Algebra v4 assessment','linear-algebra')
                returning id
                """, Long.class);
        insertQuestions(topicId, "VOCABULARY", 3);
        insertQuestions(topicId, "BACKGROUND_KNOWLEDGE", 3);
        insertQuestions(topicId, "COMPREHENSION", 2);
        return topicId;
    }

    private void insertQuestions(long topicId, String area, int count) {
        for (int index = 0; index < count; index++) {
            jdbc.update("""
                    insert into backend.question(
                        topic_id,measurement_area,difficulty,prompt,concept_id,version,active)
                    values (?,?,1,?,?,?,true)
                    """,
                    topicId,
                    area,
                    area + " self-report " + index,
                    area.toLowerCase() + "-concept-" + index,
                    "v4-assessment-test-v1");
        }
    }

    private void assertSafeResponse(JsonNode question) {
        assertThat(question.has("correctChoiceIndex")).isFalse();
        assertThat(question.has("correct")).isFalse();
        assertThat(question.has("explanation")).isFalse();
        assertThat(question.path("knowsConcept").isNull()).isTrue();
        assertThat(question.path("selectedChoiceIndex").isNull()).isTrue();
    }

    private JsonNode findGenerated(JsonNode assessment) {
        for (JsonNode question : assessment.path("questions")) {
            if (question.path("passage").isString()) {
                return question;
            }
        }
        fail("generated-question-v4 was not issued");
        throw new AssertionError("unreachable");
    }

    private JsonNode findQuestion(JsonNode assessment, long questionId) {
        for (JsonNode question : assessment.path("questions")) {
            if (question.path("id").asLong() == questionId) {
                return question;
            }
        }
        fail("issued question was not found");
        throw new AssertionError("unreachable");
    }

    private JsonNode getSession(long sessionId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/assessments/" + sessionId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build());
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private String fixture(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    static final class CapturingMlGateway implements MlGateway {
        private final StubMlGateway delegate = new StubMlGateway();
        private final AtomicReference<MlProfileRequest> lastRequest = new AtomicReference<>();

        @Override
        public MlProfileResult calculateProfile(MlProfileRequest request) {
            lastRequest.set(request);
            return delegate.calculateProfile(request);
        }

        @Override
        public MlRankResult rankBooks(MlRankRequest request) {
            return delegate.rankBooks(request);
        }

        MlProfileRequest lastRequest() {
            return lastRequest.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CapturingGatewayConfiguration {
        @Bean
        @Primary
        CapturingMlGateway capturingMlGateway() {
            return new CapturingMlGateway();
        }
    }
}
