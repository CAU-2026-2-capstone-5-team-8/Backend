package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
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
                insert into backend.assessment_question(session_id,question_id,order_index,measurement_area_snapshot,prompt_snapshot,version_snapshot,difficulty_snapshot)
                select ?,id,0,measurement_area,prompt,version,difficulty from backend.question where id=? returning id
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
        rejects("insert into backend.assessment_question(session_id,question_id,order_index,measurement_area_snapshot,prompt_snapshot,version_snapshot,difficulty_snapshot) "
                + "select " + session + ",id,0,measurement_area,prompt,version,difficulty from backend.question where id=" + questionId("os-vocab-2"));
        rejects("insert into backend.assessment_question(session_id,question_id,order_index,measurement_area_snapshot,prompt_snapshot,version_snapshot,difficulty_snapshot) "
                + "select " + session + ",id,1,measurement_area,prompt,version,difficulty from backend.question where id=" + question);
    }

    @Test void rejectsMoreThanOneAnswerPerIssuedQuestion() {
        long session = newSession();
        long assessmentQuestion = issue(session, questionId("os-vocab-1"), 0);
        jdbc.update("insert into backend.assessment_answer(assessment_question_id,knows_concept) values (?,?)", assessmentQuestion, true);
        rejects("insert into backend.assessment_answer(assessment_question_id,knows_concept) values (" + assessmentQuestion + ",false)");
    }

    @Test void rejectsInvalidDifficultySnapshot() {
        long session = newSession();
        long question = questionId("os-vocab-1");

        rejects("insert into backend.assessment_question(session_id,question_id,order_index,"
                + "measurement_area_snapshot,prompt_snapshot,version_snapshot,difficulty_snapshot) "
                + "select " + session + ",id,0,measurement_area,prompt,version,0 "
                + "from backend.question where id=" + question);
    }

    @Test void rejectsInvalidGeneratedQuestionShapeAndDuplicateUpstreamId() {
        long topic = jdbc.queryForObject(
                "select id from backend.topic where code='OS'", Long.class);
        String generatedQuestionId = "gq_dddddddddddddddddddddddddddddddd";
        jdbc.queryForObject("""
                insert into backend.question(
                    topic_id, measurement_area, difficulty, prompt, concept_id, version, active,
                    answer_mode, generated_question_id, question_spec_id, choices,
                    correct_choice_index, explanation, generated_content_hash, upstream_provenance)
                values (?, 'VOCABULARY', 1, 'valid generated prompt', 'process',
                        'generated-question-v2', true, 'MULTIPLE_CHOICE', ?,
                        'q_dddddddddddddddddddd', '["a","b","c","d"]'::jsonb,
                        0, 'valid explanation',
                        'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                        '{}'::jsonb)
                returning id
                """, Long.class, topic, generatedQuestionId);

        rejectsWrite(() -> jdbc.update("""
                insert into backend.question(
                    topic_id, measurement_area, difficulty, prompt, concept_id, version, active,
                    answer_mode, generated_question_id, question_spec_id, choices,
                    correct_choice_index, explanation, generated_content_hash, upstream_provenance)
                values (?, 'VOCABULARY', 1, 'duplicate generated prompt', 'process',
                        'generated-question-v2', true, 'MULTIPLE_CHOICE', ?,
                        'q_eeeeeeeeeeeeeeeeeeee', '["a","b","c","d"]'::jsonb,
                        0, 'valid explanation',
                        'sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        '{}'::jsonb)
                """, topic, generatedQuestionId));
        rejectsWrite(() -> jdbc.update("""
                insert into backend.question(
                    topic_id, measurement_area, difficulty, prompt, concept_id, version, active,
                    answer_mode, generated_question_id, question_spec_id, choices,
                    correct_choice_index, explanation, generated_content_hash, upstream_provenance)
                values (?, 'VOCABULARY', 1, 'invalid generated prompt', 'process',
                        'generated-question-v2', true, 'MULTIPLE_CHOICE',
                        'gq_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        'q_eeeeeeeeeeeeeeeeeeee', '["a","b","c","d"]'::jsonb,
                        4, 'valid explanation',
                        'sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        '{}'::jsonb)
                """, topic));
    }

    @Test void rejectsAnswerPayloadShapeThatDoesNotMatchAnswerMode() {
        long session = newSession();
        long assessmentQuestion = issue(session, questionId("os-vocab-1"), 0);

        rejectsWrite(() -> jdbc.update("""
                insert into backend.assessment_answer(
                    assessment_question_id, answer_mode, knows_concept,
                    selected_choice_index, correct)
                values (?, 'MULTIPLE_CHOICE', true, 0, true)
                """, assessmentQuestion));
        rejectsWrite(() -> jdbc.update("""
                insert into backend.assessment_answer(
                    assessment_question_id, answer_mode, knows_concept,
                    selected_choice_index, correct)
                values (?, 'MULTIPLE_CHOICE', null, 4, false)
                """, assessmentQuestion));
    }

    @Test void processingStatusRequiresAttemptAndLeaseAndOtherStatusesRejectThem() {
        long withoutOwnership = newSession();
        rejects("update backend.assessment_session set status='PROCESSING' where id=" + withoutOwnership);

        long outsideProcessing = newSession();
        rejects("update backend.assessment_session set attempt_id='" + UUID.randomUUID()
                + "', processing_expires_at=now() where id=" + outsideProcessing);
    }

    @Test void completedStatusAndCompletionTimeMustAppearTogether() {
        long withoutTime = newSession();
        rejects("update backend.assessment_session set status='COMPLETED' where id=" + withoutTime);

        long withoutCompletedStatus = newSession();
        rejects("update backend.assessment_session set completed_at=now() where id="
                + withoutCompletedStatus);
    }

    @Test void readerProfileRequiresUniqueSessionValidScoresAndObjectEvidence() {
        long validSession = completedSession();
        jdbc.update("insert into backend.reader_profile(session_id,vocabulary,background_knowledge,"
                        + "comprehension,calculation_version,evidence) values (?,?,?,?,?,?::jsonb)",
                validSession, 0.0, 0.5, 1.0, "test-v1", "{}");
        rejects("insert into backend.reader_profile(session_id,vocabulary,background_knowledge,"
                + "comprehension,calculation_version,evidence) values ("
                + validSession + ",0.5,0.5,0.5,'test-v1','{}'::jsonb)");

        long invalidScoreSession = completedSession();
        rejects("insert into backend.reader_profile(session_id,vocabulary,background_knowledge,"
                + "comprehension,calculation_version,evidence) values ("
                + invalidScoreSession + ",1.1,0.5,0.5,'test-v1','{}'::jsonb)");

        long invalidEvidenceSession = completedSession();
        rejects("insert into backend.reader_profile(session_id,vocabulary,background_knowledge,"
                + "comprehension,calculation_version,evidence) values ("
                + invalidEvidenceSession + ",0.5,0.5,0.5,'test-v1','[]'::jsonb)");
    }

    long newSession() {
        long userId = jdbc.queryForObject("select id from backend.app_user limit 1", Long.class);
        long topicId = jdbc.queryForObject("select id from backend.topic where code='OS'", Long.class);
        return jdbc.queryForObject("insert into backend.assessment_session(user_id,topic_id) values (?,?) returning id",
                Long.class, userId, topicId);
    }

    long completedSession() {
        long session = newSession();
        jdbc.update(
                "update backend.assessment_session set status='COMPLETED', completed_at=now() where id=?",
                session);
        return session;
    }

    long issue(long session, long question, int orderIndex) {
        return jdbc.queryForObject("""
                insert into backend.assessment_question(session_id,question_id,order_index,measurement_area_snapshot,prompt_snapshot,version_snapshot,difficulty_snapshot)
                select ?,id,?,measurement_area,prompt,version,difficulty from backend.question where id=? returning id
                """, Long.class, session, orderIndex, question);
    }

    long questionId(String demoKey) {
        return jdbc.queryForObject("select id from backend.question where demo_key=?", Long.class, demoKey);
    }

    void rejects(String sql) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> { jdbc.execute(sql); return null; }))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    void rejectsWrite(Runnable write) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            write.run();
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }
}
