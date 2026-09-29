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
@Import(GeneratedQuestionAssessmentIntegrationTest.CapturingGatewayConfiguration.class)
class GeneratedQuestionAssessmentIntegrationTest {
    private static final String GENERATED_QUESTION_ID =
            "gq_99d2b731a84d35c35ca56650b372d5f2";
    private static final String ORIGINAL_STEM =
            "운영체제(operating system)에서 실행 중인 프로그램을 의미하며, 자원을 할당받는 기본 단위가 되는 개념은 무엇인가?";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @LocalServerPort int port;
    @Autowired ApprovedQuestionImportService importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapturingMlGateway gateway;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void servesSnapshotGradesOnServerAndSendsCalculatedCorrectnessToMl() throws Exception {
        ApprovedQuestionImportService.ImportResult imported = importer.importApproved(
                fixture("fixtures/question-handoff/generated-process.json"),
                fixture("fixtures/question-handoff/reviews.jsonl"));
        jdbc.update("update backend.question set active=false where demo_key='os-vocab-1'");

        try {
            JsonNode created = json.readTree(post(
                    "/api/assessments",
                    "{\"userId\":" + user() + ",\"topicId\":" + topic() + "}").body());
            long sessionId = created.path("id").asLong();
            JsonNode multipleChoice = findMultipleChoice(created);
            long assessmentQuestionId = multipleChoice.path("id").asLong();

            assertThat(created.path("questions").size()).isEqualTo(9);
            assertSafeMultipleChoiceResponse(multipleChoice);
            assertThat(multipleChoice.path("prompt").asString()).isEqualTo(ORIGINAL_STEM);
            assertThat(multipleChoice.path("choices").get(0).asString())
                    .isEqualTo("프로세스(process)");

            jdbc.update("""
                    update backend.question
                    set prompt='changed after issuance',
                        choices='["changed-0","changed-1","changed-2","changed-3"]'::jsonb,
                        correct_choice_index=3,
                        explanation='changed after issuance'
                    where id=?
                    """, imported.questionId());

            JsonNode snapshot = findQuestion(getSession(sessionId), assessmentQuestionId);
            assertThat(snapshot.path("prompt").asString()).isEqualTo(ORIGINAL_STEM);
            assertThat(snapshot.path("choices").get(0).asString())
                    .isEqualTo("프로세스(process)");
            assertThat(jdbc.queryForMap("""
                    select correct_choice_index_snapshot, explanation_snapshot,
                           upstream_provenance_snapshot->>'promptVersion' as prompt_version
                    from backend.assessment_question where id=?
                    """, assessmentQuestionId))
                    .containsEntry("correct_choice_index_snapshot", 0)
                    .containsEntry(
                            "explanation_snapshot",
                            "프로세스(process)는 실행 중인 프로그램을 뜻하며, 운영체제에서 CPU 시간, 메모리 등의 시스템 자원을 할당받아 작업이 수행되는 기본 단위를 의미합니다.")
                    .containsEntry("prompt_version", "question-generation-prompt-v2");

            HttpResponse<String> correct = putAnswer(
                    sessionId, assessmentQuestionId, "{\"selectedChoiceIndex\":0}");
            assertThat(correct.statusCode()).isEqualTo(200);
            assertThat(json.readTree(correct.body()).path("selectedChoiceIndex").asInt()).isZero();
            assertThat(correct.body()).doesNotContain("correct", "explanation", "correctChoiceIndex");
            assertStoredMultipleChoiceAnswer(assessmentQuestionId, 0, true);

            assertThat(putAnswer(
                    sessionId, assessmentQuestionId, "{\"selectedChoiceIndex\":1}")
                    .statusCode()).isEqualTo(200);
            assertStoredMultipleChoiceAnswer(assessmentQuestionId, 1, false);
            assertThat(putAnswer(
                    sessionId, assessmentQuestionId, "{\"selectedChoiceIndex\":4}")
                    .statusCode()).isEqualTo(400);
            assertThat(putAnswer(
                    sessionId, assessmentQuestionId, "{\"knowsConcept\":true}")
                    .statusCode()).isEqualTo(400);
            assertThat(putAnswer(
                    sessionId,
                    assessmentQuestionId,
                    "{\"selectedChoiceIndex\":0,\"correct\":true}")
                    .statusCode()).isEqualTo(400);

            for (JsonNode question : getSession(sessionId).path("questions")) {
                if (question.path("id").asLong() != assessmentQuestionId) {
                    assertThat(putAnswer(
                            sessionId,
                            question.path("id").asLong(),
                            "{\"knowsConcept\":true}").statusCode()).isEqualTo(200);
                }
            }

            HttpResponse<String> completed = post(
                    "/api/assessments/" + sessionId + "/complete", "");
            assertThat(completed.statusCode()).isEqualTo(200);
            JsonNode profile = json.readTree(completed.body()).path("profile");
            assertThat(profile.path("vocabulary").asDouble()).isEqualTo(2.0 / 3.0);
            assertThat(profile.path("backgroundKnowledge").asDouble()).isEqualTo(1.0);
            assertThat(profile.path("comprehension").asDouble()).isEqualTo(1.0);

            MlProfileRequest.Answer mlAnswer = gateway.lastRequest().answers().stream()
                    .filter(answer -> answer.questionId().equals(GENERATED_QUESTION_ID))
                    .findFirst()
                    .orElseThrow();
            assertThat(mlAnswer.correct()).isFalse();
            assertThat(mlAnswer.measurementArea()).isEqualTo(MeasurementArea.VOCABULARY);
        } finally {
            jdbc.update("update backend.question set active=true where demo_key='os-vocab-1'");
            jdbc.update("""
                    update backend.question
                    set active=false,
                        prompt=?,
                        choices='["프로세스(process)","파일 시스템(file system)","디바이스 드라이버(device driver)","인터럽트 벡터(interrupt vector)"]'::jsonb,
                        correct_choice_index=0,
                        explanation='프로세스(process)는 실행 중인 프로그램을 뜻하며, 운영체제에서 CPU 시간, 메모리 등의 시스템 자원을 할당받아 작업이 수행되는 기본 단위를 의미합니다.'
                    where generated_question_id=?
                    """, ORIGINAL_STEM, GENERATED_QUESTION_ID);
        }
    }

