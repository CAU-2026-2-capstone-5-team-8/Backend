package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.cau.capstone8.backend.integration.ml.MlGateway;
import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
@Import(AssessmentCompletionFailureIntegrationTest.FailingMlConfiguration.class)
class AssessmentCompletionFailureIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void restoresSessionAndStoresOnlySanitizedFailureWhenMlCallFails() throws Exception {
        long sessionId = answeredSession();

        HttpResponse<String> response = complete(sessionId);

        assertThat(response.statusCode()).isEqualTo(502);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo("ML_TEST_FAILURE");
        assertThat(body.path("message").asString()).contains("잠시 후 다시 시도");
        assertThat(response.body()).doesNotContain("sensitive upstream detail");
        MapRow state = jdbc.queryForObject(
                "select status, attempt_id is null as attempt_cleared, "
                        + "processing_expires_at is null as lease_cleared, "
                        + "last_failure_code, last_failure_message "
                        + "from backend.assessment_session where id=?",
                (resultSet, rowNumber) -> new MapRow(
                        resultSet.getString("status"),
                        resultSet.getBoolean("attempt_cleared"),
                        resultSet.getBoolean("lease_cleared"),
                        resultSet.getString("last_failure_code"),
                        resultSet.getString("last_failure_message")),
                sessionId);
        assertThat(state).isEqualTo(new MapRow(
                "IN_PROGRESS",
                true,
                true,
                "ML_TEST_FAILURE",
                "ML 프로필 계산에 실패했습니다."));
        assertThat(jdbc.queryForObject(
                "select count(*) from backend.reader_profile where session_id=?",
                Integer.class,
                sessionId)).isZero();
    }

    private long answeredSession() {
        long userId = jdbc.queryForObject("select id from backend.app_user limit 1", Long.class);
        long topicId = jdbc.queryForObject(
                "select id from backend.topic where code='OS'", Long.class);
        long sessionId = jdbc.queryForObject(
                "insert into backend.assessment_session(user_id,topic_id,status) "
                        + "values (?,?,'IN_PROGRESS') returning id",
                Long.class,
                userId,
                topicId);
        jdbc.update("""
                insert into backend.assessment_question(
                    session_id, question_id, order_index, measurement_area_snapshot,
                    prompt_snapshot, version_snapshot, difficulty_snapshot)
                select ?, id, (row_number() over (order by id) - 1)::int,
                       measurement_area, prompt, version, difficulty
                from backend.question
                where topic_id=? and active
                order by id
                """, sessionId, topicId);
        jdbc.update("""
                insert into backend.assessment_answer(assessment_question_id, knows_concept)
                select id, true
                from backend.assessment_question
                where session_id=?
                """, sessionId);
        return sessionId;
    }

    private HttpResponse<String> complete(long sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/assessments/" + sessionId + "/complete"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private record MapRow(
            String status,
            boolean attemptCleared,
            boolean leaseCleared,
            String failureCode,
            String failureMessage) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingMlConfiguration {
        @Bean
        @Primary
        MlGateway failingMlGateway() {
            return new MlGateway() {
                @Override
                public com.cau.capstone8.backend.integration.ml.MlProfileResult calculateProfile(
                        com.cau.capstone8.backend.integration.ml.MlProfileRequest request) {
                    throw new MlGatewayException("ML_TEST_FAILURE", "sensitive upstream detail");
                }

                @Override
                public com.cau.capstone8.backend.integration.ml.MlRankResult rank(
                        com.cau.capstone8.backend.integration.ml.MlRankRequest request) {
                    throw new MlGatewayException("ML_TEST_FAILURE", "sensitive upstream detail");
                }
            };
        }
    }
}
