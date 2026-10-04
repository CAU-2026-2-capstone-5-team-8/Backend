package com.cau.capstone8.backend.book;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CatalogMaintenanceRunnerTest {
    @Test void rejectsWebOrCompetingWritersBeforeSingletonsInitialize() {
        var env = new MockEnvironment();
        env.setActiveProfiles("catalog-maintenance");
        assertThatThrownBy(() -> CatalogMaintenanceRunner.safety(env)).hasMessageContaining("web-application-type=none");
        env.setProperty("spring.main.web-application-type", "none");
        for (String other : java.util.List.of("demo", "local", "local-catalog-import", "discovery-catalog-import", "question-import", "local-assessment-bootstrap")) {
            env.setActiveProfiles("catalog-maintenance", other);
            assertThatThrownBy(() -> CatalogMaintenanceRunner.safety(env)).hasMessageContaining("data writer profile");
        }
        env.setActiveProfiles("catalog-maintenance");
        assertThatCode(() -> CatalogMaintenanceRunner.safety(env)).doesNotThrowAnyException();
    }
}
