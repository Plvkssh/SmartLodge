package com.example.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Основной класс приложения Booking Service.
 * Микросервис для управления бронированиями отелей с интеграцией в микросервисную архитектуру.
 * 
 * Основные функции:
 * - Управление жизненным циклом бронирований
 * - Распределенная сага для согласованности данных
 * - Интеграция с Hotel Service через WebClient
 * - JWT аутентификация и авторизация
 * - Discovery Client для регистрации в Eureka
 */
@SpringBootApplication
@EnableDiscoveryClient
public class BookingServiceApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(BookingServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BookingServiceApplication.class);
        
        // Дополнительная конфигурация перед запуском
        configureApplication(app);
        
        Environment env = app.run(args).getEnvironment();
        logApplicationStartup(env);
    }

    /**
     * Настраивает приложение перед запуском.
     */
    private static void configureApplication(SpringApplication app) {
        // Добавляем кастомные листенеры если нужно
        app.addListeners(
            // new ApplicationPidFileWriter() - для записи PID файла в продакшене
        );
    }

    /**
     * Логирует информацию о запуске приложения.
     */
    private static void logApplicationStartup(Environment env) {
        String protocol = env.getProperty("server.ssl.key-store") != null ? "https" : "http";
        String serverPort = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String hostAddress = "localhost";
        
        String[] activeProfiles = env.getActiveProfiles();
        if (activeProfiles.length == 0) {
            activeProfiles = env.getDefaultProfiles();
        }

        logger.info("""
                
                ----------------------------------------------------------
                Application '{}' is running! Access URLs:
                Local: \t\t{}://localhost:{}{}
                External: \t{}://{}:{}{}
                Profile(s): \t{}
                Eureka: \t{}
                Database: \t{}
                ----------------------------------------------------------
                """,
            env.getProperty("spring.application.name", "Booking Service"),
            protocol, serverPort, contextPath,
            protocol, hostAddress, serverPort, contextPath,
            Arrays.toString(activeProfiles),
            env.getProperty("eureka.client.enabled", "false"),
            env.getProperty("spring.datasource.url", "embedded")
        );

        // Логирование дополнительной информации в зависимости от профиля
        if (Arrays.stream(activeProfiles).anyMatch(profile -> profile.equalsIgnoreCase("dev"))) {
            logDevelopmentInfo(env);
        }
    }

    /**
     * Логирует дополнительную информацию для development профиля.
     */
    private static void logDevelopmentInfo(Environment env) {
        logger.info("""
                Development Mode Active:
                - H2 Console: \t\thttp://localhost:{}/h2-console
                - Swagger UI: \t\thttp://localhost:{}/swagger-ui.html
                - Actuator: \t\thttp://localhost:{}/actuator
                """,
            env.getProperty("server.port", "8080"),
            env.getProperty("server.port", "8080"),
            env.getProperty("server.port", "8080")
        );
    }

    /**
     * Бин для глобальной обработки исключений (может быть вынесен в отдельный конфиг).
     * В реальном приложении рекомендуется использовать @ControllerAdvice.
     */
    // @Bean
    // public RestExceptionHandler restExceptionHandler() {
    //     return new RestExceptionHandler();
    // }
}
