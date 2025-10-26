package com.example.booking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация OpenAPI для документации Booking Service
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearer-jwt";
    private static final String API_TITLE = "Booking Service API";
    private static final String API_VERSION = "v1";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .components(buildComponents())
                .addSecurityItem(buildSecurityRequirement())
                .info(buildApiInfo());
    }

    private Components buildComponents() {
        SecurityScheme jwtScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        return new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME, jwtScheme);
    }

    private SecurityRequirement buildSecurityRequirement() {
        return new SecurityRequirement()
                .addList(SECURITY_SCHEME_NAME);
    }

    private Info buildApiInfo() {
        return new Info()
                .title(API_TITLE)
                .version(API_VERSION);
    }
}
