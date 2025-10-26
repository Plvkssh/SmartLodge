package com.example.hotel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Основной класс приложения Hotel Service.
 * Микросервис для управления отелями, номерами и их доступностью.
 * Интегрируется с Booking Service через распределенные саги для согласованного управления бронированиями.
 * 
 * Основные функции:
 * - CRUD операции для отелей и номеров
 * - Управление доступностью номеров через систему блокировок
 * - Распределенные саги для согласованности данных между сервисами
 * - Аналитика и статистика по отелям и бронированиям
 * - JWT аутентификация и авторизация
 * - Service Discovery через Eureka
 * 
 * Архитектура:
 * - REST API для внешних клиентов и межсервисной коммуникации
 * - Реляционная база данных (H2 для разработки, PostgreSQL для продакшена)
 * - Интеграция с Spring Cloud для микросервисной экосистемы
 */
@SpringBootApplication
@EnableDiscoveryClient
public class HotelServiceApplication {

    private static final Logger logger = LoggerFactory.getLogger(HotelServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HotelServiceApplication.class);
        
        configureApplication(app);
        
        Environment env = app.run(args).getEnvironment();
        logApplicationStartup(env);
    }

    /**
     * Настраивает приложение перед запуском.
     */
    private static void configureApplication(SpringApplication app) {
        // Добавляем кастомные листенеры для дополнительной конфигурации
        app.addListeners(
            // new ApplicationPidFileWriter() - для записи PID файла в продакшене
        );
        
        // Устанавливаем дополнительные системные свойства если нужно
        System.setProperty("spring.application.name", "hotel-service");
    }

    /**
     * Логирует информацию о запуске приложения.
     */
    private static void logApplicationStartup(Environment env) {
        String protocol = env.getProperty("server.ssl.key-store") != null ? "https" : "http";
        String serverPort = env.getProperty("server.port", "8082");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String hostAddress = "localhost";
        
        String[] activeProfiles = env.getActiveProfiles();
        if (activeProfiles.length == 0) {
            activeProfiles = env.getDefaultProfiles();
        }

        logger.info("""
              
                
                Hotel Service is running!
                
                ----------------------------------------------------------
                Application Information:
                Name: \t\t{}
                Version: \t{}
                Port: \t\t{}
                Context Path: \t{}
                
                Access URLs:
                Local: \t\t{}://localhost:{}{}
                External: \t{}://{}:{}{}
                
                Environment:
                Profile(s): \t{}
                Eureka: \t{}
                Database: \t{}
                JWT Security: \t{}
                
                API Documentation:
                Swagger UI: \t{}://localhost:{}{}/swagger-ui.html
                API Docs: \t{}://localhost:{}{}/v3/api-docs
                
                Development Tools:
                H2 Console: \t{}://localhost:{}{}/h2-console
                Actuator: \t{}://localhost:{}{}/actuator
                ----------------------------------------------------------
                """,
            env.getProperty("spring.application.name", "hotel-service"),
            env.getProperty("app.version", "1.0.0"),
            serverPort,
            contextPath,
            protocol, serverPort, contextPath,
            protocol, hostAddress, serverPort, contextPath,
            Arrays.toString(activeProfiles),
            env.getProperty("eureka.client.enabled", "false"),
            env.getProperty("spring.datasource.url", "embedded"),
            env.getProperty("security.jwt.secret", "configured") != null ? "ENABLED" : "DISABLED",
            protocol, serverPort, contextPath,
            protocol, serverPort, contextPath,
            protocol, serverPort, contextPath,
            protocol, serverPort, contextPath
        );

        // Дополнительная информация в зависимости от профиля
        if (Arrays.stream(activeProfiles).anyMatch(profile -> profile.equalsIgnoreCase("dev"))) {
            logDevelopmentInfo(env, protocol, serverPort, contextPath);
        }
        
        if (Arrays.stream(activeProfiles).anyMatch(profile -> profile.equalsIgnoreCase("prod"))) {
            logProductionInfo(env);
        }
    }

    /**
     * Логирует дополнительную информацию для development профиля.
     */
    private static void logDevelopmentInfo(Environment env, String protocol, String serverPort, String contextPath) {
        logger.info("""
                Development Mode Active:
                - Database: \t\t{}
                - H2 Console Enabled: \t{}
                - Show SQL: \t\t{}
                - JWT Secret: \t\t{}
                
                Development Endpoints:
                - H2 Database Console: {}://localhost:{}{}/h2-console
                - JDBC URL: \t\t{}
                - Username: \t\t{}
                - Actuator Health: \t{}://localhost:{}{}/actuator/health
                - Actuator Info: \t{}://localhost:{}{}/actuator/info
                """,
            env.getProperty("spring.datasource.url", "unknown"),
            env.getProperty("spring.h2.console.enabled", "false"),
            env.getProperty("spring.jpa.show-sql", "false"),
            env.getProperty("security.jwt.secret", "default") != null ? "CONFIGURED" : "DEFAULT",
            protocol, serverPort, contextPath,
            env.getProperty("spring.datasource.url", "unknown"),
            env.getProperty("spring.datasource.username", "sa"),
            protocol, serverPort, contextPath,
            protocol, serverPort, contextPath
        );
    }

    /**
     * Логирует информацию для production профиля.
     */
    private static void logProductionInfo(Environment env) {
        logger.info("""
                Production Mode Active:
                - Database: \t\t{}
                - Eureka Server: \t{}
                - JWT Security: \t{}
                - Actuator Endpoints: \t{}
                
                Security Notes:
                - JWT Secret: \t{}
                - SSL Enabled: \t{}
                - CORS Configured: \t{}
                """,
            env.getProperty("spring.datasource.url", "unknown"),
            env.getProperty("eureka.client.service-url.defaultZone", "not configured"),
            env.getProperty("security.jwt.secret", "configured") != null ? "ENABLED" : "DISABLED",
            env.getProperty("management.endpoints.web.exposure.include", "health,info"),
            env.getProperty("security.jwt.secret", "").length() >= 32 ? "STRONG" : "WEAK",
            env.getProperty("server.ssl.enabled", "false"),
            env.getProperty("cors.allowed-origins", "not configured")
        );
        
        // Предупреждения для production
        if (env.getProperty("security.jwt.secret", "").length() < 32) {
            logger.warn("⚠️  JWT secret may be weak for production use. Consider using a longer secret.");
        }
        
        if ("false".equals(env.getProperty("server.ssl.enabled", "false"))) {
            logger.warn("⚠️  SSL is disabled. Consider enabling SSL for production.");
        }
    }

    /**
     * Бин для глобальной конфигурации (может быть вынесен в отдельный конфиг класс).
     */
    // @Bean
    // @Profile("dev")
    // public CommandLineRunner demoData(HotelRepository hotelRepository, RoomRepository roomRepository) {
    //     return args -> {
    //         logger.info("Loading demo data for development environment...");
    //         // Загрузка тестовых данных
    //     };
    // }
    
    /**
     * Бин для health check кастомных компонентов.
     */
    // @Bean
    // public HealthIndicator hotelServiceHealth() {
    //     return () -> Health.up()
    //             .withDetail("service", "hotel-service")
    //             .withDetail("timestamp", LocalDateTime.now())
    //             .build();
    // }
}
