package com.cau.capstone8.backend.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(
        title = "CAU Capstone 8 Backend",
        version = "0.0.1",
        description = "Reader assessment and book recommendation prototype. Stage 3: topic and book catalog reads."))
public class OpenApiConfig {
}
