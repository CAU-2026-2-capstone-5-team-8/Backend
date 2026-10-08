package com.cau.capstone8.backend.book;

import java.nio.file.Path;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("existing-catalog-preparation")
public class ExistingCatalogPreparationRunner implements ApplicationRunner {
    private final ExistingCatalogPreparationService service;
    private final Environment env;
    public ExistingCatalogPreparationRunner(ExistingCatalogPreparationService service,Environment env) {this.service=service;this.env=env;}
    @Override public void run(ApplicationArguments args) throws Exception {
        if(!"none".equals(env.getProperty("spring.main.web-application-type"))
                || env.getProperty("topic-preparation.enabled",Boolean.class,false)
                || java.util.Arrays.stream(env.getActiveProfiles()).anyMatch(p->!p.equals("existing-catalog-preparation")))
            throw new IllegalArgumentException("existing preparation requires an isolated non-web command with workers disabled");
        System.out.println("EXISTING_CATALOG_PREPARATION_RESULT="+new JsonMapper().writeValueAsString(
                service.apply(Path.of(env.getRequiredProperty("existing-catalog-preparation.plan-path")))));
    }
}
