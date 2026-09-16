package com.cau.capstone8.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
    void createsRanksPersistsAndReplaysSameRequest() throws Exception {
        long userId = userWithProfile();
        String key = UUID.randomUUID().toString();
        String body = body(userId, "BALANCED", null, 3);

        HttpResponse<String> first = create(key, body);
        HttpResponse<String> replay = create(key, body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(200);
        JsonNode result = json.readTree(first.body());
        assertThat(json.readTree(replay.body())).isEqualTo(result);
        assertThat(result.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(result.path("modelVersion").asString()).isEqualTo("stub-rank-v1");
        assertThat(result.path("eligibleCandidateCount").asInt()).isEqualTo(5);
        assertThat(result.path("excludedCandidateCount").asInt()).isZero();
        assertThat(result.path("items").size()).isEqualTo(3);
        for (int index = 0; index < 3; index++) {
            JsonNode item = result.path("items").get(index);
            assertThat(item.path("rank").asInt()).isEqualTo(index + 1);
            assertThat(item.path("reasons").size()).isGreaterThanOrEqualTo(2);
            assertThat(item.path("score").asDouble()).isBetween(0.0, 1.0);
        }
        long runId = result.path("id").asLong();
        assertThat(json.readTree(get(runId).body())).isEqualTo(result);
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.recommendation_run where id=?",
                Integer.class, runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.recommendation_item where run_id=?",
                Integer.class, runId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select input_snapshot->>'mlMode' from backend.recommendation_run where id=?",
                String.class, runId)).isEqualTo("stub");
    }

    @Test
    void sameKeyWithChangedBodyConflictsWithoutNewRun() throws Exception {
        long userId = userWithProfile();
        String key = UUID.randomUUID().toString();
        create(key, body(userId, "BALANCED", null, 2));

        HttpResponse<String> response = create(key, body(userId, "CHALLENGING", null, 2));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString())
                .isEqualTo("RECOMMENDATION_CONFLICT");
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.recommendation_run where user_id=? and request_key=?",
                Integer.class, userId, key)).isEqualTo(1);
    }

    @Test
    void targetBookIgnoresTopKAndRequiresActiveFeature() throws Exception {
        long userId = userWithProfile();
        long bookId = book("os-demo-5");
        String key = UUID.randomUUID().toString();

        HttpResponse<String> response = create(
                key, body(userId, "BALANCED", bookId, 20));
        HttpResponse<String> replay = create(key, body(userId, "BALANCED", bookId, 1));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(response.body()));
        assertThat(json.readTree(response.body()).path("requestedTopK").asInt()).isEqualTo(1);
        assertThat(json.readTree(response.body()).path("items").size()).isEqualTo(1);
        assertThat(json.readTree(response.body()).path("items").get(0).path("bookId").asLong())
                .isEqualTo(bookId);

        jdbc.update("update backend.book_feature set active=false where book_id=?", bookId);
        try {
            HttpResponse<String> unavailable = create(
                    UUID.randomUUID().toString(), body(userId, "BALANCED", bookId, 5));
            assertThat(unavailable.statusCode()).isEqualTo(422);
            assertThat(json.readTree(unavailable.body()).path("code").asString())
                    .isEqualTo("RECOMMENDATION_INPUT_UNAVAILABLE");
        } finally {
            jdbc.update("update backend.book_feature set active=true where book_id=?", bookId);
        }
    }

    @Test
    void missingProfileAndInvalidRequestsFailWithoutPersistingRun() throws Exception {
        long userId = jdbc.queryForObject(
                "insert into backend.app_user(display_name) values ('unassessed') returning id",
                Long.class);
        String key = UUID.randomUUID().toString();

        HttpResponse<String> missingProfile = create(key, body(userId, "BALANCED", null, 5));
        HttpResponse<String> missingKey = post("/api/recommendations", null,
                body(userId, "BALANCED", null, 5));
        HttpResponse<String> invalidTopK = create(
                UUID.randomUUID().toString(), body(userId, "BALANCED", null, 21));

        assertThat(missingProfile.statusCode()).isEqualTo(409);
        assertThat(missingKey.statusCode()).isEqualTo(400);
        assertThat(invalidTopK.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.recommendation_run where user_id=?",
                Integer.class, userId)).isZero();
    }

    @Test
    void expiredProcessingRunBecomesFailedAndOldKeyReplaysSavedError() throws Exception {
        long userId = userWithProfile();
        String key = UUID.randomUUID().toString();
        HttpResponse<String> created = create(key, body(userId, "BALANCED", null, 2));
        long runId = json.readTree(created.body()).path("id").asLong();
        jdbc.update("delete from backend.recommendation_item where run_id=?", runId);
        jdbc.update("update backend.recommendation_run set status='PROCESSING', "
                        + "model_version=null, completed_at=null, attempt_id=?, "
                        + "processing_expires_at=now()-interval '1 second' where id=?",
                UUID.randomUUID(), runId);

        HttpResponse<String> retrieved = get(runId);
        HttpResponse<String> replay = create(key, body(userId, "BALANCED", null, 2));

        assertThat(retrieved.statusCode()).isEqualTo(200);
        assertThat(json.readTree(retrieved.body()).path("status").asString()).isEqualTo("FAILED");
        assertThat(json.readTree(retrieved.body()).path("failureCode").asString())
                .isEqualTo("RECOMMENDATION_EXPIRED");
        assertThat(replay.statusCode()).isEqualTo(409);
        assertThat(json.readTree(replay.body()).path("code").asString())
                .isEqualTo("RECOMMENDATION_EXPIRED");
        assertThat(jdbc.queryForObject(
                "select status from backend.recommendation_run where id=?",
                String.class, runId)).isEqualTo("FAILED");
    }

    @Test
    void feedbackIsCreatedThenReplacedAndChecksOwner() throws Exception {
        long userId = userWithProfile();
        JsonNode result = json.readTree(create(UUID.randomUUID().toString(),
                body(userId, "BALANCED", null, 1)).body());
        long itemId = result.path("items").get(0).path("id").asLong();
        String path = "/api/recommendations/" + itemId + "/feedback";

        HttpResponse<String> first = post(path, null,
                "{\"userId\":" + userId + ",\"helpful\":true,\"comment\":\"좋아요\"}");
        HttpResponse<String> second = post(path, null,
                "{\"userId\":" + userId + ",\"helpful\":false,\"comment\":\"다시 읽음\"}");
        long otherUser = jdbc.queryForObject(
                "insert into backend.app_user(display_name) values ('another') returning id", Long.class);
        HttpResponse<String> wrongOwner = post(path, null,
                "{\"userId\":" + otherUser + ",\"helpful\":true}");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).path("id").asLong())
                .isEqualTo(json.readTree(second.body()).path("id").asLong());
        assertThat(json.readTree(second.body()).path("helpful").asBoolean()).isFalse();
        assertThat(wrongOwner.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.feedback where recommendation_item_id=?",
                Integer.class, itemId)).isEqualTo(1);
    }

    private long userWithProfile() throws Exception {
        long userId = jdbc.queryForObject(
                "insert into backend.app_user(display_name) values ('assessed') returning id",
                Long.class);
        long topicId = topic();
        long sessionId = jdbc.queryForObject(
                "insert into backend.assessment_session(user_id,topic_id,status) "
                        + "values (?,?,'IN_PROGRESS') returning id",
                Long.class, userId, topicId);
        jdbc.update("""
                insert into backend.assessment_question(
                    session_id,question_id,order_index,measurement_area_snapshot,
                    prompt_snapshot,concept_id_snapshot,version_snapshot,difficulty_snapshot)
                select ?,id,(row_number() over (order by id)-1)::int,
                       measurement_area,prompt,concept_id,version,difficulty
                from backend.question where topic_id=? and active
                """, sessionId, topicId);
        jdbc.update("""
                insert into backend.assessment_answer(assessment_question_id,knows_concept)
                select id,true from backend.assessment_question where session_id=?
                """, sessionId);
        HttpResponse<String> completed = post("/api/assessments/" + sessionId + "/complete", null, "");
        assertThat(completed.statusCode()).isEqualTo(200);
        return userId;
    }

    private long topic() {
        return jdbc.queryForObject("select id from backend.topic where code='OS'", Long.class);
    }

    private long book(String key) {
        return jdbc.queryForObject(
                "select id from backend.book where demo_key=?", Long.class, key);
    }

    private String body(long userId, String level, Long targetBookId, int topK) {
        return "{\"userId\":" + userId + ",\"topicId\":" + topic()
                + ",\"challengeLevel\":\"" + level + "\",\"targetBookId\":"
                + (targetBookId == null ? "null" : targetBookId)
                + ",\"topK\":" + topK + "}";
    }

    private HttpResponse<String> create(String key, String body) throws Exception {
        return post("/api/recommendations", key, body);
    }

    private HttpResponse<String> get(long runId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/recommendations/" + runId))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return send(request);
    }

    private HttpResponse<String> post(String path, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return send(request.build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
