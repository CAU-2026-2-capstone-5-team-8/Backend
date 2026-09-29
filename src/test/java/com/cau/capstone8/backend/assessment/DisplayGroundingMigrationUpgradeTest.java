package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DisplayGroundingMigrationUpgradeTest {
    private static final String PREVIOUS_VERSION = "20260928095924";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @Test
    void upgradesExistingSelfReportAndV2RowsAndEnforcesVersionedPassageShape()
            throws Exception {
        flyway(MigrationVersion.fromVersion(PREVIOUS_VERSION)).migrate();
        long topicId;
        long selfQuestionId;
        long v2QuestionId;
        long sessionId;
        try (Connection connection = connection()) {
            long userId = returningId(connection, """
                    insert into backend.app_user(display_name) values ('passage upgrade user')
                    returning id
                    """);
            topicId = returningId(connection, """
                    insert into backend.topic(code,name) values ('PASSAGE-UPGRADE','Passage Upgrade')
                    returning id
                    """);
            selfQuestionId = returningId(connection, """
                    insert into backend.question(
                        topic_id,measurement_area,difficulty,prompt,version,active)
                    values (%d,'COMPREHENSION',1,'legacy self report','legacy-v1',true)
                    returning id
                    """.formatted(topicId));
            v2QuestionId = returningId(connection, v2QuestionSql(topicId, "a"));
            sessionId = returningId(connection, """
                    insert into backend.assessment_session(user_id,topic_id)
                    values (%d,%d) returning id
                    """.formatted(userId, topicId));
            execute(connection, """
                    insert into backend.assessment_question(
                        session_id,question_id,order_index,measurement_area_snapshot,
                        prompt_snapshot,version_snapshot,difficulty_snapshot)
                    values (%d,%d,0,'COMPREHENSION','legacy self report','legacy-v1',1)
                    """.formatted(sessionId, selfQuestionId));
            execute(connection, """
                    insert into backend.assessment_question(
                        session_id,question_id,order_index,measurement_area_snapshot,
                        prompt_snapshot,version_snapshot,difficulty_snapshot,
                        answer_mode_snapshot,generated_question_id_snapshot,
                        question_spec_id_snapshot,choices_snapshot,correct_choice_index_snapshot,
                        explanation_snapshot,generated_content_hash_snapshot,
                        upstream_provenance_snapshot)
                    values (%d,%d,1,'VOCABULARY','v2 prompt','generated-question-v2',1,
                        'MULTIPLE_CHOICE','gq_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'q_aaaaaaaaaaaaaaaaaaaa','["a","b","c","d"]'::jsonb,0,
                        'v2 explanation',
                        'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        '{}'::jsonb)
                    """.formatted(sessionId, v2QuestionId));
        }

        flyway(null).migrate();

        try (Connection connection = connection()) {
            assertThat(count(connection, """
                    select count(*) from backend.question
                    where id in (%d,%d) and passage is null
                    """.formatted(selfQuestionId, v2QuestionId))).isEqualTo(2);
            assertThat(count(connection, """
                    select count(*) from backend.assessment_question
                    where session_id=%d and passage_snapshot is null
                    """.formatted(sessionId))).isEqualTo(2);

            long v4QuestionId = returningId(connection, v4QuestionSql(topicId, "b", true));
            assertThat(v4QuestionId).isPositive();
            long v4SnapshotId = returningId(connection, """
                    insert into backend.assessment_question(
                        session_id,question_id,order_index,measurement_area_snapshot,
                        passage_snapshot,prompt_snapshot,version_snapshot,difficulty_snapshot,
                        answer_mode_snapshot,generated_question_id_snapshot,
                        question_spec_id_snapshot,choices_snapshot,correct_choice_index_snapshot,
                        explanation_snapshot,generated_content_hash_snapshot,
                        upstream_provenance_snapshot)
                    values (%d,%d,2,'COMPREHENSION','display passage','v4 prompt',
                        'generated-question-v4',2,'MULTIPLE_CHOICE',
                        'gq_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','q_bbbbbbbbbbbbbbbbbbbb',
                        '["a","b","c","d"]'::jsonb,3,'v4 explanation',
                        'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        '{}'::jsonb)
                    returning id
                    """.formatted(sessionId, v4QuestionId));
            assertThat(v4SnapshotId).isPositive();
            assertThatThrownBy(() -> execute(connection, """
                    update backend.assessment_question set passage_snapshot=null where id=%d
                    """.formatted(v4SnapshotId))).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> returningId(
                    connection, v4QuestionSql(topicId, "c", false)))
                    .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> execute(connection, """
                    update backend.question set passage='not allowed for v2' where id=%d
                    """.formatted(v2QuestionId))).isInstanceOf(Exception.class);
            assertThatThrownBy(() -> execute(connection, """
                    update backend.question set passage='not allowed for self report' where id=%d
                    """.formatted(selfQuestionId))).isInstanceOf(Exception.class);
        }
    }

    private String v2QuestionSql(long topicId, String marker) {
        return """
                insert into backend.question(
                    topic_id,measurement_area,difficulty,prompt,version,active,answer_mode,
                    generated_question_id,question_spec_id,choices,correct_choice_index,
                    explanation,generated_content_hash,upstream_provenance)
                values (%d,'VOCABULARY',1,'v2 prompt','generated-question-v2',true,
                    'MULTIPLE_CHOICE','gq_%s%s','q_%s','["a","b","c","d"]'::jsonb,0,
                    'v2 explanation','sha256:%s','{}'::jsonb)
                returning id
                """.formatted(
                topicId,
                marker.repeat(1), marker.repeat(31), marker.repeat(20), marker.repeat(64));
    }

    private String v4QuestionSql(long topicId, String marker, boolean withPassage) {
        String passage = withPassage ? "'display passage'" : "null";
        return """
                insert into backend.question(
                    topic_id,measurement_area,difficulty,passage,prompt,version,active,answer_mode,
                    generated_question_id,question_spec_id,choices,correct_choice_index,
                    explanation,generated_content_hash,upstream_provenance)
                values (%d,'COMPREHENSION',2,%s,'v4 prompt','generated-question-v4',true,
                    'MULTIPLE_CHOICE','gq_%s','q_%s','["a","b","c","d"]'::jsonb,3,
                    'v4 explanation','sha256:%s','{}'::jsonb)
                returning id
                """.formatted(
                topicId, passage, marker.repeat(32), marker.repeat(20), marker.repeat(64));
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("backend")
                .defaultSchema("backend")
                .createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private long returningId(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
