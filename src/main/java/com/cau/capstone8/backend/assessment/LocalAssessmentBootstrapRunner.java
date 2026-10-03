package com.cau.capstone8.backend.assessment;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("local-assessment-bootstrap")
public class LocalAssessmentBootstrapRunner implements ApplicationRunner {
    private final LocalAssessmentBootstrap bootstrap;
    private final Path manifest;
    public LocalAssessmentBootstrapRunner(LocalAssessmentBootstrap bootstrap,
            @Value("${assessment-bootstrap.manifest-path}") String path) {
        this.bootstrap = bootstrap; manifest = Path.of(path);
    }
    @Override public void run(ApplicationArguments args) {
        System.out.println("ASSESSMENT_BOOTSTRAP_RESULT=" + new JsonMapper().writeValueAsString(bootstrap.importManifest(manifest)));
    }
}
