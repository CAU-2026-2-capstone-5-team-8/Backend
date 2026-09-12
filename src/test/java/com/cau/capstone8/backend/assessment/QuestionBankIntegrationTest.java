package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers
class QuestionBankIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired QuestionRepository questions;
    @Autowired JdbcTemplate jdbc;

    @Test void seedsThreeQuestionsPerMeasurementAreaForOsTopic() {
        long topicId = topic("OS");
        assertThat(questions.countByTopicIdAndActiveTrue(topicId)).isEqualTo(9);
    }

    @Test void samplesRequestedCountPerAreaOnly() {
        long topicId = topic("OS");
        List<Question> sampled = questions.sampleActiveByTopic(topicId, 2);
        assertThat(sampled).hasSize(6);
        assertThat(sampled).extracting(Question::getMeasurementArea)
                .containsExactlyInAnyOrder(MeasurementArea.VOCABULARY, MeasurementArea.VOCABULARY,
                        MeasurementArea.BACKGROUND_KNOWLEDGE, MeasurementArea.BACKGROUND_KNOWLEDGE,
                        MeasurementArea.COMPREHENSION, MeasurementArea.COMPREHENSION);
    }

    @Test void neverSamplesInactiveQuestions() {
        long topicId = topic("OS");
        jdbc.update("update backend.question set active=false where demo_key='os-vocab-1'");
        try {
            List<Question> sampled = questions.sampleActiveByTopic(topicId, 3);
            assertThat(sampled.stream().filter(q -> q.getMeasurementArea() == MeasurementArea.VOCABULARY).count())
                    .isEqualTo(2);
        } finally { jdbc.update("update backend.question set active=true where demo_key='os-vocab-1'"); }
    }

    long topic(String code) { return jdbc.queryForObject("select id from backend.topic where code=?", Long.class, code); }
}
