package com.cau.capstone8.backend.book;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Explicit local command; never enabled by a normal web application profile. */
@Component
@Profile("catalog-maintenance")
public class CatalogMaintenanceRunner implements ApplicationRunner {
    private final Environment env;
    private final CatalogTransitionService transitions;
    private final CatalogReadinessService readiness;
    public CatalogMaintenanceRunner(Environment env, CatalogTransitionService transitions, CatalogReadinessService readiness) {
        this.env=env; this.transitions=transitions; this.readiness=readiness;
    }
    @org.springframework.context.annotation.Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor safety(Environment env) {
        if (!"none".equals(env.getProperty("spring.main.web-application-type")))
            throw new IllegalArgumentException("catalog-maintenance requires spring.main.web-application-type=none");
        for (String profile : env.getActiveProfiles()) if (Set.of("local", "local-catalog-import", "discovery-catalog-import", "question-import", "demo", "local-assessment-bootstrap").contains(profile))
            throw new IllegalArgumentException("catalog-maintenance cannot run with another data writer profile");
        return factory -> {};
    }
    @Override public void run(ApplicationArguments args) {
        String action=env.getProperty("catalog-maintenance.action", "report");
        Object result = switch (action) {
            case "report" -> readiness.report();
            case "preview" -> transitions.preview(Path.of(required("manifest-path")));
            case "apply" -> transitions.apply(Path.of(required("manifest-path")), required("token"), required("reason"));
            case "rollback" -> {
                UUID id=UUID.fromString(required("transition-id"));
                transitions.rollback(id, required("reason"));
                yield java.util.Map.of("rolledBackTransitionId", id);
            }
            default -> throw new IllegalArgumentException("unknown catalog-maintenance.action");
        };
        System.out.println("CATALOG_MAINTENANCE_RESULT=" + new JsonMapper().writeValueAsString(result));
    }
    private String required(String key) {
        String value=env.getProperty("catalog-maintenance."+key);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("catalog-maintenance."+key+" is required");
        return value;
    }
}
