package com.cau.capstone8.backend;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class CatalogIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.cau.capstone8.backend.book.DemoCatalogInitializer demo;
    final ObjectMapper json = new ObjectMapper();

    @Test void listsParentAndAssessedTopics() throws Exception {
        var body = ok("/api/topics");
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get(0).path("code").asString()).isEqualTo("CS");
        assertThat(body.get(0).path("parentId").isNull()).isTrue();
        assertThat(body.get(1).path("parentId").asLong()).isEqualTo(body.get(0).path("id").asLong());
    }
    @Test void paginatesBooksInStableIdOrder() throws Exception {
        var first = ok("/api/books?size=2");
        var second = ok("/api/books?page=1&size=2");
        assertThat(first.path("totalElements").asLong()).isEqualTo(5);
        assertThat(first.path("totalPages").asInt()).isEqualTo(3);
        assertThat(first.path("content").size()).isEqualTo(2);
        assertThat(second.path("content").get(0).path("id").asLong())
                .isGreaterThan(first.path("content").get(1).path("id").asLong());
        assertThat(ok("/api/books?page=2&size=2").path("content").size()).isEqualTo(1);
        assertThat(ok("/api/books?page=2147483647&size=100").path("content").size()).isZero();
    }
    @Test void filtersOnlyDirectTopicMembership() throws Exception {
        assertThat(ok("/api/books?topicId=" + topic("OS")).path("totalElements").asLong()).isEqualTo(5);
        assertThat(ok("/api/books?topicId=" + topic("CS")).path("totalElements").asLong()).isZero();
    }
    @Test void returnsBookDetailWithTopicScopedActiveFeature() throws Exception {
        long book = book();
        var detail = ok("/api/books/" + book);
        assertThat(detail.path("title").asString()).isNotBlank();
        assertThat(detail.path("topics").get(0).path("featureAvailable").asBoolean()).isTrue();
        jdbc.update("update backend.book_feature set active=false where book_id=?", book);
        try {
            assertThat(ok("/api/books/" + book).path("topics").get(0).path("featureAvailable").asBoolean()).isFalse();
            assertThat(ok("/api/books").path("totalElements").asLong()).isEqualTo(5);
        } finally { jdbc.update("update backend.book_feature set active=true where book_id=?", book); }
    }
    @Test void reportsInvalidInputWithTraceableErrors() throws Exception {
        for (String path : new String[]{"/api/books?page=-1", "/api/books?size=0", "/api/books?size=101",
                "/api/books?topicId=0", "/api/books/0", "/api/books/nope", "/api/books?page=nope"}) {
            var response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            var error = json.readTree(response.body());
            assertThat(error.path("code").asString()).isEqualTo("INVALID_REQUEST");
            assertThat(error.path("message").asString()).isNotBlank();
            assertThatCode(() -> UUID.fromString(error.path("traceId").asString())).doesNotThrowAnyException();
            assertThat(response.body()).doesNotContain("org.springframework", "Exception", "jdbc:");
        }
    }
    @Test void distinguishesMissingBooksAndTopics() throws Exception {
        assertThat(get("/api/books/9223372036854775807").statusCode()).isEqualTo(404);
        assertThat(get("/api/books?topicId=9223372036854775807").statusCode()).isEqualTo(404);
    }
    @Test void enforcesFeatureRangeUniquenessAndMembership() {
        long book = book();
        rejects("update backend.book_feature set vocabulary=1.01 where book_id=" + book);
        rejects("update backend.book_feature set knowledge='NaN' where book_id=" + book);
        rejects("update backend.book_feature set comprehension='Infinity' where book_id=" + book);
        rejects("update backend.book_topic set topic_weight=-0.1 where book_id=" + book);
        rejects("insert into backend.book_feature(book_id,topic_id,version,active,vocabulary,knowledge,comprehension,topic_relevance) select book_id,topic_id,'another',true,0,0,0,0 from backend.book_feature limit 1");
        rejects("insert into backend.book_feature(book_id,topic_id,version,active,vocabulary,knowledge,comprehension,topic_relevance) values("+book+","+topic("CS")+",'bad',true,0,0,0,0)");
        rejects("insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight) values("+book+","+topic("CS")+",true,0.5)");
    }
    @Test void rejectsTopicCyclesAndOrphanReferences() {
        rejects("update backend.topic set parent_id=id where code='OS'");
        rejects("update backend.topic set parent_id="+topic("OS")+" where code='CS'");
        rejects("update backend.topic set parent_id=999999 where code='OS'");
    }
    @Test void seedsSyntheticSamplesAndReplaysWithoutDuplicates() {
        assertThat(jdbc.queryForObject("select count(*) from backend.app_user", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from backend.book_sample where synthetic=true and provenance is not null", Long.class)).isEqualTo(5);
        demo.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
        assertThat(jdbc.queryForObject("select count(*) from backend.book", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from backend.book_feature", Long.class)).isEqualTo(5);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
    @Test void permitsRestartWithoutDemoProfileAfterSeeding() {
        var normalFlyway = Flyway.configure().dataSource(jdbc.getDataSource())
                .schemas("backend").defaultSchema("backend").locations("classpath:db/migration").load();
        assertThat(normalFlyway.validateWithResult().validationSuccessful).isTrue();
    }
    @Test void neverBorrowsFeatureAvailabilityFromAnotherTopic() throws Exception {
        long book = book();
        jdbc.update("insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight) values(?,?,false,0.5)", book, topic("CS"));
        try {
            var memberships = ok("/api/books/" + book).path("topics");
            assertThat(memberships.size()).isEqualTo(2);
            assertThat(memberships.get(0).path("code").asString()).isEqualTo("CS");
            assertThat(memberships.get(0).path("featureAvailable").asBoolean()).isFalse();
            assertThat(memberships.get(1).path("featureAvailable").asBoolean()).isTrue();
            assertThat(ok("/api/books?topicId=" + topic("CS")).path("totalElements").asLong()).isEqualTo(1);
        } finally { jdbc.update("delete from backend.book_topic where book_id=? and topic_id=?", book, topic("CS")); }
    }
    @Test void serializesCompetingTopicParentsEvenWithRepeatableRead() throws Exception {
        for (int isolation : new int[]{java.sql.Connection.TRANSACTION_READ_COMMITTED, java.sql.Connection.TRANSACTION_REPEATABLE_READ}) {
            long a = jdbc.queryForObject("insert into backend.topic(code,name) values (?, 'race A') returning id", Long.class, UUID.randomUUID().toString());
            long b = jdbc.queryForObject("insert into backend.topic(code,name) values (?, 'race B') returning id", Long.class, UUID.randomUUID().toString());
            try (var first = jdbc.getDataSource().getConnection(); var second = jdbc.getDataSource().getConnection();
                    var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                first.setAutoCommit(false);
                second.setTransactionIsolation(isolation);
                second.setAutoCommit(false);
                int secondPid;
                try (var snapshot = second.createStatement(); var read = snapshot.executeQuery("select pg_backend_pid(), revision from backend.topic_tree_guard")) {
                    assertThat(read.next()).isTrue();
                    secondPid = read.getInt(1);
                }
                try (var update = first.createStatement()) { update.executeUpdate("update backend.topic set parent_id=" + b + " where id=" + a); }
                var result = executor.submit(() -> {
                    try (var update = second.createStatement()) {
                        update.setQueryTimeout(5);
                        update.executeUpdate("update backend.topic set parent_id=" + a + " where id=" + b);
                        second.commit();
                        return "committed";
                    } catch (java.sql.SQLException ex) { second.rollback(); return ex.getSQLState(); }
                });
                boolean waiting = false;
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (!waiting && System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, secondPid));
                    if (!waiting) Thread.sleep(10);
                }
                first.commit();
                assertThat(waiting).as("competing topic writer waits for the guard row").isTrue();
                assertThat(result.get(10, java.util.concurrent.TimeUnit.SECONDS)).isIn("23514", "40001");
            } finally { jdbc.update("delete from backend.topic where id in (?,?)", a, b); }
        }
    }
    long topic(String code) { return jdbc.queryForObject("select id from backend.topic where code=?", Long.class, code); }
    long book() { return jdbc.queryForObject("select min(id) from backend.book", Long.class); }
    void rejects(String sql) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> { jdbc.execute(sql); return null; }))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    JsonNode ok(String path) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return json.readTree(response.body());
    }
    HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
