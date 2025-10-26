package com.example.hotel.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;

/**
 * Конфигурация безопасности для Hotel Service.
 * Настраивает JWT аутентификацию, авторизацию по ролям и защиту эндпойнтов.
 * Интегрируется с Eureka Discovery и предоставляет защищенные API для управления отелями и номерами.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    // Публичные эндпойнты, не требующие аутентификации
    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health",
        "/actuator/info",
        "/h2-console/**",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/webjars/**"
    };

    // Эндпойнты только для администраторов
    private static final String[] ADMIN_ENDPOINTS = {
        "/actuator/**",
        "/admin/**",
        "/hotels/**",
        "/rooms/statistics/**"
    };

    // Эндпойнты для интеграции с Booking Service (саги)
    private static final String[] SAGA_ENDPOINTS = {
        "/rooms/*/hold",
        "/rooms/*/confirm", 
        "/rooms/*/release"
    };

    @Value("${security.jwt.secret:hotel-service-secure-jwt-key-min-32-chars-long-2024}")
    private String jwtSecret;

    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    /**
     * Конфигурирует цепочку фильтров безопасности.
     * Настраивает JWT аутентификацию, публичные эндпойнты и политику сессий.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Базовые настройки безопасности
        configureBasicSecurity(http);
        
        // Настройка авторизации запросов
        configureAuthorization(http);
        
        // Настройка JWT ресурсного сервера
        configureJwt(http);
        
        // Дополнительные настройки для разных окружений
        configureEnvironmentSpecificSettings(http);

        logger.info("Security configuration completed successfully");
        return http.build();
    }

    /**
     * Настраивает базовые параметры безопасности.
     */
    private void configureBasicSecurity(HttpSecurity http) throws Exception {
        http
            // Отключаем CSRF для stateless REST API
            .csrf(csrf -> csrf.disable())
            
            // Stateless приложение - без сессий
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // CORS настройки
            .cors(cors -> cors.configure(http));
    }

    /**
     * Настраивает авторизацию HTTP запросов.
     */
    private void configureAuthorization(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
            // Публичные эндпойнты
            .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
            
            // Административные эндпойнты
            .requestMatchers(ADMIN_ENDPOINTS).hasRole("ADMIN")
            
            // Эндпойнты саг - доступны для сервисов (обычно с service account)
            .requestMatchers(SAGA_ENDPOINTS).hasAnyRole("SERVICE", "ADMIN")
            
            // Эндпойнты комнат для пользователей
            .requestMatchers("/rooms/available/**").hasAnyRole("USER", "ADMIN")
            .requestMatchers("/rooms/{id}").hasAnyRole("USER", "ADMIN")
            
            // Все остальные запросы требуют аутентификации
            .anyRequest().authenticated()
        );
    }

    /**
     * Настраивает JWT ресурсный сервер.
     */
    private void configureJwt(HttpSecurity http) throws Exception {
        http.oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.decoder(jwtDecoder()))
        );
    }

    /**
     * Настраивает дополнительные параметры для разных окружений.
     */
    private void configureEnvironmentSpecificSettings(HttpSecurity http) throws Exception {
        // Настройки для H2 Console (только в development)
        if (h2ConsoleEnabled) {
            http.headers(headers -> headers
                .frameOptions(frame -> frame.disable())
            );
            logger.warn("H2 Console frame options disabled - for development only");
        }
        
        // Дополнительные заголовки безопасности
        http.headers(headers -> headers
            .contentSecurityPolicy(csp -> csp
                .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'"))
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(31536000))
        );
    }

    /**
     * Создает JWT декодер для валидации токенов.
     * Использует HMAC секрет из конфигурации.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        validateJwtSecret();
        
        try {
            return NimbusJwtDecoder.withSecretKey(
                JwtSecretKeyProvider.getHmacKey(jwtSecret)
            ).build();
        } catch (Exception e) {
            logger.error("Failed to create JWT decoder", e);
            throw new IllegalStateException("JWT decoder configuration failed", e);
        }
    }

    /**
     * Валидирует JWT секрет при старте приложения.
     */
    private void validateJwtSecret() {
        if (!JwtSecretKeyProvider.isSecretStrong(jwtSecret)) {
            String errorMessage = String.format(
                "JWT secret is too weak. Minimum length: %d characters. " +
                "Current length: %d characters. " +
                "Please set a strong secret in security.jwt.secret property",
                JwtSecretKeyProvider.getRecommendedSecretLength(),
                jwtSecret.length()
            );
            
            logger.error(errorMessage);
            throw new IllegalStateException(errorMessage);
        }
        
        logger.info("JWT secret validation passed - key length: {} bits", 
                   JwtSecretKeyProvider.getRequiredKeyLength() * 8);
    }

    /**
     * Логирует информацию о конфигурации безопасности при старте.
     */
    @PostConstruct
    public void logSecurityConfiguration() {
        logger.info("""
                
                ===== HOTEL SERVICE SECURITY CONFIGURATION =====
                JWT Algorithm: \t\tHS256
                Key Length: \t\t{} bits
                Secret Strength: \t{}
                H2 Console: \t\t{}
                Public Endpoints: \t{}
                Admin Endpoints: \t{}
                Saga Endpoints: \t{}
                ===============================================
                """,
            JwtSecretKeyProvider.getRequiredKeyLength() * 8,
            JwtSecretKeyProvider.isSecretStrong(jwtSecret) ? "STRONG" : "WEAK",
            h2ConsoleEnabled ? "ENABLED" : "DISABLED",
            PUBLIC_ENDPOINTS.length,
            ADMIN_ENDPOINTS.length,
            SAGA_ENDPOINTS.length
        );

        if (h2ConsoleEnabled) {
            logger.warn("H2 Console is enabled - this should be disabled in production");
        }
        
        if (!JwtSecretKeyProvider.isSecretStrong(jwtSecret)) {
            logger.warn("JWT secret may be weak. Consider using a stronger secret.");
        }
    }

    /**
     * Возвращает текущий JWT секрет (для тестирования).
     */
    protected String getJwtSecret() {
        return jwtSecret;
    }
}
