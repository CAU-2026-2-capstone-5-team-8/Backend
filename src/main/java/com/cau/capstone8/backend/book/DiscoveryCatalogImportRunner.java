package com.cau.capstone8.backend.book;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("discovery-catalog-import")
public class DiscoveryCatalogImportRunner implements ApplicationRunner {
    private final DiscoveryCatalogImportService importer;
    private final Path manifest;
    public DiscoveryCatalogImportRunner(DiscoveryCatalogImportService importer,
            @Value("${discovery-catalog-import.manifest-path}") String manifest) {
        this.importer=importer;
        this.manifest=Path.of(manifest);
    }
    @Override public void run(ApplicationArguments args) {
        System.out.println("DISCOVERY_CATALOG_IMPORT_RESULT="+
                new JsonMapper().writeValueAsString(importer.importManifest(manifest)));
    }
}
