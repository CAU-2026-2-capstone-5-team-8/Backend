package com.cau.capstone8.backend.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.cau.capstone8.backend.integration.ml.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
@Import(LearningRecommendationConcurrencyIntegrationTest.GatewayConfiguration.class)
class LearningRecommendationConcurrencyIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired JdbcTemplate jdbc;
    @Autowired ControlledGateway gateway;
    @Autowired PlatformTransactionManager transactionManager;
    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();
    long user, topic, profile;

    @BeforeEach void setup() {
        gateway.reset();
        topic = jdbc.queryForObject("select id from backend.topic where code='OS'", Long.class);
        user = jdbc.queryForObject("insert into backend.app_user(display_name) values ('lease-user') returning id", Long.class);
        long session = jdbc.queryForObject("insert into backend.assessment_session(user_id,topic_id,status,completed_at) values (?,?,'COMPLETED',now()) returning id", Long.class, user, topic);
        profile = jdbc.queryForObject("""
            insert into backend.reader_profile(session_id,vocabulary,background_knowledge,comprehension,calculation_version,evidence)
            values (?,1,1,1,'reader-v1','{"conceptProfile":{"version":"concept-abilities-v2","abilities":[
            {"conceptId":"process","ability":"application","measurementContext":"prior-knowledge","responseCount":1,"correctCount":1}]}}') returning id
            """, Long.class, session);
        long book = jdbc.queryForObject("insert into backend.book(title,author,description,ml_book_id) values ('lease title','lease author','',?) returning id", Long.class, "lease-book-" + user);
        jdbc.update("insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight) values (?,?,true,1)", book, topic);
        jdbc.update("""
            insert into backend.book_ranking_v2_projection(book_id,topic_id,version,active,topic_distribution,
                covered_concepts,prerequisite_concepts,config_version,config_hash,source_artifact_version,source_artifact_hash)
            values (?,?,'book-v1',true,'{"operating-systems":1}','[{"concept":"thread","weight":1}]',
                '[]','features-v1',?,'synthetic-v1',?)
            """, book, topic, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64));
    }

    @Test void mlRunsOutsideTransactionAndDoesNotHoldUserLock() throws Exception {
        gateway.blockFirst = true;
        var first = createAsync("boundary");
        try {
            assertThat(gateway.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.transactionActive).isFalse();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.execute("set local lock_timeout='1s'");
                jdbc.queryForObject("select id from backend.app_user where id=? for update", Long.class, user);
            });
        } finally { gateway.release.countDown(); }
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    }

    @Test void duplicateConflictsBeforeFirstCallCompletesAndThenReplays() throws Exception {
        gateway.blockFirst = true;
        var first = createAsync("duplicate");
        try {
            assertThat(gateway.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(createAsync("duplicate").get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
            assertThat(gateway.calls.get()).isEqualTo(1);
        } finally { gateway.release.countDown(); }
        var created = first.get(10, TimeUnit.SECONDS);
        assertThat(created.statusCode()).isEqualTo(201);
        var replay = create("duplicate");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(created.body()));
        assertThat(gateway.calls.get()).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test void anotherKeyForSameUserCanFinishWhileFirstCallIsBlocked() throws Exception {
        gateway.blockFirst = true;
        var first = createAsync("first-key");
        try {
            assertThat(gateway.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(createAsync("other-key").get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        } finally { gateway.release.countDown(); }
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(count()).isEqualTo(2);
    }

    @Test void failureLeavesKeyAvailableForSuccessfulRetry() throws Exception {
        gateway.failFirst = true;
        assertThat(create("retry").statusCode()).isEqualTo(502);
        assertThat(count()).isZero();
        assertThat(create("retry").statusCode()).isEqualTo(201);
        assertThat(create("retry").statusCode()).isEqualTo(200);
        assertThat(gateway.calls.get()).isEqualTo(2);
    }

    @Test void resultPersistenceFailureReleasesClaimForRetry() throws Exception {
        jdbc.execute("""
            create function backend.reject_learning_result() returns trigger language plpgsql as $$
            begin
              if new.request_key='persist-failure' and new.result <> '{}'::jsonb then
                raise exception 'test persistence failure';
              end if;
              return new;
            end $$
            """);
        jdbc.execute("create trigger reject_learning_result before insert or update on backend.learning_recommendation for each row execute function backend.reject_learning_result()");
        try {
            assertThat(create("persist-failure").statusCode()).isEqualTo(500);
            assertThat(count()).isZero();
        } finally {
            jdbc.execute("drop trigger reject_learning_result on backend.learning_recommendation");
            jdbc.execute("drop function backend.reject_learning_result()");
        }
        assertThat(create("persist-failure").statusCode()).isEqualTo(201);
        assertThat(count()).isEqualTo(1);
    }

    @Test void getDoesNotExposeUnfinishedResult() throws Exception {
        gateway.blockFirst = true;
        var first = createAsync("get-processing");
        try {
            assertThat(gateway.entered.await(5, TimeUnit.SECONDS)).isTrue();
            long id = jdbc.queryForObject("select id from backend.learning_recommendation where user_id=?", Long.class, user);
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/learning-recommendations/" + id)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
        } finally { gateway.release.countDown(); }
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    }

    @Test void expiredResultCannotOverwriteReplacement() throws Exception {
        staleAttempt(false);
    }

    @Test void staleFailureCannotDeleteSuccessfulReplacement() throws Exception {
        staleAttempt(true);
    }

    private void staleAttempt(boolean fail) throws Exception {
        gateway.blockFirst = true;
        gateway.failFirst = fail;
        var first = createAsync("replace");
        HttpResponse<String> replacement;
        try {
            assertThat(gateway.entered.await(5, TimeUnit.SECONDS)).isTrue();
            expire("replace");
            replacement = createAsync("replace").get(5, TimeUnit.SECONDS);
            assertThat(replacement.statusCode()).withFailMessage(replacement.body()).isEqualTo(201);
        } finally { gateway.release.countDown(); }
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(fail ? 502 : 409);
        assertThat(count()).isEqualTo(1);
        assertThat(json.readTree(create("replace").body())).isEqualTo(json.readTree(replacement.body()));
    }

    @Test void expiredResultWithoutReplacementIsRejectedAndRetryable() throws Exception {
        gateway.blockFirst = true;
        var first = createAsync("expired");
        try {
            assertThat(gateway.entered.await(5, TimeUnit.SECONDS)).isTrue();
            expire("expired");
        } finally { gateway.release.countDown(); }
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        assertThat(count()).isZero();
        assertThat(create("expired").statusCode()).isEqualTo(201);
    }

    private void expire(String key) {
        assertThat(jdbc.update("update backend.learning_recommendation set processing_expires_at=now()-interval '1 second' where user_id=? and request_key=?", user, key)).isEqualTo(1);
    }
    private int count() {
        return jdbc.queryForObject("select count(*) from backend.learning_recommendation where user_id=?", Integer.class, user);
    }
    private CompletableFuture<HttpResponse<String>> createAsync(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try { return create(key); } catch (Exception e) { throw new CompletionException(e); }
        });
    }
    private HttpResponse<String> create(String key) throws Exception {
        String body = "{\"userId\":" + user + ",\"topicId\":" + topic + ",\"profileId\":" + profile + ",\"ability\":\"application\",\"topK\":5}";
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/learning-recommendations"))
            .header("Content-Type", "application/json").header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration static class GatewayConfiguration {
        @Bean @Primary ControlledGateway controlledGateway() { return new ControlledGateway(); }
    }
    static class ControlledGateway extends StubMlGateway {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean blockFirst, failFirst, transactionActive;
        volatile CountDownLatch entered, release;
        void reset() {
            calls.set(0); blockFirst = false; failFirst = false; transactionActive = false;
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
        }
        @Override public Map<String, Object> learningFit(Map<String, Object> input) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
                entered.countDown();
                if (blockFirst) try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                if (failFirst) throw new MlGatewayException("ML_TEST_FAILURE", "추천 계산 실패");
            }
            var candidate = (Map<?, ?>) ((List<?>) input.get("candidateBooks")).getLast();
            return Map.of("modelVersion", "concept-learning-v1", "topicId", input.get("topicId"),
                "ability", input.get("ability"), "items", List.of(Map.of("bookId", candidate.get("bookId"),
                "rank", 1, "status", "check-first", "reasons", List.of("call " + call))));
        }
    }
}
