package com.cau.capstone8.backend.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer")
@OpenAPIDefinition(security = @SecurityRequirement(name = "bearerAuth"), info = @Info(
        title = "CAU Capstone 8 Backend",
        version = "0.0.1",
        description = "Account profiles, reading readiness and book recommendations. Login/register and catalog reads are public; other APIs require a bearer token."))
public class OpenApiConfig {
}
