package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class AssessmentCreationIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();

    @Test void createsSessionWithNineQuestionsAndNoAnswerKey() throws Exception {
        var response = post(user(), topic("OS"));
        assertThat(response.statusCode()).isEqualTo(201);
        var body = json.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("CREATED");
        assertThat(body.path("userId").asLong()).isEqualTo(user());
        assertThat(body.path("topicId").asLong()).isEqualTo(topic("OS"));
        var questions = body.path("questions");
        assertThat(questions.size()).isEqualTo(9);
        var areaCounts = new java.util.HashMap<String, Integer>();
        for (var q : questions) {
            assertThat(q.path("prompt").asString()).isNotBlank();
            String area = q.path("measurementArea").asString();
            assertThat(area).isIn("VOCABULARY", "BACKGROUND_KNOWLEDGE", "COMPREHENSION");
            areaCounts.merge(area, 1, Integer::sum);
        }
        assertThat(areaCounts).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("VOCABULARY", 3, "BACKGROUND_KNOWLEDGE", 3, "COMPREHENSION", 3));
        assertThat(response.body()).doesNotContain("correctOptionId", "answerKey", "options");
    }

    @Test void rejectsUnknownUserOrTopic() throws Exception {
        assertThat(post(9223372036854775807L, topic("OS")).statusCode()).isEqualTo(404);
        assertThat(post(user(), 9223372036854775807L).statusCode()).isEqualTo(404);
    }

    @Test void rejectsTopicWithoutReadyQuestionBank() throws Exception {
        assertThat(post(user(), topic("CS")).statusCode()).isEqualTo(409);
    }

    @Test void rejectsNonPositiveIds() throws Exception {
        assertThat(post(0, topic("OS")).statusCode()).isEqualTo(400);
        assertThat(post(user(), -1).statusCode()).isEqualTo(400);
    }

    @Test void returnsCreatedSessionWithUnansweredQuestionsOnGet() throws Exception {
        long sessionId = json.readTree(post(user(), topic("OS")).body()).path("id").asLong();

        var response = get(sessionId);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.path("id").asLong()).isEqualTo(sessionId);
        assertThat(body.path("status").asString()).isEqualTo("CREATED");
        var questions = body.path("questions");
        assertThat(questions.size()).isEqualTo(9);
        for (var q : questions) {
            assertThat(q.path("knowsConcept").isNull()).isTrue();
        }
    }

    @Test void returns404ForUnknownSession() throws Exception {
        assertThat(get(9223372036854775807L).statusCode()).isEqualTo(404);
    }

    @Test void rejectsNonPositiveSessionId() throws Exception {
        assertThat(get(0).statusCode()).isEqualTo(400);
    }

    @Test void savesAnswerAndMovesSessionToInProgress() throws Exception {
        long sessionId = json.readTree(post(user(), topic("OS")).body()).path("id").asLong();
        long questionId = json.readTree(get(sessionId).body()).path("questions").get(0).path("id").asLong();

        var response = put(sessionId, questionId, true);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.path("id").asLong()).isEqualTo(questionId);
        assertThat(body.path("knowsConcept").asBoolean()).isTrue();

        var reloaded = json.readTree(get(sessionId).body());
        assertThat(reloaded.path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(reloaded.path("questions").get(0).path("knowsConcept").asBoolean()).isTrue();
    }

    @Test void resubmittingSameQuestionReplacesTheAnswer() throws Exception {
        long sessionId = json.readTree(post(user(), topic("OS")).body()).path("id").asLong();
        long questionId = json.readTree(get(sessionId).body()).path("questions").get(0).path("id").asLong();

        put(sessionId, questionId, true);
        put(sessionId, questionId, false);

        var reloaded = json.readTree(get(sessionId).body());
        assertThat(reloaded.path("questions").get(0).path("knowsConcept").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from backend.assessment_answer where assessment_question_id=?",
                Integer.class, questionId)).isEqualTo(1);
    }

    @Test void rejectsAnswerForQuestionOutsideTheSession() throws Exception {
        long sessionA = json.readTree(post(user(), topic("OS")).body()).path("id").asLong();
        long sessionB = json.readTree(post(user(), topic("OS")).body()).path("id").asLong();
        long questionFromB = json.readTree(get(sessionB).body()).path("questions").get(0).path("id").asLong();

        assertThat(put(sessionA, questionFromB, true).statusCode()).isEqualTo(404);
    }

    @Test void rejectsAnswerOnCompletedSession() throws Exception {
        long sessionId = json.readTree(post(user(), topic("OS")).body()).path("id").asLong();
        long questionId = json.readTree(get(sessionId).body()).path("questions").get(0).path("id").asLong();
        jdbc.update("update backend.assessment_session set status='COMPLETED' where id=?", sessionId);

        assertThat(put(sessionId, questionId, true).statusCode()).isEqualTo(409);
    }

    @Test void rejectsMissingKnowsConcept() throws Exception {
        long sessionId = json.readTree(post(user(), topic("OS")).body()).path("id").asLong();
        long questionId = json.readTree(get(sessionId).body()).path("questions").get(0).path("id").asLong();

        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/assessments/" + sessionId + "/answers/" + questionId))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
        }
    }

    long user() { return jdbc.queryForObject("select id from backend.app_user limit 1", Long.class); }
    long topic(String code) { return jdbc.queryForObject("select id from backend.topic where code=?", Long.class, code); }

    HttpResponse<String> post(long userId, long topicId) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/assessments"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"userId\":" + userId + ",\"topicId\":" + topicId + "}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    HttpResponse<String> get(long sessionId) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/assessments/" + sessionId))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    HttpResponse<String> put(long sessionId, long assessmentQuestionId, boolean knowsConcept) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/assessments/" + sessionId + "/answers/" + assessmentQuestionId))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"knowsConcept\":" + knowsConcept + "}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
