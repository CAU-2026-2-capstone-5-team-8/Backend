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
        return postJson(key, body, "");
    }
    HttpResponse<String> postJson(String key, String body, String suffix) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/learning-recommendations" + suffix))
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

    @Test void v2PreservesChecklistAndEvidenceInSnapshotAndReplay() throws Exception {
        String evidence = """
            {"concept_id":"thread","evidence_id":"fixture-evidence","source_id":"fixture-source",
             "source_url":"https://example.org/book","evidence_type":"toc_exact","edition_relation":"exact",
             "toc_path":["Threads"],"matching_alias":"Threads","match_method":"normalized_alias_span_v2",
             "provenance_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
            """;
        jdbc.update("update backend.book_ranking_v2_projection set covered_concepts=cast(? as jsonb) where book_id=?",
                "[{\"concept\":\"thread\",\"weight\":1,\"evidence\":[" + evidence + "]}]", book);
        String wireEvidence = evidence.replace("concept_id", "conceptId").replace("evidence_id", "evidenceId")
                .replace("source_id", "sourceId").replace("source_url", "sourceUrl").replace("evidence_type", "evidenceType")
                .replace("edition_relation", "editionRelation").replace("toc_path", "tocPath")
                .replace("matching_alias", "matchingAlias").replace("match_method", "matchMethod").replace("provenance_hash", "provenanceHash");
        ML.stubFor(post("/ml/learning-fit").willReturn(okJson(v2Result(wireEvidence))));
        String key = UUID.randomUUID().toString();
        var created = postJson(key, request("application"), "?modelVersion=concept-learning-v2");
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        var node = json.readTree(created.body());
        assertThat(node.path("items").get(0).path("readingChecklist").path("concepts").get(1)
                .path("evidence").get(0).path("tocPath").get(0).asString()).isEqualTo("Threads");
        assertThat(json.readTree(postJson(key, request("application"), "?modelVersion=concept-learning-v2").body())).isEqualTo(node);
        assertThat(postJson(key, request("application")).statusCode()).isEqualTo(409);
        String wire = ML.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(json.readTree(wire).path("modelVersion").asString()).isEqualTo("concept-learning-v2");
        assertThat(json.readTree(wire).path("candidateBooks").get(0).path("conceptEvidence").size()).isEqualTo(1);
        var restored = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/learning-recommendations/" + node.path("id").asLong())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(restored.body())).isEqualTo(node);
        assertThat(jdbc.queryForObject("select input_snapshot::text from backend.learning_recommendation where id=?",
                String.class, node.path("id").asLong())).contains("fixture-evidence");
        ML.verify(1, postRequestedFor(urlEqualTo("/ml/learning-fit")));
    }

    @Test void rejectsTamperedV2ObservationWithoutSaving() throws Exception {
        ML.stubFor(post("/ml/learning-fit").willReturn(okJson(v2Result(null).replace("\"correctCount\":1", "\"correctCount\":0"))));
        var response = postJson(UUID.randomUUID().toString(), request("application"), "?modelVersion=concept-learning-v2");
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(jdbc.queryForObject("select count(*) from backend.learning_recommendation where user_id=?", Integer.class, user)).isZero();
    }

    String v2Result(String evidence) {
        String checklist = """
            {"version":"reading-checklist-v2","interpretation":"observed_answers_not_calibrated_mastery",
             "orderPolicy":"prerequisites-first-stable-concept-id","limitations":["fixture limitation"],"concepts":[
              {"conceptId":"process","isCovered":false,"isPrerequisite":true,"state":"correct",
               "responseCount":1,"correctCount":1,"nextAction":"continue-learning","dependsOn":[],"requiredFor":["thread"],"evidence":[],"teachingSufficiency":"unverified"},
              {"conceptId":"thread","isCovered":true,"isPrerequisite":false,"state":"unmeasured",
               "responseCount":null,"correctCount":null,"nextAction":"assess-concept","dependsOn":["process"],"requiredFor":[],"evidence":[%s],"teachingSufficiency":"unverified"}]}
            """.formatted(evidence == null ? "" : evidence);
        return result("synthetic-learning-" + user).replace("concept-learning-v1", "concept-learning-v2")
                .replace("\"reasons\":[", "\"foundationStatus\":\"observed-graph-candidates\",\"sourceArtifactVersion\":\"synthetic-v1\",\"sourceArtifactHash\":\"sha256:" + "b".repeat(64) + "\",\"reasons\":[")
                .replace("\"status\":\"check-first\"", "\"status\":\"ready-to-explore\"")
                .replace("\"reasons\":[", "\"internalPrerequisites\":[],\"externalPrerequisites\":[\"process\"],\"readingChecklist\":" + checklist + ",\"reasons\":[");
    }

    @Test void rejectsContradictoryV2SummaryAndProvenance() throws Exception {
        for (String invalid : new String[] {
                v2Result(null).replace("\"foundation\":[{\"conceptId\":\"process\",\"state\":\"correct\"}",
                        "\"foundation\":[{\"conceptId\":\"process\",\"state\":\"unmeasured\"}"),
                v2Result(null).replace("\"sourceArtifactVersion\":\"synthetic-v1\"", "\"sourceArtifactVersion\":\"invented\"")}) {
            ML.stubFor(post("/ml/learning-fit").willReturn(okJson(invalid)));
            assertThat(postJson(UUID.randomUUID().toString(), request("application"), "?modelVersion=concept-learning-v2").statusCode()).isEqualTo(502);
        }
        assertThat(jdbc.queryForObject("select count(*) from backend.learning_recommendation where user_id=?", Integer.class, user)).isZero();
    }

    @Test void rejectsHiddenCoveredPrerequisiteDespiteRetainedDependencyEdges() throws Exception {
        jdbc.update("update backend.book_ranking_v2_projection set covered_concepts='[{\"concept\":\"thread\",\"weight\":1},{\"concept\":\"process\",\"weight\":1}]'::jsonb where book_id=?", book);
        String invalid = v2Result(null)
                .replace("\"coveredConcepts\":[\"thread\"]", "\"coveredConcepts\":[\"process\",\"thread\"]")
                .replace("\"targets\":[", "\"targets\":[{\"conceptId\":\"process\",\"state\":\"correct\"},")
                .replace("\"foundation\":[{\"conceptId\":\"process\",\"state\":\"correct\"}]", "\"foundation\":[]")
                .replace("\"inferredPrerequisites\":[\"process\"]", "\"inferredPrerequisites\":[]")
                .replace("\"externalPrerequisites\":[\"process\"]", "\"externalPrerequisites\":[]")
                .replace("observed-graph-candidates", "not-established")
                .replace("ready-to-explore", "check-first")
                .replace("\"isCovered\":false,\"isPrerequisite\":true", "\"isCovered\":true,\"isPrerequisite\":false");
        ML.stubFor(post("/ml/learning-fit").willReturn(okJson(invalid)));
        assertThat(postJson(UUID.randomUUID().toString(), request("application"), "?modelVersion=concept-learning-v2").statusCode()).isEqualTo(502);
        assertThat(jdbc.queryForObject("select count(*) from backend.learning_recommendation where user_id=?", Integer.class, user)).isZero();
    }
}
