package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("demo")
@Testcontainers
class AssessmentSessionSchemaIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void createsSessionWithDefaultStatusAndAcceptsSnapshotAndAnswer() {
        long session = newSession();
        long question = questionId("os-vocab-1");
        long assessmentQuestion = jdbc.queryForObject("""
                insert into backend.assessment_question(session_id,question_id,order_index,measurement_area_snapshot,prompt_snapshot,version_snapshot)
                select ?,id,0,measurement_area,prompt,version from backend.question where id=? returning id
                """, Long.class, session, question);
        jdbc.update("insert into backend.assessment_answer(assessment_question_id,knows_concept) values (?,?)",
                assessmentQuestion, true);
        String status = jdbc.queryForObject("select status from backend.assessment_session where id=?", String.class, session);
        assertThat(status).isEqualTo("CREATED");
    }

    @Test void rejectsUnknownStatusValue() {
        rejects("update backend.assessment_session set status='UNKNOWN' where id=" + newSession());
    }

    @Test void rejectsDuplicateOrderIndexOrQuestionWithinSameSession() {
        long session = newSession();
        long question = questionId("os-vocab-1");
        issue(session, question, 0);
        rejects("insert into backend.assessment_question(session_id,question_id,order_index,measurement_area_snapshot,prompt_snapshot,version_snapshot) "
                + "select " + session + ",id,0,measurement_area,prompt,version from backend.question where id=" + questionId("os-vocab-2"));
        rejects("insert into backend.assessment_question(session_id,question_id,order_index,measurement_area_snapshot,prompt_snapshot,version_snapshot) "
                + "select " + session + ",id,1,measurement_area,prompt,version from backend.question where id=" + question);
    }

    @Test void rejectsMoreThanOneAnswerPerIssuedQuestion() {
        long session = newSession();
        long assessmentQuestion = issue(session, questionId("os-vocab-1"), 0);
        jdbc.update("insert into backend.assessment_answer(assessment_question_id,knows_concept) values (?,?)", assessmentQuestion, true);
        rejects("insert into backend.assessment_answer(assessment_question_id,knows_concept) values (" + assessmentQuestion + ",false)");
    }

    long newSession() {
        long userId = jdbc.queryForObject("select id from backend.app_user limit 1", Long.class);
        long topicId = jdbc.queryForObject("select id from backend.topic where code='OS'", Long.class);
        return jdbc.queryForObject("insert into backend.assessment_session(user_id,topic_id) values (?,?) returning id",
                Long.class, userId, topicId);
    }

    long issue(long session, long question, int orderIndex) {
        return jdbc.queryForObject("""
                insert into backend.assessment_question(session_id,question_id,order_index,measurement_area_snapshot,prompt_snapshot,version_snapshot)
                select ?,id,?,measurement_area,prompt,version from backend.question where id=? returning id
                """, Long.class, session, orderIndex, question);
    }

    long questionId(String demoKey) {
        return jdbc.queryForObject("select id from backend.question where demo_key=?", Long.class, demoKey);
    }

    void rejects(String sql) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> { jdbc.execute(sql); return null; }))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
