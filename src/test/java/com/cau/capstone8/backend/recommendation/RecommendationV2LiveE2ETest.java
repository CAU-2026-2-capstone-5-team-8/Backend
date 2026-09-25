package com.cau.capstone8.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real Python ML + Spring Backend + PostgreSQL opt-in flow. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
@EnabledIfEnvironmentVariable(named = "ML_CONTRACT_BASE_URL", matches = "https?://.+")
class RecommendationV2LiveE2ETest {
    private static final String SOURCE_HASH =
            "sha256:8a034f57a51c44ae5c3b0240b75812bb7654500d60d720da0eeae3389b3e74e0";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry properties) {
        properties.add("ml.mode", () -> "http");
        properties.add("ml.base-url", () -> System.getenv("ML_CONTRACT_BASE_URL"));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void assessmentProfileRankPersistenceAndReadbackUseRealMl() throws Exception {
        importRealCandidates();
        long userId = jdbc.queryForObject(
                "select id from backend.app_user where demo_key='catalog-user-v1'", Long.class);
        long topicId = topic();

        JsonNode assessment = json.readTree(postJson("/api/assessments", null,
                "{\"userId\":" + userId + ",\"topicId\":" + topicId + "}").body());
        long sessionId = assessment.path("id").asLong();
        for (JsonNode question : assessment.path("questions")) {
            HttpResponse<String> answer = putJson(
                    "/api/assessments/" + sessionId + "/answers/" + question.path("id").asLong(),
                    "{\"knowsConcept\":true}");
            assertThat(answer.statusCode()).isEqualTo(200);
        }
        HttpResponse<String> completion = postJson(
                "/api/assessments/" + sessionId + "/complete", null, "");
        assertThat(completion.statusCode()).withFailMessage(completion.body()).isEqualTo(200);
        assertThat(json.readTree(completion.body()).path("profile").path("calculationVersion").asString())
                .isEqualTo("reader-v1");

        HttpResponse<String> recommendation = postJson(
                "/api/recommendations", UUID.randomUUID().toString(),
                "{\"userId\":" + userId + ",\"topicId\":" + topicId
                        + ",\"challengeLevel\":\"BALANCED\",\"topK\":3}");
        assertThat(recommendation.statusCode()).withFailMessage(recommendation.body()).isEqualTo(201);
        JsonNode result = json.readTree(recommendation.body());
        assertThat(result.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(result.path("rankingMode").asString()).isEqualTo("PREREQUISITE_FIRST_V2");
        assertThat(result.path("modelVersion").asString())
                .isEqualTo("rank-prerequisite-first-v2");
        assertThat(result.path("diagnostics").path("topicCandidateCount").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.recommendation_run where id=? and ranking_mode=?",
                Integer.class, result.path("id").asLong(), "PREREQUISITE_FIRST_V2")).isEqualTo(1);

        HttpResponse<String> readback = get("/api/recommendations/" + result.path("id").asLong());
        assertThat(json.readTree(readback.body())).isEqualTo(result);
    }

    private void importRealCandidates() throws Exception {
        long topicId = topic();
        var resource = getClass().getResource("/fixtures/ml/scale-50-ranking-v2-candidates.jsonl");
        assertThat(resource).isNotNull();
        Map<String, String[]> catalog = Map.of(
                "isbn13:9780130319999", new String[]{"Operating Systems", "William Stallings"},
                "isbn13:9780132199087", new String[]{"Distributed operating systems", "Andrew S. Tanenbaum"},
                "isbn13:9780201498387", new String[]{"Distributed operating systems & algorithms", "Randy Chow, Theodore Johnson"});
        for (String line : Files.readAllLines(Path.of(resource.toURI()))) {
            JsonNode candidate = json.readTree(line);
            String mlBookId = candidate.path("book_id").asString();
            String[] metadata = catalog.get(mlBookId);
            Long bookId = jdbc.queryForObject("""
                    insert into backend.book(title,author,description,ml_book_id)
                    values (?,?,?,?) returning id
                    """, Long.class, metadata[0], metadata[1],
                    "Scale-50 real candidate projection live E2E book", mlBookId);
            jdbc.update("""
                    insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight)
                    values (?,?,true,1)
                    """, bookId, topicId);
            jdbc.update("""
                    insert into backend.book_ranking_v2_projection(
                        book_id,topic_id,version,active,topic_distribution,covered_concepts,
                        prerequisite_concepts,config_version,config_hash,
                        source_artifact_version,source_artifact_hash)
                    values (?,?,?,true,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb),?,?,?,?)
                    """, bookId, topicId, candidate.path("feature_version").asString(),
                    candidate.path("topic_distribution").toString(),
                    candidate.path("covered_concepts").toString(),
                    candidate.path("prerequisite_concepts").toString(),
                    candidate.path("config_version").asString(),
                    candidate.path("config_hash").asString(), "scale-50-matching-candidates-v1", SOURCE_HASH);
        }
    }

    private long topic() {
        return jdbc.queryForObject("select id from backend.topic where code='OS'", Long.class);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return send(request);
    }

    private HttpResponse<String> putJson(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request);
    }

    private HttpResponse<String> postJson(String path, String key, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        return send(builder.build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
