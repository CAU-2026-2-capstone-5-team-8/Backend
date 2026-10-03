package com.cau.capstone8.backend.learning;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class LearningRecommendationIntegrationTest {
    static final WireMockServer ML = new WireMockServer(0);
    static { ML.start(); }
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @DynamicPropertySource static void mlProperties(DynamicPropertyRegistry properties) {
        properties.add("ml.mode", () -> "http"); properties.add("ml.base-url", ML::baseUrl);
    }
    @AfterAll static void stopMl() { ML.stop(); }
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();
    long user, topic, profile, book;

    @BeforeEach void setup() {
        ML.resetAll();
        topic = jdbc.queryForObject("select id from backend.topic where code='OS'", Long.class);
        user = jdbc.queryForObject("insert into backend.app_user(display_name) values ('concept-user') returning id", Long.class);
        long session = jdbc.queryForObject("insert into backend.assessment_session(user_id,topic_id,status,completed_at) values (?,?,'COMPLETED',now()) returning id", Long.class, user, topic);
        String evidence = """
            {"conceptProfile":{"version":"concept-abilities-v2","abilities":[
              {"conceptId":"process","ability":"application","measurementContext":"prior-knowledge","responseCount":1,"correctCount":1}],
              "selfReports":[{"conceptId":"thread","positiveCount":1,"responseCount":1}]}}
            """;
        profile = jdbc.queryForObject("insert into backend.reader_profile(session_id,vocabulary,background_knowledge,comprehension,calculation_version,evidence) values (?,1,1,1,'reader-v1',cast(? as jsonb)) returning id", Long.class, session, evidence);
        String canonical = "synthetic-learning-" + user;
        book = jdbc.queryForObject("insert into backend.book(title,author,description,ml_book_id) values ('synthetic title','synthetic author','',?) returning id", Long.class, canonical);
        jdbc.update("insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight) values (?,?,true,1)", book, topic);
        jdbc.update("""
            insert into backend.book_ranking_v2_projection(book_id,topic_id,version,active,
                topic_distribution,covered_concepts,prerequisite_concepts,config_version,config_hash,
                source_artifact_version,source_artifact_hash)
            values (?,?,'book-v1',true,'{"operating-systems":1}',
                '[{"concept":"thread","weight":1}]','[]','features-v1',?,'synthetic-v1',?)
            """, book, topic, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64));
    }
    String result(String identity) {
        return """
            {"topicId":"operating-systems","ability":"application","modelVersion":"concept-learning-v1",
             "items":[{"bookId":"%s","rank":1,"status":"check-first","reviewOnly":false,
               "foundation":[{"conceptId":"process","state":"correct"}],
               "targets":[{"conceptId":"thread","state":"unmeasured"}],"coveredConcepts":["thread"],
               "inferredPrerequisites":["process"],"practiceConceptCount":0,"unmeasuredConceptCount":1,
               "reasons":["synthetic reason"]}],"candidateCount":1,"mappedCandidateCount":1,"unmappedCandidateCount":0}
            """.formatted(identity);
    }
    String request(String ability) {
        return "{\"userId\":" + user + ",\"topicId\":" + topic + ",\"profileId\":" + profile
                + ",\"ability\":\"" + ability + "\",\"topK\":5}";
    }
    HttpResponse<String> postJson(String key, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/learning-recommendations"))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void persistsAndReplaysWithObjectiveEvidenceOnly() throws Exception {
        ML.stubFor(post("/ml/learning-fit").willReturn(okJson(result("synthetic-learning-" + user))));
        String key = UUID.randomUUID().toString();
        var created = postJson(key, request("application"));
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        var node = json.readTree(created.body());
        assertThat(node.path("items").get(0).path("bookId").asLong()).isEqualTo(book);
        assertThat(node.path("profileId").asLong()).isEqualTo(profile);
        assertThat(json.readTree(postJson(key, request("application")).body())).isEqualTo(node);
        assertThat(postJson(key, request("meaning")).statusCode()).isEqualTo(409);
        var restored = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/learning-recommendations/" + node.path("id").asLong())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(restored.body())).isEqualTo(node);
        ML.verify(1, postRequestedFor(urlEqualTo("/ml/learning-fit"))
            .withRequestBody(matchingJsonPath("$.observations[0].conceptId", equalTo("process")))
            .withRequestBody(matchingJsonPath("$.observations", matching(".*process.*"))));
        String wire = ML.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(json.readTree(wire).path("observations").size()).isEqualTo(1);
        assertThat(wire).doesNotContain("selfReports", "vocabulary", "backgroundKnowledge");
    }
    @Test void rejectsUnrequestedBookAndRollsBack() throws Exception {
        ML.stubFor(post("/ml/learning-fit").willReturn(okJson(result("unknown-book"))));
        assertThat(postJson(UUID.randomUUID().toString(), request("application")).statusCode()).isEqualTo(502);
        assertThat(jdbc.queryForObject("select count(*) from backend.learning_recommendation where user_id=?", Integer.class, user)).isZero();
    }
}
