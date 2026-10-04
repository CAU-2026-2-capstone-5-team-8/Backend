package com.cau.capstone8.backend.book;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real local files, fresh disposable DB only. Never points at an existing user DB. */
@EnabledIfEnvironmentVariable(named="BOOKMATCH_CATALOG_MANIFEST", matches=".+")
@SpringBootTest
@Testcontainers
class CatalogReadinessLiveHandoffTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");
    @Autowired LocalCatalogImportService importer;
    @Autowired CatalogReadinessService readiness;

    @Test void reportsActualPinnedHandoffWithoutInventingQuestionCoverage() {
        var imported = importer.importManifest(Path.of(System.getenv("BOOKMATCH_CATALOG_MANIFEST")));
        var report = readiness.report();
        var topic = report.topics().stream().filter(t -> t.topicId()==imported.topicId()).findFirst().orElseThrow();
        assertThat(topic.bookCount()).isEqualTo(imported.bookIds().size());
        assertThat(topic.objectiveCount()).isZero();
        assertThat(topic.sourceLinkedConceptBookCount()).isLessThanOrEqualTo(topic.conceptBookCount());
        System.out.println("ACTUAL_CATALOG_READINESS=" + new JsonMapper().writeValueAsString(report));
    }
}
