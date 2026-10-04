package com.cau.capstone8.backend.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LearningRecommendationMigrationUpgradeTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @Test void existingSuccessKeepsResultAndHashAndHasNoProcessingLease() throws Exception {
        config().target("20261004115000").load().migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("insert into backend.app_user(id,display_name) values (9001,'existing reader')");
            sql.execute("insert into backend.topic(id,code,name) values (9001,'LEASE','Lease topic')");
            sql.execute("insert into backend.assessment_session(id,user_id,topic_id,status,completed_at) values (9001,9001,9001,'COMPLETED',now())");
            sql.execute("insert into backend.reader_profile(id,session_id,vocabulary,background_knowledge,comprehension,calculation_version,evidence) values (9001,9001,1,1,1,'reader-v1','{}')");
            sql.execute("insert into backend.learning_recommendation(id,user_id,topic_id,profile_id,request_key,request_hash,input_snapshot,result) values (9001,9001,9001,9001,'old-key','" + "a".repeat(64) + "','{}','{\"items\":[],\"modelVersion\":\"concept-learning-v1\"}')");
        }
        var migration = config().load();
        migration.migrate();
        migration.validate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = connection.createStatement();
             var rows = sql.executeQuery("select status,attempt_id,processing_expires_at,request_hash,result->>'modelVersion' as model from backend.learning_recommendation where id=9001")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("status")).isEqualTo("SUCCEEDED");
            assertThat(rows.getObject("attempt_id")).isNull();
            assertThat(rows.getObject("processing_expires_at")).isNull();
            assertThat(rows.getString("request_hash")).isEqualTo("a".repeat(64));
            assertThat(rows.getString("model")).isEqualTo("concept-learning-v1");
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration config() {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("backend").defaultSchema("backend").createSchemas(true);
    }
}