    private void assertSafeMultipleChoiceResponse(JsonNode question) {
        assertThat(question.path("passage").isNull()).isTrue();
        assertThat(question.path("answerMode").asString()).isEqualTo("MULTIPLE_CHOICE");
        assertThat(question.path("choices").size()).isEqualTo(4);
        assertThat(question.has("correctChoiceIndex")).isFalse();
        assertThat(question.has("correct")).isFalse();
        assertThat(question.has("explanation")).isFalse();
        assertThat(question.path("knowsConcept").isNull()).isTrue();
        assertThat(question.path("selectedChoiceIndex").isNull()).isTrue();
    }

    private void assertStoredMultipleChoiceAnswer(
            long assessmentQuestionId,
            int selectedChoiceIndex,
            boolean correct) {
        assertThat(jdbc.queryForMap("""
                select answer_mode, knows_concept, selected_choice_index, correct
                from backend.assessment_answer where assessment_question_id=?
                """, assessmentQuestionId))
                .containsEntry("answer_mode", "MULTIPLE_CHOICE")
                .containsEntry("knows_concept", null)
                .containsEntry("selected_choice_index", selectedChoiceIndex)
                .containsEntry("correct", correct);
    }

    private JsonNode findMultipleChoice(JsonNode assessment) {
        for (JsonNode question : assessment.path("questions")) {
            if ("MULTIPLE_CHOICE".equals(question.path("answerMode").asString())) {
                return question;
            }
        }
        fail("발급된 객관식 문항을 찾을 수 없습니다.");
        throw new AssertionError("unreachable");
    }

    private JsonNode findQuestion(JsonNode assessment, long questionId) {
        for (JsonNode question : assessment.path("questions")) {
            if (question.path("id").asLong() == questionId) {
                return question;
            }
        }
        fail("진단 응답에서 문항을 찾을 수 없습니다: %s", questionId);
        throw new AssertionError("unreachable");
    }

    private JsonNode getSession(long sessionId) throws Exception {
        HttpResponse<String> response = get("/api/assessments/" + sessionId);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> putAnswer(long sessionId, long questionId, String body)
            throws Exception {
        return put("/api/assessments/" + sessionId + "/answers/" + questionId, body);
    }

    private String fixture(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private long user() {
        return jdbc.queryForObject("select id from backend.app_user limit 1", Long.class);
    }

    private long topic() {
        return jdbc.queryForObject("select id from backend.topic where code='OS'", Long.class);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build());
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
