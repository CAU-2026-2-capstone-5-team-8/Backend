package com.cau.capstone8.backend.topic;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URI;
import java.net.http.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"app.auth.mode=required",
        "topic-preparation.enabled=true", "topic-content-preparation.enabled=true", "topic-question-preparation.enabled=true", "topic-preparation.initial-delay-ms=3600000"})
@Testcontainers
class TopicRequestIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TopicPreparationService preparation;
    @Autowired TopicContentPreparationService content;
    @Autowired TopicQuestionPreparationService questions;
    @Autowired TopicPreparationWorker worker;
    @Autowired TopicDiscoveryService discoveries;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newHttpClient();
    final String aliceToken = "a".repeat(43), bobToken = "b".repeat(43);
    long alice, bob;
    final String scope = "TCP/IP와 라우팅의 원리를 공부하고 싶어요.";

    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE backend.app_user,backend.topic,backend.book,backend.discovery_catalog_import,backend.catalog_selection_snapshot CASCADE");
        alice = user("Alice", aliceToken); bob = user("Bob", bobToken);
        jdbc.update("INSERT INTO backend.topic(id,code,name) VALUES (9001,'CS','컴퓨터과학'),(9003,'MAT','수학')");
        jdbc.update("INSERT INTO backend.topic(id,code,name,parent_id,ml_topic_id) VALUES (9002,'OS','운영체제',9001,'operating-systems')");
    }
    long user(String name, String token) {
        long id = jdbc.queryForObject("INSERT INTO backend.app_user(display_name) VALUES (?) RETURNING id", Long.class, name);
        jdbc.update("INSERT INTO backend.user_account(user_id,email,password_hash) VALUES (?,?,?)", id, name.toLowerCase(Locale.ROOT)+"@example.com", "synthetic-not-for-login");
        jdbc.update("INSERT INTO backend.account_session(token_hash,user_id,expires_at) VALUES (?,?,now()+interval '1 hour')",
                hashToken(token), id);
        return id;
    }
    String hashToken(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    Map<String,Object> input(String name) { return Map.of("categoryId",9001,"name",name,"scope",scope); }
    HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if (token != null) request.header("Authorization","Bearer "+token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void newFieldDiscoveryUsesOnlyOwnedObservedScopesAndQueuesWithoutActivatingDiagnosis() throws Exception {
        assertThat(call("POST","/api/topic-requests/discover",null,Map.of("name","경제")).statusCode()).isEqualTo(401);
        var started=call("POST","/api/topic-requests/discover",aliceToken,Map.of("name","경제"));
        assertThat(started.statusCode()).isEqualTo(202);
        UUID id=UUID.fromString(json.readTree(started.body()).path("id").asString());
        assertThat(json.readTree(call("POST","/api/topic-requests/discover",aliceToken,Map.of("name","경제")).body()).path("id").asString()).isEqualTo(id.toString());
        assertThat(call("GET","/api/topic-requests/discover/"+id,bobToken,null).statusCode()).isEqualTo(404);
        var discovery=discoveries.claim();assertThat(discovery.id()).isEqualTo(id);
        assertThat(discoveries.claim()).isNull();assertThat(preparation.claim()).isNull();
        discoveries.complete(discovery,json.valueToTree(Map.of("status","FOUND","provider","yes24","query","경제","groups",List.of(
            Map.of("category","국내도서-경제 경영","bookCount",2,"samples",List.of(Map.of("title","경제학 입문","authors",List.of("저자")))),
            Map.of("category","국내도서-대학교재","bookCount",1,"samples",List.of())))));
        assertThat(call("POST","/api/topic-requests",bobToken,Map.of("name","경제","discoveryId",id,"providerCategory","국내도서-경제 경영")).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","경제","discoveryId",id,"providerCategory","국내도서-소설")).statusCode()).isEqualTo(400);
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","aa","discoveryId",id,"providerCategory","국내도서-경제 경영")).statusCode()).isEqualTo(400);
        var valid=Map.of("name","경제","discoveryId",id,"providerCategory","국내도서-경제 경영");
        var submitted=call("POST","/api/topic-requests",aliceToken,valid);assertThat(submitted.statusCode()).isEqualTo(201);
        assertThat(call("POST","/api/topic-requests",aliceToken,valid).statusCode()).isEqualTo(200);
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","경제","discoveryId",id,"providerCategory","국내도서-대학교재")).statusCode()).isEqualTo(201);
        var job=preparation.claim();var selected=json.readTree(job.discoveryJson());
        assertThat(selected.path("slug").asString()).startsWith("search-");
        job=preparation.classifyDiscovery(job,selected.path("parentCode").asString(),selected.path("parentName").asString());
        assertThat(preparation.resolved(job,selected.path("slug").asString())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_content_preparation",Integer.class)).isZero();
        preparation.fail(job);
        var request=json.readTree(submitted.body()).path("request");
        assertThat(call("POST","/api/topic-requests/"+request.path("id").asLong()+"/retry",aliceToken,Map.of("name","미시경제")).statusCode()).isEqualTo(400);
    }
    @Test void emptyBookDiscoveryDoesNotCreateAFieldOrAcceptAForgedScope() throws Exception {
        var started=call("POST","/api/topic-requests/discover",aliceToken,Map.of("name","aa"));
        UUID id=UUID.fromString(json.readTree(started.body()).path("id").asString());
        var job=discoveries.claim();
        discoveries.complete(job,json.valueToTree(Map.of("status","NO_RESULTS","provider","yes24","query","aa","groups",List.of())));
        assertThat(json.readTree(call("GET","/api/topic-requests/discover/"+id,aliceToken,null).body()).path("status").asString()).isEqualTo("NO_RESULTS");
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","aa","discoveryId",id,"providerCategory","국내도서-경제 경영")).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request",Integer.class)).isZero();
    }

    TopicDiscoveryService.Discovery commonDiscovery(String query,int domesticCount,int foreignCount) {
        return commonDiscovery(query,domesticCount,foreignCount,null);
    }
    TopicDiscoveryService.Discovery commonDiscovery(String query,int domesticCount,int foreignCount,Integer nationalCount) {
        var found=discoveries.start(alice,query);var job=discoveries.claim();
        var sources=new ArrayList<Map<String,Object>>();
        sources.add(Map.of("id","yes24","status",domesticCount==0?"provider_failed":"collected","bookCount",domesticCount));
        sources.add(Map.of("id","open_library","status","collected","bookCount",foreignCount));
        sources.add(Map.of("id","google_books","status","provider_failed","bookCount",0,"statusCode",429));
        if(nationalCount!=null)sources.add(Map.of("id","national_library","status","collected","bookCount",nationalCount));
        var field=Map.of("id","microeconomics","slug","field-microeconomics","name","미시경제학","englishName","Microeconomics",
            "parentCode","SRC-"+"b".repeat(16),"parentName","사회과학","registryHash","sha256:"+"a".repeat(64),
            "bookCount",domesticCount+foreignCount+(nationalCount==null?0:nationalCount),"samples",List.of(Map.of("title","Microeconomics","authors",List.of("Author"))),
            "providers",sources);
        discoveries.complete(job,json.valueToTree(Map.of("provider","common-fields","query",query,"status","FOUND","groups",List.of(),"fields",List.of(field))));
        return discoveries.detail(alice,found.id());
    }

    @Test void nationalLibraryCanSupplyTheSelectedFieldWithoutOtherSources() throws Exception {
        var found=commonDiscovery("미시경제학",0,0,2);
        assertThat(found.fields().getFirst().providers()).hasSize(4);
        assertThat(found.fields().getFirst().providers().getLast().id()).isEqualTo("national_library");
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","미시경제학","discoveryId",found.id(),"commonFieldId","microeconomics")).statusCode()).isEqualTo(201);
    }

    @Test void oldCompletedDiscoveryDoesNotHideTheNewProviderIndependentPath() {
        var old=discoveries.start(alice,"경제");var job=discoveries.claim();
        discoveries.complete(job,json.readTree("{\"provider\":\"yes24\",\"query\":\"경제\",\"status\":\"NO_RESULTS\",\"groups\":[]}"));
        var updated=discoveries.start(alice,"경제");
        assertThat(updated.id()).isNotEqualTo(old.id());
        assertThat(discoveries.start(alice,"경제").id()).isEqualTo(updated.id());
    }

    @Test void selectsCommonFieldFromIndependentSourcesAndHidesPrivateScope() throws Exception {
        var found=commonDiscovery("Microeconomics",0,2);
        var response=call("GET","/api/topic-requests/discover/"+found.id(),aliceToken,null);
        assertThat(response.body()).contains("Microeconomics","open_library").doesNotContain("registryHash","sha256:","parentCode");
        var selected=call("POST","/api/topic-requests",aliceToken,Map.of("name","Microeconomics","discoveryId",found.id(),"commonFieldId","microeconomics"));
        assertThat(selected.statusCode()).isEqualTo(201);
        var job=preparation.claim();var policy=json.readTree(job.discoveryJson());
        assertThat(policy.path("slug").asString()).isEqualTo("field-microeconomics");
        assertThat(policy.path("commonFieldId").asString()).isEqualTo("microeconomics");
        assertThat(policy.has("category")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
    }

    @Test void commonFieldSelectionRejectsUnobservedIdsOwnershipAndMixedNativeCategory() throws Exception {
        var found=commonDiscovery("미시경제학",1,2);
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","미시경제학","discoveryId",found.id(),"commonFieldId","forged")).statusCode()).isEqualTo(400);
        assertThat(call("POST","/api/topic-requests",bobToken,Map.of("name","미시경제학","discoveryId",found.id(),"commonFieldId","microeconomics")).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","미시경제학","discoveryId",found.id(),"commonFieldId","microeconomics","providerCategory","국내도서-대학교재")).statusCode()).isEqualTo(400);
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","aa","commonFieldId","microeconomics")).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request",Integer.class)).isZero();
    }

    @Test void differentLanguageAliasesReplayOnePendingCommonField() throws Exception {
        var english=commonDiscovery("Microeconomics",1,2);
        var first=call("POST","/api/topic-requests",aliceToken,Map.of("name","Microeconomics","discoveryId",english.id(),"commonFieldId","microeconomics"));
        var korean=commonDiscovery("미시경제학",1,2);
        var second=call("POST","/api/topic-requests",aliceToken,Map.of("name","미시경제학","discoveryId",korean.id(),"commonFieldId","microeconomics"));
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()).path("replayed").asBoolean()).isTrue();
        assertThat(json.readTree(first.body()).path("request").path("id").asLong()).isEqualTo(json.readTree(second.body()).path("request").path("id").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request",Integer.class)).isEqualTo(1);
    }

    @Test void savesAndReadsPendingRequestWithoutPublishingATopicOrQuestion() throws Exception {
        var response = call("POST","/api/topic-requests",aliceToken,input("컴퓨터 네트워크"));
        assertThat(response.statusCode()).isEqualTo(201);
        var saved = json.readTree(response.body()).path("request");
        long id = saved.path("id").asLong();
        assertThat(saved.path("status").asString()).isEqualTo("QUEUED");
        assertThat(saved.path("categoryName").asString()).isEqualTo("컴퓨터과학");
        assertThat(call("GET","/api/topic-requests/"+id,aliceToken,null).body()).contains(scope);
        assertThat(json.readTree(call("GET","/api/topic-requests",aliceToken,null).body()).get(0).path("id").asLong()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
    }
    @Test void retriesNormalizeWhitespaceCaseAndKoreanAndKeepOriginalScopeImmutable() throws Exception {
        var first = call("POST","/api/topic-requests",aliceToken,input("컴퓨터 네트워크"));
        long id = json.readTree(first.body()).path("request").path("id").asLong();
        var second = call("POST","/api/topic-requests",aliceToken,input(Normalizer.normalize("컴퓨터네트워크",Normalizer.Form.NFD)));
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()).path("replayed").asBoolean()).isTrue();
        assertThat(json.readTree(second.body()).path("request").path("id").asLong()).isEqualTo(id);
        var changed = new HashMap<>(input("컴퓨터 네트워크")); changed.put("scope","기존 요청과 다른 공부 범위를 입력합니다.");
        assertThat(call("POST","/api/topic-requests",aliceToken,changed).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT scope FROM backend.topic_request WHERE id=?",String.class,id)).isEqualTo(scope);
    }
    @Test void findsExistingFieldsAcrossCategoriesIncludingCodesAndEnglishIds() throws Exception {
        for (String name : List.of("운영 체제","os","Operating Systems")) {
            var body = new HashMap<>(input(name)); body.put("categoryId",9003);
            var response = call("POST","/api/topic-requests",aliceToken,body);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("existingTopicId").asLong()).isEqualTo(9002);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request",Integer.class)).isZero();
    }
    @Test void rejectsInvalidInputAndBroadFieldsBeforeSavingAnything() throws Exception {
        List<Map<String,Object>> invalid = List.of(
                Map.of("categoryId",9001,"name","a","scope","모름"),
                Map.of("categoryId",9002,"name","네트워크","scope",scope),
                Map.of("categoryId",9999,"name","네트워크","scope",scope),
                Map.of("categoryId",9001,"name","!!!","scope",scope),
                Map.of("categoryId",9001,"name","컴퓨터과학","scope",scope),
                Map.of("categoryId",9001,"name","네트워크","scope","x".repeat(1001)));
        for (var body: invalid) assertThat(call("POST","/api/topic-requests",aliceToken,body).statusCode()).as(body.toString()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request",Integer.class)).isZero();
        // Name validation is separate from semantic/AI review.
        assertThat(call("POST","/api/topic-requests",aliceToken,input("NLP")).statusCode()).isEqualTo(201);
    }
    @Test void descriptionIsOptionalAndOwnershipStillAppliesToRetries() throws Exception {
        var response=call("POST","/api/topic-requests",aliceToken,Map.of("categoryId",9001,"name","컴퓨터 네트워크"));
        assertThat(response.statusCode()).isEqualTo(201);
        long id=json.readTree(response.body()).path("request").path("id").asLong();
        assertThat(json.readTree(response.body()).path("request").path("scope").asString()).isEmpty();
        assertThat(call("POST","/api/topic-requests/"+id+"/retry",bobToken,Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/topic-requests/"+id+"/retry",null,Map.of()).statusCode()).isEqualTo(401);
        assertThat(call("POST","/api/topic-requests/"+id+"/retry",aliceToken,Map.of("scope","should not edit running work")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT scope FROM backend.topic_request WHERE id=?",String.class,id)).isEmpty();
    }

    @Test void localLookupDoesNotCreateARequestAndRequiresAuthentication() throws Exception {
        assertThat(call("POST","/api/topic-requests/resolve",null,Map.of("name","OS")).statusCode()).isEqualTo(401);
        for(String name:List.of("OS","TCP 혼잡 제어","수학","aa")) {
            var response=call("POST","/api/topic-requests/resolve",aliceToken,Map.of("name",name));
            assertThat(response.statusCode()).isEqualTo(200);
            var result=json.readTree(response.body());
            assertThat(result.path("status").asString()).isEqualTo(switch(name){case "OS"->"MATCH";case "수학"->"BROAD";case "aa"->"UNKNOWN";default->"RELATED";});
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request",Integer.class)).isZero();
    }

    @Test void narrowTopicWaitsForOwnedExplicitScopeSelectionBeforeCollection() throws Exception {
        var response=call("POST","/api/topic-requests",aliceToken,Map.of("name","TCP 혼잡 제어"));
        long id=json.readTree(response.body()).path("request").path("id").asLong();
        worker.tick();
        var pending=json.readTree(call("GET","/api/topic-requests/"+id,aliceToken,null).body());
        assertThat(pending.path("status").asString()).isEqualTo("NEEDS_INPUT");
        assertThat(pending.path("candidates").get(0).path("slug").asString()).isEqualTo("computer-networks");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.discovery_catalog_import",Integer.class)).isZero();
        assertThat(call("POST","/api/topic-requests/"+id+"/retry",bobToken,Map.of("selectedSlug","computer-networks")).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/topic-requests/"+id+"/retry",aliceToken,Map.of("selectedSlug","linear-algebra")).statusCode()).isEqualTo(400);
        assertThat(call("POST","/api/topic-requests/"+id+"/retry",aliceToken,Map.of("selectedSlug","computer-networks")).statusCode()).isEqualTo(200);
        var job=preparation.claim();assertThat(job.name()).isEqualTo("TCP 혼잡 제어");assertThat(job.selectedSlug()).isEqualTo("computer-networks");
        var classified=preparation.classify(job,"CS");assertThat(preparation.resolved(classified,"computer-networks")).isTrue();preparation.fail(classified);
        assertThat(call("POST","/api/topic-requests",aliceToken,Map.of("name","aa","selectedSlug","computer-networks")).statusCode()).isEqualTo(400);
    }

    @Test void nameOnlyRequestsRemainUnclassifiedUntilFencedResolution() throws Exception {
        var response=call("POST","/api/topic-requests",aliceToken,Map.of("name","컴퓨터 네트워크"));
        assertThat(response.statusCode()).isEqualTo(201);
        var saved=json.readTree(response.body()).path("request");long id=saved.path("id").asLong();
        assertThat(saved.path("categoryId").isNull()).isTrue();assertThat(saved.path("categoryName").isNull()).isTrue();
        assertThat(saved.path("scope").asString()).isEmpty();
        assertThat(json.readTree(call("POST","/api/topic-requests",aliceToken,Map.of("name","컴퓨터네트워크")).body()).path("replayed").asBoolean()).isTrue();
        var job=preparation.claim();assertThat(job.parentCode()).isNull();
        assertThatThrownBy(()->preparation.classify(job,"UNKNOWN")).hasMessageContaining("invalid app category");
        var classified=preparation.classify(job,"CS");assertThat(classified.parentName()).isEqualTo("컴퓨터과학");
        assertThat(preparation.resolved(classified,"computer-networks")).isTrue();
        assertThat(json.readTree(call("GET","/api/topic-requests/"+id,aliceToken,null).body()).path("categoryId").asLong()).isEqualTo(9001);
        assertThatThrownBy(()->preparation.classify(job,"MAT")).hasMessageContaining("expired or replaced");
        preparation.fail(classified);
    }

    @Test void nameCorrectionReclassifiesWithoutRequestingADescriptionAndPreservesOwnership() throws Exception {
        var response=call("POST","/api/topic-requests",aliceToken,input("aaa"));
        long id=json.readTree(response.body()).path("request").path("id").asLong();
        var old=preparation.claim();preparation.needsInput(old,"더 구체적인 분야 이름으로 입력해 주세요.");
        assertThat(call("POST","/api/topic-requests/"+id+"/retry",bobToken,Map.of("name","선형대수")).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/topic-requests/"+id+"/retry",aliceToken,Map.of("name","!!!")).statusCode()).isEqualTo(400);
        var corrected=call("POST","/api/topic-requests/"+id+"/retry",aliceToken,Map.of("name","선형대수"));
        assertThat(corrected.statusCode()).isEqualTo(200);
        var saved=json.readTree(corrected.body());assertThat(saved.path("categoryId").isNull()).isTrue();assertThat(saved.path("scope").asString()).isEmpty();
        var job=preparation.claim();assertThat(job.name()).isEqualTo("선형대수");
        assertThatThrownBy(()->preparation.classify(old,"CS")).hasMessageContaining("expired or replaced");
        var classified=preparation.classify(job,"MAT");assertThat(preparation.resolved(classified,"linear-algebra")).isTrue();
        assertThat(json.readTree(call("GET","/api/topic-requests/"+id,aliceToken,null).body()).path("categoryName").asString()).isEqualTo("수학");
        preparation.fail(classified);
    }
    @Test void oneDurableClaimAtATimeAndClarificationResumesWithoutPublishing() throws Exception {
        call("POST","/api/topic-requests",aliceToken,input("네트워크"));
        call("POST","/api/topic-requests",bobToken,input("aaa"));
        var job=preparation.claim();
        assertThat(job).isNotNull();
        assertThat(preparation.claim()).isNull();
        preparation.needsInput(job,"컴퓨터 통신을 다루는 네트워크인가요?");
        assertThat(call("POST","/api/topic-requests/"+job.id()+"/retry",aliceToken,Map.of("scope","컴퓨터 통신을 다루는 분야")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_request WHERE id=?",String.class,job.id())).isEqualTo("QUEUED");
        var next=preparation.claim();
        assertThat(next.scope()).isEqualTo("컴퓨터 통신을 다루는 분야");
        assertThatThrownBy(()->preparation.needsInput(job,"stale claim")).isInstanceOf(IllegalStateException.class);
        preparation.fail(job);
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_request WHERE id=?",String.class,next.id())).isEqualTo("CHECKING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic",Integer.class)).isEqualTo(3);
    }
    @Test void expiredWorkerIsRecoverableAndCannotPublishLate() throws Exception {
        call("POST","/api/topic-requests",aliceToken,input("네트워크"));
        var old=preparation.claim();
        jdbc.update("UPDATE backend.topic_request SET lease_until=now()-interval '1 minute' WHERE id=?",old.id());
        assertThat(preparation.claim()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_request WHERE id=?",String.class,old.id())).isEqualTo("FAILED");
        call("POST","/api/topic-requests/"+old.id()+"/retry",aliceToken,Map.of());
        var current=preparation.claim();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThatThrownBy(()->preparation.resolved(old,"computer-networks")).isInstanceOf(IllegalStateException.class);
        assertThat(preparation.resolved(current,"computer-networks")).isTrue();
        assertThatThrownBy(()->preparation.publish(current,"computer-networks",java.nio.file.Path.of("missing-import"),java.nio.file.Path.of("missing-selection")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic",Integer.class)).isEqualTo(3);
        preparation.fail(current);
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_request WHERE id=?",String.class,current.id())).isEqualTo("FAILED");
    }
    @Test void publicationRollsBackIncompleteSelectionThenPublishesBooksWithoutActivatingDiagnosis(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        call("POST","/api/topic-requests",aliceToken,input("네트워크"));
        var job=preparation.claim();
        preparation.resolved(job,"computer-networks");
        String bookId="book_"+"a".repeat(20);
        var book=new LinkedHashMap<String,Object>();
        book.put("book_id",bookId); book.put("isbn_10",null); book.put("isbn_13",null);
        book.put("title","Synthetic network book"); book.put("subtitle",null); book.put("authors",List.of("Synthetic"));
        book.put("publisher",null); book.put("published_year",null); book.put("language","ko");
        book.put("topics",List.of("computer-science","computer-networks"));
        java.nio.file.Files.writeString(dir.resolve("books.jsonl"),json.writeValueAsString(book)+"\n");
        java.nio.file.Files.writeString(dir.resolve("coverage.jsonl"),json.writeValueAsString(Map.of(
                "book_id",bookId,"toc_entry_count",0,"description_count",0,"source_count",1))+"\n");
        String booksHash=fileHash(dir.resolve("books.jsonl"));
        String evidenceHash=fileHash(dir.resolve("coverage.jsonl"));
        String emptyHash="sha256:"+"a".repeat(64);
        var batch=new LinkedHashMap<String,Object>(Map.of(
                "topic",Map.of("code","AUTO-computer-networks","name","컴퓨터 네트워크","parent_code","CS","parent_name","컴퓨터과학","ml_topic_id","computer-networks"),
                "books_path","books.jsonl","books_sha256",booksHash,"evidence_path","coverage.jsonl","evidence_sha256",evidenceHash,
                "canonical_hashes",Map.of("books.jsonl",booksHash,"documents.jsonl",emptyHash,"toc.jsonl",emptyHash,"sources.jsonl",emptyHash)));
        batch.put("candidates_path",null);batch.put("candidates_sha256",null);
        var source=dir.resolve("import.json");
        java.nio.file.Files.writeString(source,json.writeValueAsString(Map.of("contract_version","discovery-catalog-import-v1",
                "snapshot_id","synthetic-job","topics",List.of(batch),"accepted_projection_sources",List.of())));
        var topic=new LinkedHashMap<String,Object>(Map.of("ml_topic_id","computer-networks","raw_sha256",List.of(emptyHash),
                "filtered_books_sha256",booksHash,"decisions",List.of()));
        var selection=dir.resolve("selection.json");
        var selected=Map.of("contract_version","catalog-selection-v1","snapshot_id","synthetic-job-selected",
                "source_snapshot_id","synthetic-job","source_manifest_sha256",fileHash(source),
                "pipeline_revision","b".repeat(40),"pipeline_code_sha256",emptyHash,"topics",List.of(topic));
        java.nio.file.Files.writeString(selection,json.writeValueAsString(selected));
        assertThatThrownBy(()->preparation.publish(job,"computer-networks",source,selection)).hasMessageContaining("incomplete");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.book",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.discovery_catalog_import",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_request WHERE id=?",String.class,job.id())).isEqualTo("COLLECTING");
        topic.put("decisions",List.of(Map.of("book_id",bookId,"included",true,"reason","provider_filter_pass")));
        java.nio.file.Files.writeString(selection,json.writeValueAsString(selected));
        preparation.publish(job,"computer-networks",source,selection,json.readTree("""
                {"google_books":{"status":"provider_failed","statusCode":429,"privatePath":"must not persist"}}
                """));
        var stored=jdbc.queryForObject("SELECT provider_report::text FROM backend.catalog_selection_snapshot WHERE snapshot_id='synthetic-job-selected'",String.class);
        assertThat(stored).contains("provider_failed","429").doesNotContain("privatePath","must not persist");
        var ready=json.readTree(call("GET","/api/topic-requests/"+job.id(),aliceToken,null).body());
        assertThat(ready.path("status").asString()).isEqualTo("BOOKS_READY");
        assertThat(ready.path("bookCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.catalog_visible_book",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.book_ranking_v2_projection",Integer.class)).isZero();
        // A curated alias reuses the field immediately, without a worker or another import.
        var reused=json.readTree(call("POST","/api/topic-requests",bobToken,input("컴퓨터 통신망")).body());
        assertThat(reused.path("existingTopicId").asLong()).isEqualTo(ready.path("topicId").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request WHERE user_id=?",Integer.class,bob)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.discovery_catalog_import",Integer.class)).isEqualTo(1);
    }
    String fileHash(java.nio.file.Path path) throws Exception {
        return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)));
    }
    @Test void authenticationAndPrivateOwnershipApplyToListCreateAndDetail() throws Exception {
        assertThat(call("POST","/api/topic-requests",null,input("네트워크")).statusCode()).isEqualTo(401);
        assertThat(call("GET","/api/topic-requests",null,null).statusCode()).isEqualTo(401);
        var response = call("POST","/api/topic-requests",aliceToken,input("네트워크"));
        long id = json.readTree(response.body()).path("request").path("id").asLong();
        assertThat(call("GET","/api/topic-requests/"+id,bobToken,null).statusCode()).isEqualTo(404);
        assertThat(call("GET","/api/topic-requests/"+id,null,null).statusCode()).isEqualTo(401);
        assertThat(json.readTree(call("GET","/api/topic-requests",bobToken,null).body()).size()).isZero();
    }
    @Test void simultaneousRetriesCreateOneRequestAndEnforcePendingLimit() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Future<HttpResponse<String>>>();
            for (int i=0;i<4;i++) calls.add(pool.submit(() -> call("POST","/api/topic-requests",aliceToken,input("네트워크"))));
            int created=0;
            for (var task:calls) {
                int status=task.get(15,TimeUnit.SECONDS).statusCode();
                assertThat(status).isIn(200,201);
                if (status==201) created++;
            }
            assertThat(created).isEqualTo(1);
        }
        for (int i=0;i<19;i++) jdbc.update("INSERT INTO backend.topic_request(user_id,category_id,name,normalized_name,scope) VALUES (?,9001,?,?,?)",alice,"필드"+i,"필드"+i,scope);
        assertThat(call("POST","/api/topic-requests",aliceToken,input("추가 분야")).statusCode()).isEqualTo(409);
        assertThat(call("POST","/api/topic-requests",aliceToken,input("네트워크")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.topic_request",Integer.class)).isEqualTo(20);
    }
    long seedContentCatalog() {
        jdbc.update("INSERT INTO backend.topic(id,code,name,parent_id,ml_topic_id) VALUES (9004,'AUTO-computer-networks','컴퓨터 네트워크',9001,'computer-networks')");
        long request=jdbc.queryForObject("INSERT INTO backend.topic_request(user_id,category_id,name,normalized_name,scope,status,resolved_slug,topic_id,book_count) VALUES (?,9001,'네트워크','네트워크','','BOOKS_READY','computer-networks',9004,2) RETURNING id",Long.class,alice);
        String snapshot="topic-request-"+request+"-computer-networks-fixture";
        jdbc.update("INSERT INTO backend.discovery_catalog_import(snapshot_id,manifest_hash,manifest) VALUES (?,?,'{}'::jsonb)",snapshot,"sha256:"+"a".repeat(64));
        jdbc.update("INSERT INTO backend.catalog_selection_snapshot(snapshot_id,manifest_hash,manifest) VALUES (?,?,?::jsonb)",snapshot+"-selected","sha256:"+"b".repeat(64),json.writeValueAsString(Map.of("source_snapshot_id",snapshot)));
        jdbc.update("INSERT INTO backend.catalog_selection_topic(snapshot_id,topic_id) VALUES (?,9004)",snapshot+"-selected");
        jdbc.update("INSERT INTO backend.catalog_selection_current(topic_id,snapshot_id) VALUES (9004,?)",snapshot+"-selected");
        return request;
    }
    @Autowired TopicDiagnosticActivationService activation;
    @Autowired TopicDiagnosticState diagnosticState;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.cau.capstone8.backend.integration.ml.MlGateway ml;

    @Test void sharedContentQueuesWithoutOriginalRequestAndPreservesClaimWorkspaceAfterDeletion() {
        long request=seedContentCatalog();
        var first=content.claim();assertThat(first.adapterJob().id()).isEqualTo(request);
        jdbc.update("DELETE FROM backend.topic_request WHERE id=?",request);
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM backend.topic_content_preparation",Long.class)).isEqualTo(request);
        assertThat(jdbc.queryForMap("SELECT source_request_id FROM backend.topic_content_preparation").get("source_request_id")).isNull();
        content.fail(first);
        jdbc.execute("DELETE FROM backend.topic_content_preparation");
        var independent=content.claim();assertThat(independent.topicId()).isEqualTo(9004);assertThat(independent.adapterJob().id()).isEqualTo(-9004);
        assertThat(diagnosticState.ready(9004)).isFalse();
    }

    @Test void reviewClaimsUseGlobalLeaseAndRejectStaleArtifactsWithoutOpeningDiagnosis(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        long request=seedContentCatalog();var p=content.claim();content.fail(p);
        var report=dir.resolve("content-report.json");java.nio.file.Files.writeString(report,"{}");
        jdbc.update("UPDATE backend.topic_content_preparation SET status='CONCEPTS_READY',report='{\"questionSpecCount\":9}'::jsonb,report_hash=?,artifact_path=?",fileHash(report),report.toString());
        var generation=dir.resolve("generation-report.json");java.nio.file.Files.writeString(generation,"{}");
        jdbc.update("INSERT INTO backend.topic_question_preparation(topic_id,source_snapshot_id,content_report_hash,status,generated_count,planned_count,report_hash,artifact_path) VALUES (9004,?,?,'CANDIDATES_READY',9,9,?,?)",p.snapshotId(),fileHash(report),fileHash(generation),generation.toString());
        var job=activation.claim();assertThat(job.question().adapterJob().id()).isEqualTo(request);
        assertThat(activation.claim()).isNull();assertThat(questions.claim()).isNull();assertThat(content.claim()).isNull();
        assertThat(diagnosticState.get(9004).get("status")).isEqualTo("REVIEWING");
        assertThatThrownBy(()->activation.publish(job,report,"sha256:"+"f".repeat(64))).hasMessageContaining("artifact hash differs");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Long.class)).isZero();
        jdbc.update("UPDATE backend.topic_question_preparation SET lease_until=now()-interval '1 minute'");
        assertThat(activation.claim()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_question_preparation",String.class)).isEqualTo("FAILED");
        assertThatThrownBy(()->activation.publish(job,report,fileHash(report))).hasMessageContaining("lease expired");
        questions.retry(alice,request);assertThat(diagnosticState.get(9004).get("status")).isEqualTo("CANDIDATES_READY");
        var next=activation.claim();activation.fail(job);
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_question_preparation",String.class)).isEqualTo("REVIEWING");
        activation.fail(next);
    }

    @Test void automaticReadinessIsBoundToCurrentCatalogAndRetryRequiresAuthentication() throws Exception {
        seedContentCatalog();var p=content.claim();content.fail(p);
        jdbc.update("UPDATE backend.topic_content_preparation SET status='CONCEPTS_READY',report='{\"questionSpecCount\":9}'::jsonb,report_hash=?,artifact_path='test'", "sha256:"+"c".repeat(64));
        jdbc.update("INSERT INTO backend.topic_question_preparation(topic_id,source_snapshot_id,content_report_hash,status,generated_count,planned_count) VALUES (9004,?,?,'ACTIVE',9,9)",p.snapshotId(),"sha256:"+"c".repeat(64));
        assertThat(diagnosticState.ready(9004)).isTrue();
        assertThat(call("POST","/api/topics/9004/preparation/retry",null,Map.of()).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE backend.catalog_selection_snapshot SET manifest='{\"source_snapshot_id\":\"other-source\"}'::jsonb");
        assertThat(diagnosticState.ready(9004)).isFalse();
        assertThat(call("GET","/api/topics/9004/preparation",aliceToken,null).statusCode()).isEqualTo(200);
    }

    @Test void automaticRevisionClaimsOnlyEligibleBlockedReviewsAndKeepsDiagnosisClosed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        seedQuestionCatalog(dir);
        var p=jdbc.queryForMap("SELECT * FROM backend.topic_content_preparation");
        var report=dir.resolve("generation.json");java.nio.file.Files.writeString(report,"{}");
        jdbc.update("INSERT INTO backend.topic_question_preparation(topic_id,source_snapshot_id,content_report_hash,status,generated_count,planned_count,report_hash,artifact_path,review_report) VALUES (9004,?,?,'REVIEW_BLOCKED',9,9,?,?, '{\"revisionPossible\":false}'::jsonb)",p.get("source_snapshot_id"),p.get("report_hash"),fileHash(report),report.toString());
        assertThat(activation.claim()).isNull();
        jdbc.update("UPDATE backend.topic_question_preparation SET review_report='{\"revisionPossible\":true}'::jsonb");
        var job=activation.claim();assertThat(job.allowRevision()).isTrue();
        assertThat(activation.claim()).isNull();assertThat(diagnosticState.ready(9004)).isFalse();
        activation.fail(job);
    }

    @Test void reviewWithoutRealRenderingProofCannotOpenDiagnosis(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        seedContentCatalog();var p=content.claim();content.fail(p);
        var contentPath=dir.resolve("content.json");java.nio.file.Files.writeString(contentPath,"{}");
        String contentHash=fileHash(contentPath);
        jdbc.update("UPDATE backend.topic_content_preparation SET status='CONCEPTS_READY',report='{\"questionSpecCount\":9}'::jsonb,report_hash=?,artifact_path=?",contentHash,contentPath.toString());
        var generationPath=dir.resolve("generation.json");
        var hashes=Map.of("q_"+"a".repeat(20)+".json","sha256:"+"b".repeat(64));
        java.nio.file.Files.writeString(generationPath,json.writeValueAsString(Map.of("candidateHashes",hashes,"blueprintHash","sha256:"+"c".repeat(64))));
        String generationHash=fileHash(generationPath);
        jdbc.update("INSERT INTO backend.topic_question_preparation(topic_id,source_snapshot_id,content_report_hash,status,generated_count,planned_count,report_hash,artifact_path) VALUES (9004,?,?,'CANDIDATES_READY',9,9,?,?)",p.snapshotId(),contentHash,generationHash,generationPath.toString());
        var job=activation.claim();var report=new LinkedHashMap<String,Object>();
        report.put("contractVersion","automatic-content-review-v1");report.put("topicId",job.question().slug());
        report.put("sourceSnapshotId",p.snapshotId());report.put("contentReportHash",contentHash);
        report.put("generationReportHash",generationHash);report.put("candidateHashes",hashes);
        report.put("blueprintHash","sha256:"+"c".repeat(64));report.put("plannedCount",9);
        report.put("reviewerType","ai");report.put("validationScope","content-only");report.put("diagnosisReady",false);
        var reportPath=dir.resolve("review.json");
        for(var rendering:List.of(Map.of(),Map.of("status","PASSED","questions",9,"fields",54,"invalidMath",1,"rendererHash","sha256:"+"d".repeat(64),"validatorHash","sha256:"+"e".repeat(64)))) {
            report.put("renderValidation",rendering);java.nio.file.Files.writeString(reportPath,json.writeValueAsString(report));
            assertThatThrownBy(()->activation.publish(job,reportPath,fileHash(reportPath))).hasMessageContaining("rendering not validated");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Long.class)).isZero();
            assertThat(diagnosticState.ready(9004)).isFalse();
        }
        activation.fail(job);
    }

    @Test void contentQueueSharesClaimsAcrossAccountsAndDoesNotActivateDiagnosis() throws Exception {
        long request=seedContentCatalog();
        var job=content.claim();
        assertThat(job).isNotNull();
        assertThat(job.adapterJob().id()).isEqualTo(request);
        assertThat(content.claim()).isNull();
        assertThat(preparation.claim()).isNull();
        var response=json.readTree(call("GET","/api/topic-requests/"+request,aliceToken,null).body());
        assertThat(response.path("content").path("status").asString()).isEqualTo("PREPARING");
        assertThat(call("POST","/api/topic-requests/"+request+"/content/retry",bobToken,Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/topic-requests/"+request+"/content/retry",null,Map.of()).statusCode()).isEqualTo(401);
        content.fail(job);
        assertThat(call("POST","/api/topic-requests/"+request+"/content/retry",aliceToken,Map.of()).statusCode()).isEqualTo(200);
        var next=content.claim();
        assertThat(next.token()).isNotEqualTo(job.token());
        content.fail(job);
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_content_preparation",String.class)).isEqualTo("PREPARING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.book_ranking_v2_projection",Integer.class)).isZero();
    }
    @Test void expiredContentClaimsRecoverButOldWorkersCannotPublish() throws Exception {
        long request=seedContentCatalog(); var old=content.claim();
        jdbc.update("UPDATE backend.topic_content_preparation SET lease_until=now()-interval '1 minute'");
        assertThat(content.claim()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_content_preparation",String.class)).isEqualTo("FAILED");
        content.retry(alice,request); var current=content.claim();
        assertThatThrownBy(()->content.publish(old,java.nio.file.Path.of("missing"),"sha256:"+"a".repeat(64))).hasMessageContaining("claim expired");
        assertThat(current.token()).isNotEqualTo(old.token());
        content.fail(current);
    }
    @Test void contentPublicationRejectsTamperedReportsAndCannotPublishForAnOldCatalog(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        seedContentCatalog(); var job=content.claim();
        var path=dir.resolve("report.json"); java.nio.file.Files.writeString(path,"{}");
        assertThatThrownBy(()->content.publish(job,path,"sha256:"+"f".repeat(64))).hasMessageContaining("report hash differs");
        assertThatThrownBy(()->content.publish(job,path,fileHash(path))).hasMessageContaining("content source differs");
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_content_preparation",String.class)).isEqualTo("PREPARING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
        content.fail(job);
    }

    @Test void groundedContentPublicationExposesSummaryButNeverImportsQuestions(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        long request=seedContentCatalog(); var job=content.claim();
        String emptyHash="sha256:"+"c".repeat(64);
        var bookIds=List.of("book_"+"1".repeat(20),"book_"+"2".repeat(20));
        for (String mlId:bookIds) {
            long book=jdbc.queryForObject("INSERT INTO backend.book(title,author,description,ml_book_id) VALUES ('Synthetic','Fixture','',?) RETURNING id",Long.class,mlId);
            jdbc.update("INSERT INTO backend.book_topic(book_id,topic_id,topic_weight) VALUES (?,9004,1.0)",book);
            jdbc.update("INSERT INTO backend.discovery_catalog_member(snapshot_id,book_id,topic_id,toc_entry_count,description_count,source_count,books_hash,evidence_hash) VALUES (?,?,9004,3,0,1,?,?)",job.snapshotId(),book,emptyHash,emptyHash);
        }
        var root=dir.resolve("request-fixture"); var canonical=root.resolve("source/canonical");
        var artifacts=root.resolve("content/fixture"); java.nio.file.Files.createDirectories(canonical); java.nio.file.Files.createDirectories(artifacts.resolve("configs"));
        var toc=new StringBuilder(); var concepts=new ArrayList<Object>(); var specs=new ArrayList<Object>();
        for (int i=0;i<6;i++) {
            String concept="computer-networks:concept-"+i;
            var refs=new ArrayList<Object>();
            if (i<3) for (String book:bookIds) {
                var ref=Map.of("book_id",book,"toc_entry_id","toc-"+i); refs.add(ref);
                toc.append(json.writeValueAsString(ref)).append('\n');
            }
            concepts.add(Map.of("id",concept,"name","Synthetic concept "+i,"bookCount",i<3?2:0,"tocCount",refs.size(),"evidenceReferences",refs));
            if (i<3) for (String ability:List.of("meaning","application","reasoning"))
                specs.add(Map.of("topic_id",job.slug(),"primary_concept",concept,"ability",ability,"measurement_context","prior-knowledge","evidence_references",refs));
        }
        var canonicalHashes=new LinkedHashMap<String,String>();
        for (String file:List.of("books.jsonl","documents.jsonl","toc.jsonl","sources.jsonl")) {
            java.nio.file.Files.writeString(canonical.resolve(file),file.equals("toc.jsonl")?toc.toString():""); canonicalHashes.put(file,fileHash(canonical.resolve(file)));
        }
        java.nio.file.Files.writeString(artifacts.resolve("blueprint.json"),json.writeValueAsString(Map.of("topic_id",job.slug(),"question_specs",specs)));
        var artifactHashes=new LinkedHashMap<String,String>();
        for (String file:List.of("outline.json","book-profiles.jsonl","targets.json","blueprint.json","configs/features.yaml","configs/concept_graph.yaml","configs/concept_matching_v2.yaml")) {
            if (!file.equals("blueprint.json")) java.nio.file.Files.writeString(artifacts.resolve(file),"synthetic fixture");
            artifactHashes.put(file,fileHash(artifacts.resolve(file)));
        }
        var report=new LinkedHashMap<String,Object>();
        report.put("contractVersion","topic-content-preparation-v1"); report.put("topicId",job.slug());
        report.put("sourceSnapshotId",job.snapshotId()); report.put("sourceManifestHash","sha256:"+"a".repeat(64));
        report.put("canonicalDataPath",canonical.toString()); report.put("canonicalFileHashes",canonicalHashes); report.put("artifactHashes",artifactHashes);
        report.put("diagnosisReady",false); report.put("generatedQuestionCount",0); report.put("humanReview","unreviewed");
        report.put("bookCount",2); report.put("conceptCount",3); report.put("mappedBookCount",2); report.put("questionSpecCount",9);
        report.put("status","CONCEPTS_READY"); report.put("concepts",concepts);
        var path=artifacts.resolve("report.json"); java.nio.file.Files.writeString(path,json.writeValueAsString(report));
        java.nio.file.Files.writeString(artifacts.resolve("blueprint.json"),"{}");
        assertThatThrownBy(()->content.publish(job,path,fileHash(path))).hasMessageContaining("content artifact differs");
        java.nio.file.Files.writeString(artifacts.resolve("blueprint.json"),json.writeValueAsString(Map.of("topic_id",job.slug(),"question_specs",specs)));
        content.publish(job,path,fileHash(path));
        var response=json.readTree(call("GET","/api/topic-requests/"+request,aliceToken,null).body());
        assertThat(response.path("status").asString()).isEqualTo("BOOKS_READY");
        assertThat(response.path("content").path("status").asString()).isEqualTo("CONCEPTS_READY");
        assertThat(response.path("content").path("questionSpecCount").asInt()).isEqualTo(9);
        assertThat(response.path("content").path("concepts").size()).isEqualTo(3);
        assertThat(response.toString()).doesNotContain("sourceSnapshotId","artifactHashes","canonicalDataPath");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
        assertThat(content.claim()).isNull();
    }

    long seedQuestionCatalog(java.nio.file.Path dir) throws Exception {
        long request=seedContentCatalog();var contentJob=content.claim();content.fail(contentJob);
        var path=dir.resolve("content-report.json");java.nio.file.Files.writeString(path,json.writeValueAsString(Map.of("questionSpecCount",9,"artifactHashes",Map.of("blueprint.json","sha256:"+"b".repeat(64)))));
        jdbc.update("UPDATE backend.topic_content_preparation SET status='CONCEPTS_READY',report=?::jsonb,report_hash=?,artifact_path=?",java.nio.file.Files.readString(path),fileHash(path),path.toString());
        return request;
    }
    @Test void questionClaimsAreSharedFencedAndOwnedRetriesPreserveProgress(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        long request=seedQuestionCatalog(dir);var old=questions.claim();
        assertThat(old).isNotNull();assertThat(old.plannedCount()).isEqualTo(9);
        assertThat(questions.claim()).isNull();assertThat(preparation.claim()).isNull();assertThat(content.claim()).isNull();
        assertThat(call("POST","/api/topic-requests/"+request+"/questions/retry",bobToken,Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("POST","/api/topic-requests/"+request+"/questions/retry",null,Map.of()).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE backend.topic_question_preparation SET generated_count=2,lease_until=now()-interval '1 minute'");
        assertThat(questions.claim()).isNull();
        assertThat(call("POST","/api/topic-requests/"+request+"/questions/retry",aliceToken,Map.of()).statusCode()).isEqualTo(200);
        var current=questions.claim();assertThat(current.token()).isNotEqualTo(old.token());questions.fail(old);
        assertThatThrownBy(()->questions.publish(old,dir.resolve("missing"),"invalid")).hasMessageContaining("claim expired");
        assertThat(jdbc.queryForObject("SELECT generated_count FROM backend.topic_question_preparation",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM backend.topic_question_preparation",String.class)).isEqualTo("GENERATING");questions.fail(current);
    }
    @Test void questionBatchesPublishCountsWithoutActivatingQuestionsAndRejectTampering(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        long request=seedQuestionCatalog(dir);var job=questions.claim();
        var hashes=new LinkedHashMap<String,String>();String blueprintHash="sha256:"+"b".repeat(64);
        for (int i=1;i<=2;i++) {
            String id="q_"+Integer.toString(i).repeat(20);var candidate=dir.resolve(id+".json");
            java.nio.file.Files.writeString(candidate,json.writeValueAsString(Map.of("topic_id",job.slug(),"input_artifact_hash",blueprintHash,"question_spec_id",id,"generated_question_version","generated-question-v5")));hashes.put(candidate.getFileName().toString(),fileHash(candidate));
        }
        var report=new LinkedHashMap<String,Object>();
        report.put("contractVersion","concept-generation-batch-v1");report.put("topicId",job.slug());report.put("sourceSnapshotId",job.snapshotId());report.put("contentReportHash",job.contentHash());
        report.put("contentReview","pending");report.put("diagnosisReady",false);report.put("generatedCount",2);report.put("plannedCount",9);report.put("status","GENERATING");report.put("blueprintHash",blueprintHash);report.put("candidateHashes",hashes);
        var path=dir.resolve("generation-report.json");java.nio.file.Files.writeString(path,json.writeValueAsString(report));
        assertThatThrownBy(()->questions.publish(job,path,"sha256:"+"f".repeat(64))).hasMessageContaining("report hash differs");questions.publish(job,path,fileHash(path));
        var response=json.readTree(call("GET","/api/topic-requests/"+request,aliceToken,null).body());
        assertThat(response.path("content").path("questions").path("generatedCount").asInt()).isEqualTo(2);
        assertThat(response.path("content").path("questions").path("status").asString()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.question",Integer.class)).isZero();
        var next=questions.claim();assertThat(next).isNotNull();
        assertThatThrownBy(()->questions.publish(next,path,fileHash(path))).hasMessageContaining("invalid generation progress");
        // A versioned replacement run may restart its count, keeping old files intact.
        var replacement=java.nio.file.Files.createDirectory(dir.resolve("replacement-run"));
        String first=hashes.keySet().iterator().next();
        java.nio.file.Files.copy(dir.resolve(first),replacement.resolve(first));
        report.put("generatedCount",1);report.put("candidateHashes",Map.of(first,hashes.get(first)));
        var replacementReport=replacement.resolve("generation-report.json");
        java.nio.file.Files.writeString(replacementReport,json.writeValueAsString(report));
        questions.publish(next,replacementReport,fileHash(replacementReport));
        assertThat(jdbc.queryForObject("SELECT generated_count FROM backend.topic_question_preparation",Integer.class)).isEqualTo(1);
        assertThat(java.nio.file.Files.readString(path)).contains("\"generatedCount\":2");
        assertThat(java.nio.file.Files.exists(dir.resolve(first))).isTrue();
        questions.fail(questions.claim());
    }

}
