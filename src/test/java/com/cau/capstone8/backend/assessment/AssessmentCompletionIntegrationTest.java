package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class AssessmentCompletionIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void completesAssessmentPersistsProfileAndReturnsSameProfileOnRetry() throws Exception {
        long sessionId = createSession();
        answerWithKnownCounts(sessionId, Map.of(
                "VOCABULARY", 2,
                "BACKGROUND_KNOWLEDGE", 1,
                "COMPREHENSION", 3));

        HttpResponse<String> first = complete(sessionId);
        HttpResponse<String> second = complete(sessionId);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        JsonNode firstBody = json.readTree(first.body());
        JsonNode secondBody = json.readTree(second.body());
        assertThat(firstBody.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(firstBody.path("profile").path("vocabulary").asDouble()).isEqualTo(2.0 / 3.0);
        assertThat(firstBody.path("profile").path("backgroundKnowledge").asDouble())
                .isEqualTo(1.0 / 3.0);
        assertThat(firstBody.path("profile").path("comprehension").asDouble()).isEqualTo(1.0);
        assertThat(firstBody.path("profile").path("calculationVersion").asString())
                .isEqualTo("stub-profile-v1");
        assertThat(firstBody.path("profile").path("evidence").path("method").asString())
                .isEqualTo("known_response_ratio");
        assertThat(secondBody.path("profile").path("id").asLong())
                .isEqualTo(firstBody.path("profile").path("id").asLong());
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.reader_profile where session_id=?",
                Integer.class,
                sessionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select attempt_id is null and processing_expires_at is null "
                        + "and completed_at is not null from backend.assessment_session where id=?",
                Boolean.class,
                sessionId)).isTrue();
    }

    @Test
    void rejectsCompletionUntilAllNineAnswersExist() throws Exception {
        long sessionId = createSession();
        JsonNode questions = getSession(sessionId).path("questions");
        putAnswer(sessionId, questions.get(0).path("id").asLong(), true);

        HttpResponse<String> response = complete(sessionId);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString())
                .isEqualTo("ASSESSMENT_STATE_CONFLICT");
        assertThat(jdbc.queryForObject(
                "select status from backend.assessment_session where id=?",
                String.class,
                sessionId)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void rejectsConcurrentCompletionWhileLeaseIsActive() throws Exception {
        long sessionId = createSession();
        answerAll(sessionId, true);
        jdbc.update(
                "update backend.assessment_session set status='PROCESSING', attempt_id=?, "
                        + "processing_expires_at=now()+interval '30 seconds' where id=?",
                UUID.randomUUID(),
                sessionId);

        HttpResponse<String> response = complete(sessionId);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("message").asString()).contains("진행 중");
    }

    @Test
    void expiredAttemptCanBeReplacedAndCompleted() throws Exception {
        long sessionId = createSession();
        answerAll(sessionId, true);
        UUID expiredAttempt = UUID.randomUUID();
        jdbc.update(
                "update backend.assessment_session set status='PROCESSING', attempt_id=?, "
                        + "processing_expires_at=now()-interval '1 second' where id=?",
                expiredAttempt,
                sessionId);

        HttpResponse<String> response = complete(sessionId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("status").asString()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "select attempt_id is null from backend.assessment_session where id=?",
                Boolean.class,
                sessionId)).isTrue();
    }

    @Test
    void latestProfileUsesCompletionTimeThenProfileId() throws Exception {
        long firstSession = createSession();
        answerAll(firstSession, false);
        complete(firstSession);
        jdbc.update(
                "update backend.assessment_session set completed_at=now()-interval '1 hour' where id=?",
                firstSession);

        long secondSession = createSession();
        answerAll(secondSession, true);
        JsonNode completed = json.readTree(complete(secondSession).body());

        HttpResponse<String> response = get("/api/users/" + user() + "/profiles/" + topic("OS"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("id").asLong())
                .isEqualTo(completed.path("profile").path("id").asLong());
        assertThat(body.path("sessionId").asLong()).isEqualTo(secondSession);
        assertThat(body.path("vocabulary").asDouble()).isEqualTo(1.0);
    }

    @Test
    void returns404WhenCompletedProfileDoesNotExist() throws Exception {
        HttpResponse<String> response = get(
                "/api/users/" + Long.MAX_VALUE + "/profiles/" + topic("OS"));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void completedSessionRejectsFurtherAnswerChanges() throws Exception {
        long sessionId = createSession();
        JsonNode questions = getSession(sessionId).path("questions");
        answerAll(sessionId, true);
        complete(sessionId);

        HttpResponse<String> response = putAnswer(
                sessionId, questions.get(0).path("id").asLong(), false);

        assertThat(response.statusCode()).isEqualTo(409);
    }

    private long createSession() throws Exception {
        HttpResponse<String> response = post(
                "/api/assessments",
                "{\"userId\":" + user() + ",\"topicId\":" + topic("OS") + "}");
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).path("id").asLong();
    }

    private JsonNode getSession(long sessionId) throws Exception {
        return json.readTree(get("/api/assessments/" + sessionId).body());
    }

    private void answerAll(long sessionId, boolean knowsConcept) throws Exception {
        JsonNode questions = getSession(sessionId).path("questions");
        for (JsonNode question : questions) {
            assertThat(putAnswer(sessionId, question.path("id").asLong(), knowsConcept).statusCode())
                    .isEqualTo(200);
        }
    }

    private void answerWithKnownCounts(long sessionId, Map<String, Integer> targetKnown)
            throws Exception {
        JsonNode questions = getSession(sessionId).path("questions");
        Map<String, Integer> seen = new HashMap<>();
        for (JsonNode question : questions) {
            String area = question.path("measurementArea").asString();
            int areaIndex = seen.merge(area, 1, Integer::sum);
            boolean knowsConcept = areaIndex <= targetKnown.get(area);
            assertThat(putAnswer(sessionId, question.path("id").asLong(), knowsConcept).statusCode())
                    .isEqualTo(200);
        }
    }

    private HttpResponse<String> putAnswer(
            long sessionId,
            long questionId,
            boolean knowsConcept) throws Exception {
        return put(
                "/api/assessments/" + sessionId + "/answers/" + questionId,
                "{\"knowsConcept\":" + knowsConcept + "}");
    }

    private HttpResponse<String> complete(long sessionId) throws Exception {
        return post("/api/assessments/" + sessionId + "/complete", "");
    }

    private long user() {
        return jdbc.queryForObject("select id from backend.app_user limit 1", Long.class);
    }

    private long topic(String code) {
        return jdbc.queryForObject(
                "select id from backend.topic where code=?", Long.class, code);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return send(request);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private HttpResponse<String> put(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
