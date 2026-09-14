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
}
