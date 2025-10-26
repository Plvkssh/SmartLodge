package com.example.hotel.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация OpenAPI для документации Hotel Service.
 * Настраивает Swagger UI с JWT аутентификацией и метаданными API.
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearer-jwt";
    private static final String API_TITLE = "Hotel Service API";
    private static final String API_DESCRIPTION = """
        Microservice for hotel and room management. Provides CRUD operations for hotels and rooms,
        room availability checks, booking statistics, and integration with Booking Service
        for distributed transaction handling.
        
        ## Key Features:
        - Hotel and room management
        - Room availability and booking statistics  
        - Integration with Booking Service via saga pattern
        - JWT-based authentication and authorization
        """;
    private static final String API_VERSION = "v1.0.0";

    @Value("${spring.application.name:hotel-service}")
    private String applicationName;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .components(buildSecurityComponents())
                .addSecurityItem(buildSecurityRequirement())
                .info(buildApiInfo());
    }

    /**
     * Создает компоненты безопасности для JWT аутентификации.
     */
    private Components buildSecurityComponents() {
        SecurityScheme jwtScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("JWT токен для аутентификации. Получите токен через эндпойнт /auth/login")
                .name("JWT Authentication");

        return new Components()
                .addSecuritySchemes(SECURITY_SCHEME_NAME, jwtScheme);
    }

    /**
     * Создает требование безопасности для всех защищенных эндпойнтов.
     */
    private SecurityRequirement buildSecurityRequirement() {
        return new SecurityRequirement()
                .addList(SECURITY_SCHEME_NAME);
    }

    /**
     * Создает метаинформацию об API.
     */
    private Info buildApiInfo() {
        return new Info()
                .title(API_TITLE)
                .description(API_DESCRIPTION)
                .version(API_VERSION)
                .contact(buildContactInfo())
                .license(buildLicenseInfo())
                .extensions(java.util.Map.of(
                    "x-service-name", applicationName,
                    "x-api-version", API_VERSION,
                    "x-supported-languages", java.util.List.of("en", "ru")
                ));
    }

    /**
     * Создает контактную информацию для API.
     */
    private Contact buildContactInfo() {
        return new Contact()
                .name("Hotel Service Team")
                .email("hotel-service@example.com")
                .url("https://example.com/hotel-service");
    }

    /**
     * Создает информацию о лицензии API.
     */
    private License buildLicenseInfo() {
        return new License()
                .name("Apache 2.0")
                .url("https://www.apache.org/licenses/LICENSE-2.0.html");
    }
}
