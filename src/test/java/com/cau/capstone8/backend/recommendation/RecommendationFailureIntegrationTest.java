package com.cau.capstone8.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.cau.capstone8.backend.integration.ml.MlGateway;
import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import com.cau.capstone8.backend.integration.ml.MlProfileRequest;
import com.cau.capstone8.backend.integration.ml.MlProfileResult;
import com.cau.capstone8.backend.integration.ml.MlRankRequest;
import com.cau.capstone8.backend.integration.ml.MlRankResult;
import com.cau.capstone8.backend.integration.ml.StubMlGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(RecommendationFailureIntegrationTest.GatewayConfiguration.class)
class RecommendationFailureIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ControlledGateway gateway;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void failedMlCallPersistsSanitizedErrorAndReplaysIt() throws Exception {
        long userId = userWithProfile();
        gateway.expireDuringRanking = false;
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = create(userId, key);
        HttpResponse<String> replay = create(userId, key);
        long runId = runId(userId, key);

        assertThat(first.statusCode()).isEqualTo(502);
        assertThat(replay.statusCode()).isEqualTo(502);
        assertThat(json.readTree(first.body()).path("code").asString())
                .isEqualTo("ML_TEST_FAILURE");
        assertThat(json.readTree(replay.body()).path("code").asString())
                .isEqualTo("ML_TEST_FAILURE");
        assertThat(first.body()).doesNotContain("private upstream secret");
        JsonNode saved = json.readTree(get(runId).body());
        assertThat(saved.path("status").asString()).isEqualTo("FAILED");
        assertThat(saved.path("failureMessage").asString())
                .doesNotContain("private upstream secret");
        assertThat(saved.path("items").size()).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.recommendation_item where run_id=?",
                Integer.class, runId)).isZero();
    }

    @Test
    void lateMlResultCannotFinalizeExpiredAttempt() throws Exception {
        long userId = userWithProfile();
        gateway.expireDuringRanking = true;
        String key = UUID.randomUUID().toString();

        HttpResponse<String> response = create(userId, key);
        long runId = runId(userId, key);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString())
                .isEqualTo("RECOMMENDATION_EXPIRED");
        JsonNode saved = json.readTree(get(runId).body());
        assertThat(saved.path("status").asString()).isEqualTo("FAILED");
        assertThat(saved.path("items").size()).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.recommendation_item where run_id=?",
                Integer.class, runId)).isZero();
    }

    @Test
    void concurrentRequestWithSameKeyConflictsWhileFirstIsProcessing() throws Exception {
        long userId = userWithProfile();
        String key = UUID.randomUUID().toString();
        gateway.expireDuringRanking = false;
        gateway.blockDuringRanking = true;
        gateway.started = new CountDownLatch(1);
        gateway.release = new CountDownLatch(1);
        try {
            CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return create(userId, key);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            assertThat(gateway.started.await(5, TimeUnit.SECONDS)).isTrue();

            HttpResponse<String> competing = create(userId, key);

            assertThat(competing.statusCode()).isEqualTo(409);
            assertThat(json.readTree(competing.body()).path("code").asString())
                    .isEqualTo("RECOMMENDATION_CONFLICT");
            gateway.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            assertThat(jdbc.queryForObject(
                    "select count(*) from backend.recommendation_run "
                            + "where user_id=? and request_key=?",
                    Integer.class, userId, key)).isEqualTo(1);
        } finally {
            gateway.release.countDown();
            gateway.blockDuringRanking = false;
        }
    }

    private long userWithProfile() {
        long userId = jdbc.queryForObject(
                "insert into backend.app_user(display_name) values ('rank-failure') returning id",
                Long.class);
        long topicId = jdbc.queryForObject(
                "select id from backend.topic where code='OS'", Long.class);
        long sessionId = jdbc.queryForObject(
                "insert into backend.assessment_session(user_id,topic_id,status,completed_at) "
                        + "values (?,?,'COMPLETED',now()) returning id",
                Long.class, userId, topicId);
        jdbc.update("insert into backend.reader_profile(session_id,vocabulary,background_knowledge,"
                        + "comprehension,calculation_version,evidence) "
                        + "values (?,0.5,0.5,0.5,'stub-profile-v1','{}'::jsonb)",
                sessionId);
        return userId;
    }

    private long runId(long userId, String key) {
        return jdbc.queryForObject(
                "select id from backend.recommendation_run where user_id=? and request_key=?",
                Long.class, userId, key);
    }

    private HttpResponse<String> create(long userId, String key) throws Exception {
        long topicId = jdbc.queryForObject(
                "select id from backend.topic where code='OS'", Long.class);
        String body = "{\"userId\":" + userId + ",\"topicId\":" + topicId
                + ",\"challengeLevel\":\"BALANCED\",\"topK\":2}";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/recommendations"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request);
    }

    private HttpResponse<String> get(long runId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/recommendations/" + runId))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean
        @Primary
        ControlledGateway controlledGateway(JdbcTemplate jdbc) {
            return new ControlledGateway(jdbc);
        }
    }

    static class ControlledGateway implements MlGateway {
        private final JdbcTemplate jdbc;
        volatile boolean expireDuringRanking;
        volatile boolean blockDuringRanking;
        volatile CountDownLatch started;
        volatile CountDownLatch release;

        ControlledGateway(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public MlProfileResult calculateProfile(MlProfileRequest request) {
            return new StubMlGateway().calculateProfile(request);
        }

        @Override
        public MlRankResult rankBooks(MlRankRequest request) {
            if (blockDuringRanking) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test gateway wait timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test gateway interrupted", exception);
                }
                return new StubMlGateway().rankBooks(request);
            }
            if (!expireDuringRanking) {
                throw new MlGatewayException("ML_TEST_FAILURE", "private upstream secret");
            }
            jdbc.update("update backend.recommendation_run "
                            + "set processing_expires_at=now()-interval '1 second' "
                            + "where attempt_id=?",
                    request.requestId());
            return new StubMlGateway().rankBooks(request);
        }
    }
}
