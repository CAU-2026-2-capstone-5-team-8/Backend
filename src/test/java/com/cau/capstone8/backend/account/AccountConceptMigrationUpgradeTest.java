package com.cau.capstone8.backend.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AccountConceptMigrationUpgradeTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @Test void upgradesMergedAccountSchemaWithoutOutOfOrderOrLostAccounts() throws Exception {
        config().target("20261002090000").load().migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = connection.createStatement()) {
            sql.execute("INSERT INTO backend.app_user(id,display_name,bio) VALUES (999,'Existing reader','Keep me')");
            sql.execute("INSERT INTO backend.user_account(user_id,email,password_hash) VALUES (999,'existing@example.com','preserved-test-hash')");
            sql.execute("INSERT INTO backend.account_session(token_hash,user_id,expires_at) VALUES ('" + "a".repeat(64) + "',999,now()+interval '1 hour')");
        }
        var migration = config().load();
        migration.migrate();
        migration.validate();
        assertThat(migration.info().pending()).isEmpty();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT display_name,bio,password_hash FROM backend.app_user u JOIN backend.user_account a ON a.user_id=u.id WHERE u.id=999")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("Existing reader");
                assertThat(rows.getString(2)).isEqualTo("Keep me");
                assertThat(rows.getString(3)).isEqualTo("preserved-test-hash");
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM backend.account_session WHERE user_id=999")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
            for (String table : java.util.List.of("learning_recommendation", "local_catalog_import", "discovery_catalog_member")) {
                try (var rows = sql.executeQuery("SELECT to_regclass('backend." + table + "')")) {
                    rows.next(); assertThat(rows.getString(1)).as(table).isNotNull();
                }
            }
        }
    }
    private org.flywaydb.core.api.configuration.FluentConfiguration config() {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("backend").defaultSchema("backend").createSchemas(true);
    }
}
