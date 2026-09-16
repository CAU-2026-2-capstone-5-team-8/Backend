package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ReaderProfileMigrationUpgradeTest {
    private static final String PREVIOUS_VERSION = "20260912193000";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @Test
    void upgradesExistingCompletedSessionAndBackfillsSnapshots() throws Exception {
        flyway(MigrationVersion.fromVersion(PREVIOUS_VERSION)).migrate();
        OffsetDateTime previousUpdate = OffsetDateTime.parse("2026-09-15T00:00:00Z");
        long assessmentQuestionId;
        long incompleteProcessingSessionId;
        long validProcessingSessionId;
        try (Connection connection = connection()) {
            long userId = returningId(connection, """
                    insert into backend.app_user(display_name) values ('upgrade user') returning id
                    """);
            long topicId = returningId(connection, """
                    insert into backend.topic(code,name) values ('UPGRADE','Upgrade') returning id
                    """);
            long questionId = returningId(connection, """
                    insert into backend.question(
                        topic_id,measurement_area,difficulty,prompt,version,active)
                    values (%d,'VOCABULARY',4,'upgrade prompt','upgrade-v1',true)
                    returning id
                    """.formatted(topicId));
            long sessionId = returningId(connection, """
                    insert into backend.assessment_session(
                        user_id,topic_id,status,attempt_id,processing_expires_at,updated_at)
                    values (%d,%d,'COMPLETED','%s','2026-09-15T01:00:00Z','%s') returning id
                    """.formatted(userId, topicId, UUID.randomUUID(), previousUpdate));
            incompleteProcessingSessionId = returningId(connection, """
                    insert into backend.assessment_session(user_id,topic_id,status,attempt_id)
                    values (%d,%d,'PROCESSING','%s') returning id
                    """.formatted(userId, topicId, UUID.randomUUID()));
            validProcessingSessionId = returningId(connection, """
                    insert into backend.assessment_session(
                        user_id,topic_id,status,attempt_id,processing_expires_at)
                    values (%d,%d,'PROCESSING','%s','2026-09-15T01:00:00Z') returning id
                    """.formatted(userId, topicId, UUID.randomUUID()));
            assessmentQuestionId = returningId(connection, """
                    insert into backend.assessment_question(
                        session_id,question_id,order_index,measurement_area_snapshot,
                        prompt_snapshot,version_snapshot)
                    values (%d,%d,0,'VOCABULARY','upgrade prompt','upgrade-v1')
                    returning id
                    """.formatted(sessionId, questionId));
        }

        flyway(null).migrate();

        try (Connection connection = connection()) {
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            select completed_at
                            from backend.assessment_session
                            where status='COMPLETED'
                            """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("completed_at", OffsetDateTime.class))
                        .isEqualTo(previousUpdate);
            }
            assertOwnershipState(
                    connection, incompleteProcessingSessionId, "IN_PROGRESS", true, true);
            assertOwnershipState(
                    connection, validProcessingSessionId, "PROCESSING", false, false);
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery(
                            "select difficulty_snapshot from backend.assessment_question where id="
                                    + assessmentQuestionId)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("difficulty_snapshot")).isEqualTo(4);
            }
        }
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

    private void assertOwnershipState(
            Connection connection,
            long sessionId,
            String expectedStatus,
            boolean attemptCleared,
            boolean leaseCleared) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        select status, attempt_id is null as attempt_cleared,
                               processing_expires_at is null as lease_cleared
                        from backend.assessment_session
                        where id=%d
                        """.formatted(sessionId))) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("status")).isEqualTo(expectedStatus);
            assertThat(result.getBoolean("attempt_cleared")).isEqualTo(attemptCleared);
            assertThat(result.getBoolean("lease_cleared")).isEqualTo(leaseCleared);
        }
    }

    private long returningId(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
