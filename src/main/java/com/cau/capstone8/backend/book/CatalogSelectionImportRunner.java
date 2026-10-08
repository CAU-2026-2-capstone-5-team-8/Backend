package com.cau.capstone8.backend.book;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile({"local", "catalog-selection-import"})
@ConditionalOnProperty(name = "catalog-selection.manifest-path")
public class CatalogSelectionImportRunner implements ApplicationRunner {
    private final CatalogSelectionService service;
    private final Path manifest;
    private final boolean bootstrap;
    public CatalogSelectionImportRunner(CatalogSelectionService service,
            @Value("${catalog-selection.manifest-path}") String manifest,
            org.springframework.core.env.Environment environment) {
        this.service=service; this.manifest=Path.of(manifest);
        this.bootstrap=environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local"));
    }
    @Override public void run(ApplicationArguments args) {
        // A normal restart must not replace later provider refreshes or prepared catalogs.
        // Explicit catalog-selection-import remains the operator's activation command.
        if (bootstrap && !service.current().isEmpty()) return;
        System.out.println("CATALOG_SELECTION_RESULT="+new JsonMapper().writeValueAsString(service.activate(manifest)));
    }
}
