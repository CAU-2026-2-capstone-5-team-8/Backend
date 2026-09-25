package com.cau.capstone8.backend.recommendation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class RecommendationV2IntegrationTest {
    private static final String HASH =
            "sha256:6230d12facf94e46c1daa11d9d83b3929322af2f69a36614d3f553684bfb21d6";
    private static final String SOURCE_HASH =
            "sha256:8a034f57a51c44ae5c3b0240b75812bb7654500d60d720da0eeae3389b3e74e0";
    private static final WireMockServer ML = new WireMockServer(0);
    static {
        ML.start();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry properties) {
        properties.add("ml.mode", () -> "http");
        properties.add("ml.base-url", ML::baseUrl);
    }

    @AfterAll
    static void stopMl() {
        ML.stop();
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetMl() throws Exception {
        ML.resetAll();
        importRealCandidates();
    }

    @Test
    void persistsCanonicalV2ResultWithoutFakeScalarAndReplaysAndAcceptsFeedback() throws Exception {
        long userId = userWithHttpProfile();
        ML.stubFor(post("/ml/rank").willReturn(okJson(successJson())));
        String key = UUID.randomUUID().toString();

        HttpResponse<String> created = create(key, body(userId, null, 2));
        HttpResponse<String> replay = create(key, body(userId, null, 2));

        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(200);
        JsonNode response = json.readTree(created.body());
        assertThat(json.readTree(replay.body())).isEqualTo(response);
        assertThat(response.path("rankingMode").asString()).isEqualTo("PREREQUISITE_FIRST_V2");
        assertThat(response.path("modelVersion").asString())
                .isEqualTo("rank-prerequisite-first-v2");
        assertThat(response.path("diagnostics").path("personalizedCandidateShortage").asInt())
                .isEqualTo(1);
        JsonNode item = response.path("items").get(0);
        assertThat(item.path("score").isNull()).isTrue();
        assertThat(item.path("title").asString()).isEqualTo("Distributed operating systems");
        assertThat(item.path("author").asString()).isEqualTo("Andrew S. Tanenbaum");
        assertThat(item.path("prerequisiteReadiness").asDouble()).isEqualTo(0.9);
        long runId = response.path("id").asLong();
        assertThat(jdbc.queryForObject(
                "select score is null and prerequisite_readiness=0.9 "
                        + "from backend.recommendation_item where run_id=?",
                Boolean.class, runId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select ranking_config_hash from backend.recommendation_run where id=?",
                String.class, runId)).isEqualTo(HASH);

        long itemId = item.path("id").asLong();
        HttpResponse<String> feedback = postJson(
                "/api/recommendations/" + itemId + "/feedback", null,
                "{\"userId\":" + userId + ",\"helpful\":true,\"comment\":\"v2 좋아요\"}");
        assertThat(feedback.statusCode()).isEqualTo(201);
        ML.verify(1, postRequestedFor(urlEqualTo("/ml/rank")));
    }

    @Test
    void treatsEmptyPersonalizedResultAsSuccessfulShortage() throws Exception {
        long userId = userWithHttpProfile();
        ML.stubFor(post("/ml/rank").willReturn(okJson(emptyJson())));

        HttpResponse<String> created = create(
                UUID.randomUUID().toString(), body(userId, null, 3));

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode response = json.readTree(created.body());
        assertThat(response.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(response.path("items").isEmpty()).isTrue();
        assertThat(response.path("diagnostics").path("personalizedCandidateShortage").asInt())
                .isEqualTo(3);
    }

    @Test
    void mapsTarget422ToSafePersistedDomainFailure() throws Exception {
        long userId = userWithHttpProfile();
        long target = jdbc.queryForObject(
                "select id from backend.book where ml_book_id='isbn13:9780132199087'",
                Long.class);
        ML.stubFor(post("/ml/rank").willReturn(aResponse().withStatus(422)
                .withBody("secret upstream reason")));

        HttpResponse<String> response = create(
                UUID.randomUUID().toString(), body(userId, target, 5));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json.readTree(response.body()).path("code").asString())
                .isEqualTo("ML_RANK_TARGET_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("secret");
        assertThat(jdbc.queryForObject(
                "select last_failure_code from backend.recommendation_run where user_id=? "
                        + "order by id desc limit 1", String.class, userId))
                .isEqualTo("ML_RANK_TARGET_UNAVAILABLE");
    }

    @Test
    void rejectsIncompleteSucceededV2ProvenanceAndItemShape() throws Exception {
        long userId = userWithHttpProfile();
        ML.stubFor(post("/ml/rank").willReturn(okJson(successJson())));

        HttpResponse<String> created = create(
                UUID.randomUUID().toString(), body(userId, null, 2));

        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        long runId = json.readTree(created.body()).path("id").asLong();
        long itemId = json.readTree(created.body()).path("items").get(0).path("id").asLong();
        assertThatThrownBy(() -> jdbc.update(
                        "update backend.recommendation_run set ranking_config_hash=null where id=?",
                        runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "update backend.recommendation_item set prerequisite_total_count=null where id=?",
                        itemId))
                .isInstanceOf(DataIntegrityViolationException.class);
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
            List<Long> existing = jdbc.query(
                    "select id from backend.book where ml_book_id=?", (rs, row) -> rs.getLong(1),
                    mlBookId);
            Long bookId = existing.isEmpty()
                    ? jdbc.queryForObject("""
                            insert into backend.book(title,author,description,ml_book_id)
                            values (?,?,?,?) returning id
                            """, Long.class, metadata[0], metadata[1],
                            "Scale-50 real candidate projection test book", mlBookId)
                    : existing.getFirst();
            jdbc.update("""
                    insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight)
                    values (?,?,true,1) on conflict(book_id,topic_id) do nothing
                    """, bookId, topicId);
            jdbc.update("""
                    insert into backend.book_ranking_v2_projection(
                        book_id,topic_id,version,active,topic_distribution,covered_concepts,
                        prerequisite_concepts,config_version,config_hash,
                        source_artifact_version,source_artifact_hash)
                    values (?,?,?,true,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb),?,?,?,?)
                    on conflict(book_id,topic_id,version) do nothing
                    """, bookId, topicId, candidate.path("feature_version").asString(),
                    candidate.path("topic_distribution").toString(),
                    candidate.path("covered_concepts").toString(),
                    candidate.path("prerequisite_concepts").toString(),
                    candidate.path("config_version").asString(),
                    candidate.path("config_hash").asString(), "scale-50-matching-candidates-v1", SOURCE_HASH);
        }
    }

    private long userWithHttpProfile() {
        long userId = jdbc.queryForObject(
                "insert into backend.app_user(display_name) values ('v2-user') returning id", Long.class);
        long sessionId = jdbc.queryForObject("""
                insert into backend.assessment_session(user_id,topic_id,status,completed_at)
                values (?,?,'COMPLETED',now()) returning id
                """, Long.class, userId, topic());
        String evidence = """
                {"method":"ml-http-reader-profile","profileVersion":"reader-v1",
                 "configVersion":"reader-config-v1","configHash":"%s",
                 "dimensionDetails":[],"conceptReadiness":[
                   {"conceptId":"process","score":1.0,"responseCount":1,"earnedWeight":1.0,"availableWeight":1.0},
                   {"conceptId":"concurrency","score":0.8,"responseCount":1,"earnedWeight":0.8,"availableWeight":1.0},
                   {"conceptId":"synchronization","score":0.8,"responseCount":1,"earnedWeight":0.8,"availableWeight":1.0},
                   {"conceptId":"memory management","score":0.5,"responseCount":1,"earnedWeight":0.5,"availableWeight":1.0}]}
                """.formatted(HASH);
        jdbc.update("""
                insert into backend.reader_profile(session_id,vocabulary,background_knowledge,
                    comprehension,calculation_version,evidence)
                values (?,?,?,?,?,cast(? as jsonb))
                """, sessionId, 0.7, 0.7, 0.7, "reader-v1", evidence);
        return userId;
    }

    private long topic() {
        return jdbc.queryForObject("select id from backend.topic where code='OS'", Long.class);
    }

    private String body(long userId, Long targetBookId, int topK) {
        return "{\"userId\":" + userId + ",\"topicId\":" + topic()
                + ",\"challengeLevel\":\"BALANCED\",\"targetBookId\":"
                + (targetBookId == null ? "null" : targetBookId) + ",\"topK\":" + topK + "}";
    }

    private HttpResponse<String> create(String key, String body) throws Exception {
        return postJson("/api/recommendations", key, body);
    }

    private HttpResponse<String> postJson(String path, String key, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String successJson() {
        return ("""
                {"userId":%d,"topicId":"operating-systems","items":[{
                  "bookId":"isbn13:9780132199087","rank":1,"availabilityStatus":"personalizable",
                  "prerequisiteReadiness":0.9,"prerequisiteAssessedCount":2,
                  "prerequisiteTotalCount":4,"prerequisiteCoverage":0.5,
                  "directLearningOpportunity":0.2,"directAssessedCount":2,
                  "directTotalCount":4,"directCoverage":0.5,
                  "coveredConcepts":["distributed systems","file system","process","synchronization"],
                  "inferredPrerequisites":["concurrency","process","storage","thread"],
                  "reasons":["선행 개념 근거","학습 기회 근거"],
                  "modelVersion":"rank-prerequisite-first-v2","bookFeatureVersion":"book-v1",
                  "bookConfigVersion":"features-v1","bookConfigHash":"%s"}],
                 "diagnostics":{"requestedLimit":2,"returnedCount":1,"topicCandidateCount":3,
                   "personalizableCount":1,"conceptOnlyCount":1,"evidenceUnavailableCount":1,
                   "fallbackCount":2,"personalizedCandidateShortage":1},%s}
                """).formatted(latestUserId(), HASH, provenance());
    }

    private String emptyJson() {
        return ("""
                {"userId":%d,"topicId":"operating-systems","items":[],
                 "diagnostics":{"requestedLimit":3,"returnedCount":0,"topicCandidateCount":3,
                   "personalizableCount":0,"conceptOnlyCount":2,"evidenceUnavailableCount":1,
                   "fallbackCount":3,"personalizedCandidateShortage":3},%s}
                """).formatted(latestUserId(), provenance());
    }

    private String provenance() {
        return ("""
                "modelVersion":"rank-prerequisite-first-v2","configVersion":"ranking-v2-config-v1",
                "configHash":"%s","conceptGraphVersion":"concept-graph-v1","conceptGraphHash":"%s",
                "graphReviewVersion":"concept-graph-reviews-v1","graphReviewHash":"%s",
                "readerProfileVersion":"reader-v1","readerConfigVersion":"reader-config-v1",
                "readerConfigHash":"%s"
                """).formatted(HASH, HASH, HASH, HASH);
    }

    private long latestUserId() {
        return jdbc.queryForObject("select max(id) from backend.app_user", Long.class);
    }
}
