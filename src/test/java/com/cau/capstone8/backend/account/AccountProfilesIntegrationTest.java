package com.cau.capstone8.backend.account;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="app.auth.mode=required")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountProfilesIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.11");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    long alice,bob,topic,unassessed,book;
    String aliceToken,bobToken,aliceEmail;

    @BeforeAll void seed() throws Exception {
        aliceEmail="Alice-"+UUID.randomUUID()+"@example.com";
        var first=call("POST","/api/auth/register",null,Map.of(
                "email",aliceEmail,"password","example-password-123","displayName","Alice"));
        assertThat(first.statusCode()).isEqualTo(201);
        var firstBody=json.readTree(first.body());
        alice=firstBody.path("userId").asLong(); aliceToken=firstBody.path("accessToken").asString();
        var second=call("POST","/api/auth/register",null,Map.of(
                "email","bob-"+UUID.randomUUID()+"@example.com","password","example-password-456","displayName","Bob"));
        assertThat(second.statusCode()).isEqualTo(201);
        var secondBody=json.readTree(second.body());
        bob=secondBody.path("userId").asLong(); bobToken=secondBody.path("accessToken").asString();
        var empty=json.readTree(call("GET","/api/me/readiness",aliceToken,null).body());
        assertThat(empty.path("latestProfiles").size()).isZero();
        assertThat(empty.path("unassessedInterests").size()).isZero();
        assertThat(empty.path("completedAssessmentCount").asLong()).isZero();
        topic=jdbc.queryForObject("INSERT INTO backend.topic(code,name) VALUES ('TEST-OS','운영체제') RETURNING id",Long.class);
        unassessed=jdbc.queryForObject("INSERT INTO backend.topic(code,name) VALUES ('TEST-LA','선형대수') RETURNING id",Long.class);
        book=jdbc.queryForObject("INSERT INTO backend.book(title,author,description) VALUES ('Book','Author','') RETURNING id",Long.class);
    }

    @Test void credentialsAreHashedAndEmailIsCanonical() throws Exception {
        var me=call("GET","/api/me",aliceToken,null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).doesNotContain("password","token_hash",aliceToken);
        assertThat(json.readTree(me.body()).path("email").asString()).isEqualTo(aliceEmail.toLowerCase(Locale.ROOT));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM backend.user_account WHERE user_id=?",String.class,alice))
                .doesNotContain("example-password-123").hasSizeGreaterThan(64);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.account_session WHERE token_hash=?",Long.class,AccountService.hashToken(aliceToken)))
                .isEqualTo(1L);
        assertThat(call("POST","/api/auth/register",null,Map.of(
                "email",aliceEmail.toLowerCase(Locale.ROOT),"password","another-password-123","displayName","Impostor")).statusCode())
                .isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.app_user WHERE display_name='Impostor'",Long.class)).isZero();
    }

    @Test void loginLogoutAndExpiryAreEnforced() throws Exception {
        var wrong=call("POST","/api/auth/login",null,Map.of("email",aliceEmail,"password","wrong-password"));
        var missing=call("POST","/api/auth/login",null,Map.of("email","missing@example.com","password","wrong-password"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(json.readTree(wrong.body()).path("message")).isEqualTo(json.readTree(missing.body()).path("message"));
        var login=call("POST","/api/auth/login",null,Map.of("email",aliceEmail,"password","example-password-123"));
        assertThat(login.statusCode()).isEqualTo(200);
        String token=json.readTree(login.body()).path("accessToken").asString();
        assertThat(call("GET","/api/me",token,null).statusCode()).isEqualTo(200);
        assertThat(call("POST","/api/auth/logout",token,null).statusCode()).isEqualTo(204);
        assertThat(call("GET","/api/me",token,null).statusCode()).isEqualTo(401);
        // Another independent session remains usable.
        assertThat(call("GET","/api/me",aliceToken,null).statusCode()).isEqualTo(200);
        String expired="a".repeat(43);
        jdbc.update("INSERT INTO backend.account_session(token_hash,user_id,expires_at) VALUES (?,?,now()-interval '1 second')",
                AccountService.hashToken(expired),alice);
        assertThat(call("GET","/api/me",expired,null).statusCode()).isEqualTo(401);
    }

    @Test void anonymousAndMalformedTokensCannotReadPrivateData() throws Exception {
        assertThat(call("GET","/api/me",null,null).statusCode()).isEqualTo(401);
        assertThat(call("GET","/api/me","bad-token",null).statusCode()).isEqualTo(401);
        assertThat(call("GET","/api/users/"+alice+"/shelf",null,null).statusCode()).isEqualTo(401);
        assertThat(call("GET","/api/topics",null,null).statusCode()).isEqualTo(200);
        var apiDocs=call("GET","/v3/api-docs",null,null);
        assertThat(apiDocs.statusCode()).isEqualTo(200);
        assertThat(json.readTree(apiDocs.body()).path("components").path("securitySchemes")
                .path("bearerAuth").path("scheme").asString()).isEqualTo("bearer");
        assertThat(json.readTree(apiDocs.body()).path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(call("GET","/swagger-ui/index.html",null,null).statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/books/"+book+"/reviews",null,null).statusCode()).isEqualTo(200);
    }

    @Test void profileEditsAreAtomicAndDoNotChangeAnotherAccount() throws Exception {
        var update=Map.of("displayName","새 이름","bio","독서 중","avatarKey","LEAF","interestTopicIds",List.of(topic,unassessed));
        var result=call("PUT","/api/me",aliceToken,update);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json.readTree(result.body()).path("interests").size()).isEqualTo(2);
        assertThat(json.readTree(call("GET","/api/me",bobToken,null).body()).path("displayName").asString()).isEqualTo("Bob");
        for (Object interests:List.of(List.of(topic,topic),List.of(Long.MAX_VALUE))) {
            assertThat(call("PUT","/api/me",aliceToken,Map.of(
                    "displayName","Bad change","bio","","avatarKey","BOOK","interestTopicIds",interests)).statusCode()).isEqualTo(400);
        }
        assertThat(call("PUT","/api/me",aliceToken,Map.of(
                "displayName"," ","bio","","avatarKey","BOOK","interestTopicIds",List.of())).statusCode()).isEqualTo(400);
        assertThat(json.readTree(call("GET","/api/me",aliceToken,null).body()).path("displayName").asString()).isEqualTo("새 이름");
    }

    @Test void existingUserAndSessionApisCheckOwnershipIncludingBodyIds() throws Exception {
        long session=jdbc.queryForObject("INSERT INTO backend.assessment_session(user_id,topic_id) VALUES (?,?) RETURNING id",Long.class,alice,topic);
        for (String path:List.of("/api/users/"+alice+"/shelf","/api/users/"+alice+"/profiles/"+topic,
                "/api/assessments/"+session,"/api/assessments/"+session+"/diagnostics")) {
            assertThat(call("GET",path,bobToken,null).statusCode()).as(path).isEqualTo(403);
        }
        assertThat(call("POST","/api/assessments",bobToken,Map.of("userId",alice,"topicId",topic)).statusCode()).isEqualTo(403);
        assertThat(call("POST","/api/assessments/"+session+"/complete",bobToken,null).statusCode()).isEqualTo(403);
        assertThat(call("PUT","/api/assessments/"+session+"/answers/1",bobToken,Map.of("knowsConcept",true)).statusCode()).isEqualTo(403);
        assertThat(call("PUT","/api/users/"+alice+"/shelf/"+book,bobToken,Map.of("status","READING","note","stolen")).statusCode()).isEqualTo(403);
        assertThat(call("PUT","/api/users/"+alice+"/reviews/"+book,bobToken,Map.of("difficulty","EASY","text","stolen")).statusCode()).isEqualTo(403);
        assertThat(call("POST","/api/recommendations",bobToken,Map.of("userId",alice,"topicId",topic,"challengeLevel","BALANCED","topK",1)).statusCode()).isEqualTo(403);
        assertThat(call("POST","/api/users/"+alice+"/shelf/"+book,aliceToken,null).statusCode()).isEqualTo(200);
        assertThat(call("DELETE","/api/users/"+alice+"/shelf/"+book,bobToken,null).statusCode()).isEqualTo(403);
    }

    @Test void readinessUsesSavedSnapshotsAndStableHistoryWithoutInventingScores() throws Exception {
        long user=alice;
        jdbc.update("INSERT INTO backend.user_interest VALUES (?,?) ON CONFLICT DO NOTHING",user,unassessed);
        long older=profile(user,0.2,"reader-v1");
        long newest=profile(user,0.8,"reader-v2");
        answerEvidence(newest);
        profile(bob,0.99,"other-user-version");
        var overview=call("GET","/api/me/readiness",aliceToken,null);
        assertThat(overview.statusCode()).isEqualTo(200);
        JsonNode data=json.readTree(overview.body());
        assertThat(data.path("completedAssessmentCount").asLong()).isEqualTo(2);
        assertThat(data.path("latestProfiles").size()).isEqualTo(1);
        assertThat(data.path("latestProfiles").get(0).path("sessionId").asLong()).isEqualTo(newest);
        assertThat(data.path("latestProfiles").get(0).path("vocabulary").asDouble()).isEqualTo(0.8);
        assertThat(data.path("latestProfiles").get(0).path("selfReportCount").asInt()).isEqualTo(1);
        assertThat(data.path("latestProfiles").get(0).path("multipleChoiceCount").asInt()).isEqualTo(1);
        assertThat(data.path("latestProfiles").get(0).path("evidence").path("configHash").asString()).isEqualTo("preserved");
        assertThat(data.path("unassessedInterests").get(0).path("id").asLong()).isEqualTo(unassessed);
        assertThat(overview.body()).doesNotContain("other-user-version");
        var history=json.readTree(call("GET","/api/me/readiness/"+topic+"/history?size=1",aliceToken,null).body());
        assertThat(history.path("totalElements").asLong()).isEqualTo(2);
        assertThat(history.path("content").get(0).path("sessionId").asLong()).isEqualTo(newest);
        assertThat(json.readTree(call("GET","/api/me/readiness/"+topic+"/history?page=1&size=1",aliceToken,null).body())
                .path("content").get(0).path("sessionId").asLong()).isEqualTo(older);
        assertThat(call("GET","/api/me/readiness/"+topic+"/history?size=101",aliceToken,null).statusCode()).isEqualTo(400);
        assertThat(json.readTree(call("GET","/api/me/readiness/"+unassessed+"/history",aliceToken,null).body())
                .path("content").size()).isZero();
        assertThat(call("GET","/api/me/readiness/"+unassessed+"/diagnostics",aliceToken,null).statusCode()).isEqualTo(404);
        // Both snapshots survive history reads unchanged.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.reader_profile WHERE session_id IN (?,?)",Long.class,older,newest)).isEqualTo(2);
    }

    @Test void recommendationResultsAndFeedbackRejectAnotherUser() throws Exception {
        long session=profile(bob,0.5,"reader-v1");
        long profile=jdbc.queryForObject("SELECT id FROM backend.reader_profile WHERE session_id=?",Long.class,session);
        long run=jdbc.queryForObject("""
                INSERT INTO backend.recommendation_run(user_id,topic_id,profile_id,status,request_key,request_hash,
                    challenge_level,requested_top_k,input_snapshot,eligible_candidate_count,excluded_candidate_count,
                    model_version,completed_at)
                VALUES (?,?,?,'SUCCEEDED',?,?,'BALANCED',1,'{}',1,0,'stub-rank-v1',now()) RETURNING id
                """,Long.class,bob,topic,profile,UUID.randomUUID().toString(),"a".repeat(64));
        jdbc.update("INSERT INTO backend.book_topic(book_id,topic_id,is_primary,topic_weight) VALUES (?,?,true,1)",book,topic);
        long feature=jdbc.queryForObject("""
                INSERT INTO backend.book_feature(book_id,topic_id,version,active,vocabulary,knowledge,comprehension,topic_relevance)
                VALUES (?,?,'test-v1',true,0.5,0.5,0.5,1) RETURNING id
                """,Long.class,book,topic);
        long item=jdbc.queryForObject("""
                INSERT INTO backend.recommendation_item(run_id,book_id,feature_id,rank,score,topic_fit,vocabulary_fit,
                    knowledge_fit,comprehension_fit,book_feature_version,vocabulary_requirement,knowledge_requirement,
                    comprehension_requirement,topic_relevance,reasons)
                VALUES (?,?,?,1,0.5,1,0.5,0.5,0.5,'test-v1',0.5,0.5,0.5,1,'["reason one","reason two"]') RETURNING id
                """,Long.class,run,book,feature);
        assertThat(call("GET","/api/recommendations/"+run,aliceToken,null).statusCode()).isEqualTo(403);
        assertThat(call("POST","/api/recommendations/"+item+"/feedback",aliceToken,
                Map.of("userId",bob,"helpful",true)).statusCode()).isEqualTo(403);
        assertThat(call("POST","/api/recommendations/"+item+"/feedback",bobToken,
                Map.of("userId",alice,"helpful",true)).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backend.feedback WHERE recommendation_item_id=?",Long.class,item)).isZero();
    }

    void answerEvidence(long session) {
        for (int i=0;i<3;i++) {
            long question=jdbc.queryForObject("""
                    INSERT INTO backend.question(topic_id,measurement_area,difficulty,prompt,version)
                    VALUES (?,'VOCABULARY',1,'fixture','test-v1') RETURNING id
                    """,Long.class,topic);
            long issued=jdbc.queryForObject("""
                    INSERT INTO backend.assessment_question(session_id,question_id,order_index,
                        measurement_area_snapshot,prompt_snapshot,version_snapshot,difficulty_snapshot)
                    VALUES (?,?,?,'VOCABULARY','fixture','test-v1',1) RETURNING id
                    """,Long.class,session,question,i);
            if (i==1) {
                jdbc.update("""
                        UPDATE backend.assessment_question SET answer_mode_snapshot='MULTIPLE_CHOICE',
                            version_snapshot='generated-question-v2',generated_question_id_snapshot=?,
                            question_spec_id_snapshot=?,choices_snapshot='["A","B","C","D"]',
                            correct_choice_index_snapshot=0,explanation_snapshot='fixture',
                            generated_content_hash_snapshot=?,upstream_provenance_snapshot='{}' WHERE id=?
                        ""","gq_"+"a".repeat(32),"q_"+"a".repeat(20),"sha256:"+"a".repeat(64),issued);
                jdbc.update("""
                        INSERT INTO backend.assessment_answer(assessment_question_id,answer_mode,selected_choice_index,correct)
                        VALUES (?,'MULTIPLE_CHOICE',0,true)
                        """,issued);
            } else if (i==0) {
                jdbc.update("INSERT INTO backend.assessment_answer(assessment_question_id,knows_concept) VALUES (?,true)",issued);
            }
            // Third issued question is unanswered and must not count as evidence.
        }
    }

    long profile(long user,double score,String version) {
        long session=jdbc.queryForObject("""
                INSERT INTO backend.assessment_session(user_id,topic_id,status,completed_at)
                VALUES (?,?,'COMPLETED','2026-10-02T00:00:00Z') RETURNING id
                """,Long.class,user,topic);
        jdbc.update("""
                INSERT INTO backend.reader_profile(session_id,vocabulary,background_knowledge,comprehension,calculation_version,evidence)
                VALUES (?,?,?,?,?,?::jsonb)
                """,session,score,score,score,version,"{\"configHash\":\"preserved\",\"conceptReadiness\":[]}");
        return session;
    }

    HttpResponse<String> call(String method,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token!=null) request.header("Authorization","Bearer "+token);
        try (var client=HttpClient.newHttpClient()) {
            return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
