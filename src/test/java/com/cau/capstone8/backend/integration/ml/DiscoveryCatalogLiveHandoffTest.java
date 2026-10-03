package com.cau.capstone8.backend.integration.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cau.capstone8.backend.assessment.LocalAssessmentBootstrap;
import com.cau.capstone8.backend.book.LocalCatalogImportService;
import com.cau.capstone8.backend.book.DiscoveryCatalogImportService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in real packet + Python ML, with a new empty PostgreSQL owned by this test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfEnvironmentVariable(named="ML_CONTRACT_BASE_URL",matches="https?://.+")
@EnabledIfEnvironmentVariable(named="LA_HANDOFF_DIR",matches=".+")
@EnabledIfEnvironmentVariable(named="DISCOVERY_HANDOFF_DIR",matches=".+")
class DiscoveryCatalogLiveHandoffTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @DynamicPropertySource static void ml(DynamicPropertyRegistry registry) {
        registry.add("ml.mode",()->"http");
        registry.add("ml.base-url",()->System.getenv("ML_CONTRACT_BASE_URL"));
    }
    @LocalServerPort int port;
    @Autowired LocalCatalogImportService catalog;
    @Autowired DiscoveryCatalogImportService discovery;
    @Autowired LocalAssessmentBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    // Spy delegates to the actual HTTP gateway; it never replaces ML calculations.
    @MockitoSpyBean HttpMlGateway gateway;
    final JsonMapper json = new JsonMapper();
    final HttpClient client = HttpClient.newHttpClient();

    @Test void importedOriginalCandidatesAndServerGradingReachRealMlAndPersistOnce() throws Exception {
        Path packet = Path.of(System.getenv("LA_HANDOFF_DIR"));
        var registered = catalog.importManifest(packet.resolve("catalog-manifest.json"));
        assertThat(catalog.importManifest(packet.resolve("catalog-manifest.json")).bookIds()).isEqualTo(registered.bookIds());
        var bank = bootstrap.importManifest(packet.resolve("assessment-manifest.json"));
        assertThat(bootstrap.importManifest(packet.resolve("assessment-manifest.json"))).isEqualTo(bank);
        var session = call("POST","/api/assessments",json.writeValueAsString(
                java.util.Map.of("userId",bank.userId(),"topicId",bank.topicId())),null,201);
        long sessionId = session.path("id").asLong();
        assertThat(session.path("questions").size()).isEqualTo(9);
        for(JsonNode q:session.path("questions")) {
            assertThat(q.has("correctChoiceIndex") || q.has("explanation")).isFalse();
            String body=q.path("answerMode").asString().equals("SELF_REPORT")
                    ? "{\"knowsConcept\":true}" : "{\"selectedChoiceIndex\":0}";
            call("PUT","/api/assessments/"+sessionId+"/answers/"+q.path("id").asLong(),body,null,200);
        }
        var completed=call("POST","/api/assessments/"+sessionId+"/complete","",null,200);
        assertThat(call("POST","/api/assessments/"+sessionId+"/complete","",null,200)).isEqualTo(completed);
        assertThat(completed.path("profile").path("calculationVersion").asString()).isEqualTo("reader-v1");
        assertThat(completed.path("profile").path("comprehension").asDouble()).isCloseTo(2.0/3.0,org.assertj.core.data.Offset.offset(1e-9));
        var profileRequest=ArgumentCaptor.forClass(MlProfileRequest.class);
        verify(gateway,times(1)).calculateProfile(profileRequest.capture());
        assertThat(profileRequest.getValue().answers().stream().filter(a->a.conceptId().equals("matrix")
                && a.measurementArea()==com.cau.capstone8.backend.assessment.MeasurementArea.COMPREHENSION).findFirst().orElseThrow().correct()).isFalse();
        String rankBody=json.writeValueAsString(java.util.Map.of("userId",bank.userId(),"topicId",bank.topicId(),
                "challengeLevel","BALANCED","topK",5));
        String key="la-test-session-"+sessionId;
        var ranked=call("POST","/api/recommendations",rankBody,key,201);
        assertThat(ranked.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(ranked.path("profileId").asLong()).isEqualTo(completed.path("profile").path("id").asLong());
        assertThat(ranked.path("items").size()).isEqualTo(3);
        assertThat(ranked.path("diagnostics").path("personalizedCandidateShortage").asInt()).isEqualTo(2);
        var rankRequest=ArgumentCaptor.forClass(MlRankV2Request.class);
        verify(gateway,times(1)).rankBooksV2(rankRequest.capture());
        var wire=json.readTree(json.writeValueAsString(MlRankV2HttpContract.toWire(rankRequest.getValue())));
        assertThat(wire.path("candidateBooks")).isEqualTo(json.readTree(Files.readString(packet.resolve("wire-candidates.json"))));
        assertThat(wire.path("readerProfile").path("configHash").asString()).isEqualTo(
                completed.path("profile").path("evidence").path("configHash").asString());
        assertThat(call("POST","/api/recommendations",rankBody,key,200)).isEqualTo(ranked);
        assertThat(call("GET","/api/recommendations/"+ranked.path("id").asLong(),null,null,200)).isEqualTo(ranked);
        verify(gateway,times(1)).rankBooksV2(org.mockito.ArgumentMatchers.any());
        assertThat(jdbc.queryForObject("select count(*) from backend.reader_profile",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from backend.recommendation_run",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from backend.recommendation_item where projection_id is not null and book_feature_version='book-v1'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select correct from backend.assessment_answer where answer_mode='MULTIPLE_CHOICE'",Boolean.class)).isFalse();
        // Expand the same database after storing a real three-book run.
        Path bulk=Path.of(System.getenv("DISCOVERY_HANDOFF_DIR"));
        var imported=discovery.importManifest(bulk.resolve("catalog-manifest.json"));
        assertThat(imported.bookCount()).isEqualTo(471);assertThat(imported.tocBookCount()).isEqualTo(439);
        assertThat(imported.projectionCount()).isEqualTo(83);
        assertThat(discovery.importManifest(bulk.resolve("catalog-manifest.json")).replayed()).isTrue();
        assertThat(call("GET","/api/recommendations/"+ranked.path("id").asLong(),null,null,200)).isEqualTo(ranked);
        for(var projection:registered.projectionIds().values())
            assertThat(jdbc.queryForObject("select source_artifact_version from backend.book_ranking_v2_projection where id=?",String.class,projection)).isEqualTo(registered.snapshotId());
        var summary=call("GET","/api/books/summary",null,null,200);
        assertThat(summary.path("bookCount").asInt()).isEqualTo(471);
        assertThat(summary.path("tocBookCount").asInt()).isEqualTo(439);
        assertThat(summary.path("conceptBookCount").asInt()).isEqualTo(11);
        var ids=new java.util.HashSet<Long>();
        for(int page=0;page<5;page++){
            var catalogPage=call("GET","/api/books?page="+page+"&size=100",null,null,200);
            assertThat(catalogPage.path("totalElements").asInt()).isEqualTo(471);
            for(var book:catalogPage.path("content"))assertThat(ids.add(book.path("id").asLong())).isTrue();
        }
        assertThat(ids).hasSize(471);
        for(var topic:summary.path("topics")){
            var listed=call("GET","/api/books?topicId="+topic.path("id").asLong()+"&size=100",null,null,200);
            assertThat(listed.path("totalElements").asLong()).isEqualTo(topic.path("bookCount").asLong());
            assertThat(topic.path("assessmentReady").asBoolean()).isEqualTo(topic.path("mlTopicId").asString().equals("linear-algebra"));
        }
        var expanded=call("POST","/api/recommendations",rankBody,key+"-discovery",201);
        assertThat(expanded.path("items").size()).isEqualTo(5);
        assertThat(expanded.path("diagnostics").path("topicCandidateCount").asInt()).isEqualTo(83);
        assertThat(expanded.path("diagnostics").path("personalizableCount").asInt()).isEqualTo(6);
        verify(gateway,times(2)).rankBooksV2(rankRequest.capture());
        var expandedWire=json.readTree(json.writeValueAsString(MlRankV2HttpContract.toWire(rankRequest.getValue())));
        assertThat(expandedWire.path("candidateBooks").size()).isEqualTo(83);
        assertThat(candidateMap(expandedWire.path("candidateBooks"))).isEqualTo(
                candidateMap(json.readTree(Files.readString(bulk.resolve("wire-candidates.json")))));
        assertThat(call("GET","/api/recommendations/"+expanded.path("id").asLong(),null,null,200)).isEqualTo(expanded);
        assertThat(call("POST","/api/recommendations",rankBody,key+"-discovery",200)).isEqualTo(expanded);
        assertThat(jdbc.queryForObject("select count(*) from backend.recommendation_run",Integer.class)).isEqualTo(2);

    }

    java.util.Map<String,JsonNode> candidateMap(JsonNode candidates) {
        return java.util.stream.StreamSupport.stream(candidates.spliterator(),false)
                .collect(java.util.stream.Collectors.toMap(c->c.path("bookId").asString(),c->c));
    }

    JsonNode call(String method,String path,String body,String key,int expected) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json");
        if(key!=null) request.header("Idempotency-Key",key);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }
}
