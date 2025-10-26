package com.example.booking.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.beans.factory.annotation.Value;

/**
 * Конфигурация безопасности приложения.
 * Настраивает JWT аутентификацию, авторизацию по ролям и защиту эндпойнтов.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    // Публичные эндпойнты, не требующие аутентификации
    private static final String[] PUBLIC_ENDPOINTS = {
        "/actuator/health",
        "/actuator/info", 
        "/auth/login",
        "/auth/register",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    };

    // Эндпойнты только для администраторов
    private static final String[] ADMIN_ENDPOINTS = {
        "/actuator/**",
        "/admin/**",
        "/users/**"
    };

    @Value("${security.jwt.secret:default-jwt-secret-key-min-32-chars-long}")
    private String jwtSecret;

    /**
     * Конфигурирует цепочку фильтров безопасности.
     * Настраивает JWT аутентификацию, публичные эндпойнты и политику сессий.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Отключаем CSRF для stateless REST API
            .csrf(csrf -> csrf.disable())
            
            // Stateless приложение - без сессий
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // Настройка авторизации запросов
            .authorizeHttpRequests(authorize -> authorize
                // Публичные эндпойнты
                .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                
                // Административные эндпойнты
                .requestMatchers(ADMIN_ENDPOINTS).hasRole("ADMIN")
                
                // Все остальные запросы требуют аутентификации
                .anyRequest().authenticated()
            )
            
            // Настройка JWT ресурсного сервера
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder()))
            );

        return http.build();
    }

    /**
     * Создает JWT декодер для валидации токенов.
     * Использует HMAC секрет из конфигурации.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        if (!JwtSecretKeyProvider.isSecretStrong(jwtSecret)) {
            throw new IllegalStateException(
                "JWT secret is too weak. Minimum length: " + 
                JwtSecretKeyProvider.getRecommendedSecretLength() + " characters"
            );
        }
        
        return NimbusJwtDecoder.withSecretKey(
            JwtSecretKeyProvider.getHmacKey(jwtSecret)
        ).build();
    }

    /**
     * Возвращает текущий JWT секрет (для тестирования и логирования)
     */
    public String getJwtSecret() {
        return jwtSecret;
    }
}
