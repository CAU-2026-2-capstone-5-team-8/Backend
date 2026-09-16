package com.cau.capstone8.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
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
class RecommendationIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void createsTopKRecommendationForAllActiveCandidates() throws Exception {
        long userId = completeAssessment(topic("OS"));

        HttpResponse<String> response = recommend(userId, topic("OS"), null, "BALANCED", 5, key());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(body.path("candidateCount").asInt()).isEqualTo(5);
        assertThat(body.path("excludedCount").asInt()).isEqualTo(0);
        assertThat(body.path("modelVersion").asString()).isEqualTo("stub-rank-v1");
        JsonNode items = body.path("items");
        assertThat(items.size()).isEqualTo(5);
        Set<Long> bookIds = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        for (JsonNode item : items) {
            assertThat(bookIds.add(item.path("bookId").asLong())).isTrue();
            assertThat(ranks.add(item.path("rank").asInt())).isTrue();
            assertThat(item.path("reasons").size()).isGreaterThanOrEqualTo(2);
        }
        assertThat(ranks).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void replaysSuccessfulRunForSameIdempotencyKeyAndBody() throws Exception {
        long userId = completeAssessment(topic("OS"));
        String idempotencyKey = key();

        HttpResponse<String> first = recommend(userId, topic("OS"), null, "BALANCED", 5, idempotencyKey);
        HttpResponse<String> second = recommend(userId, topic("OS"), null, "BALANCED", 5, idempotencyKey);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()).path("runId").asLong())
                .isEqualTo(json.readTree(first.body()).path("runId").asLong());
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.recommendation_run where user_id=?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void rejectsSameKeyWithDifferentBody() throws Exception {
        long userId = completeAssessment(topic("OS"));
        String idempotencyKey = key();
        recommend(userId, topic("OS"), null, "BALANCED", 5, idempotencyKey);

        HttpResponse<String> response = recommend(userId, topic("OS"), null, "CHALLENGING", 5, idempotencyKey);

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void returns409WhenNoCompletedProfileExists() throws Exception {
        long userWithoutProfile = jdbc.queryForObject(
                "insert into backend.app_user(display_name) values ('프로필 없음') returning id", Long.class);

        HttpResponse<String> response = recommend(userWithoutProfile, topic("OS"), null, "BALANCED", 5, key());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString())
                .isEqualTo("RECOMMENDATION_CONFLICT");
    }

    @Test
    void returns422WhenTopicHasNoFeaturedCandidates() throws Exception {
        long userId = user();
        insertCompletedProfile(userId, topic("CS"), 0.5, 0.5, 0.5);

        HttpResponse<String> response = recommend(userId, topic("CS"), null, "BALANCED", 5, key());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json.readTree(response.body()).path("code").asString())
                .isEqualTo("RECOMMENDATION_INPUT_UNUSABLE");
    }

    @Test
    void targetModeReturnsExactlyOneRankedItem() throws Exception {
        long userId = completeAssessment(topic("OS"));
        long targetBookId = jdbc.queryForObject(
                "select book_id from backend.book_feature where topic_id=? and active limit 1",
                Long.class, topic("OS"));

        HttpResponse<String> response = recommend(userId, topic("OS"), targetBookId, "BALANCED", 5, key());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("items").size()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("bookId").asLong()).isEqualTo(targetBookId);
        assertThat(body.path("items").get(0).path("rank").asInt()).isEqualTo(1);
    }

    @Test
    void targetModeReturns422ForUnknownBook() throws Exception {
        long userId = completeAssessment(topic("OS"));

        HttpResponse<String> response =
                recommend(userId, topic("OS"), Long.MAX_VALUE, "BALANCED", 5, key());

        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    void rejectsWhileAnotherAttemptIsActivelyProcessing() throws Exception {
        long userId = completeAssessment(topic("OS"));
        String idempotencyKey = key();
        recommend(userId, topic("OS"), null, "BALANCED", 5, idempotencyKey);
        jdbc.update("update backend.recommendation_run set status='PROCESSING', attempt_id=?, "
                        + "processing_expires_at=now()+interval '30 seconds', model_version=NULL, completed_at=NULL "
                        + "where user_id=? and request_key=?",
                UUID.randomUUID(), userId, idempotencyKey);

        HttpResponse<String> response = recommend(userId, topic("OS"), null, "BALANCED", 5, idempotencyKey);

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void expiredProcessingLeaseReplaysAsFailureAndMarksRunFailed() throws Exception {
        long userId = completeAssessment(topic("OS"));
        String idempotencyKey = key();
        recommend(userId, topic("OS"), null, "BALANCED", 5, idempotencyKey);
        jdbc.update("update backend.recommendation_run set status='PROCESSING', attempt_id=?, "
                        + "processing_expires_at=now()-interval '1 second', model_version=NULL, completed_at=NULL "
                        + "where user_id=? and request_key=?",
                UUID.randomUUID(), userId, idempotencyKey);

        HttpResponse<String> response = recommend(userId, topic("OS"), null, "BALANCED", 5, idempotencyKey);

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("PROCESSING_EXPIRED");
        assertThat(jdbc.queryForObject(
                "select status from backend.recommendation_run where user_id=? and request_key=?",
                String.class, userId, idempotencyKey)).isEqualTo("FAILED");
    }

    private long completeAssessment(long topicId) throws Exception {
        long userId = user();
        HttpResponse<String> created = post(
                "/api/assessments", "{\"userId\":" + userId + ",\"topicId\":" + topicId + "}");
        assertThat(created.statusCode()).isEqualTo(201);
        long sessionId = json.readTree(created.body()).path("id").asLong();
        for (JsonNode question : json.readTree(get("/api/assessments/" + sessionId).body()).path("questions")) {
            HttpResponse<String> answered = put(
                    "/api/assessments/" + sessionId + "/answers/" + question.path("id").asLong(),
                    "{\"knowsConcept\":true}");
            assertThat(answered.statusCode()).isEqualTo(200);
        }
        assertThat(post("/api/assessments/" + sessionId + "/complete", "").statusCode()).isEqualTo(200);
        return userId;
    }

    private void insertCompletedProfile(long userId, long topicId, double vocabulary, double backgroundKnowledge,
                                         double comprehension) {
        long sessionId = jdbc.queryForObject(
                "insert into backend.assessment_session(user_id,topic_id,status,completed_at) "
                        + "values (?,?,'COMPLETED',now()) returning id",
                Long.class, userId, topicId);
        jdbc.update(
                "insert into backend.reader_profile(session_id,vocabulary,background_knowledge,comprehension,"
                        + "calculation_version,evidence) values (?,?,?,?,'test-v1','{}'::jsonb)",
                sessionId, vocabulary, backgroundKnowledge, comprehension);
    }

    private long user() {
        return jdbc.queryForObject("select id from backend.app_user limit 1", Long.class);
    }

    private long topic(String code) {
        return jdbc.queryForObject("select id from backend.topic where code=?", Long.class, code);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private HttpResponse<String> recommend(long userId, long topicId, Long targetBookId, String challengeLevel,
                                            int topK, String idempotencyKey) throws Exception {
        StringBuilder body = new StringBuilder("{\"userId\":").append(userId)
                .append(",\"topicId\":").append(topicId)
                .append(",\"challengeLevel\":\"").append(challengeLevel).append("\"")
                .append(",\"topK\":").append(topK);
        if (targetBookId != null) {
            body.append(",\"targetBookId\":").append(targetBookId);
        }
        body.append("}");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/recommendations"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(request);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return send(request);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
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
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
