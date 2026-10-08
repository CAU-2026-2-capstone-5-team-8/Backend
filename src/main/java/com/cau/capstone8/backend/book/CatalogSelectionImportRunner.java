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
    public CatalogSelectionImportRunner(CatalogSelectionService service,
            @Value("${catalog-selection.manifest-path}") String manifest) {
        this.service=service; this.manifest=Path.of(manifest);
    }
    @Override public void run(ApplicationArguments args) {
        System.out.println("CATALOG_SELECTION_RESULT="+new JsonMapper().writeValueAsString(service.activate(manifest)));
    }
}
