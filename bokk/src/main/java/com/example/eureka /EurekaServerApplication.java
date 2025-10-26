package com.example.eureka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Eureka Server Application - Service Discovery Server для микросервисной архитектуры.
 * 
 * Основные функции:
 * - Регистрация и обнаружение микросервисов
 * - Распределение нагрузки между инстансами сервисов
 * - Отслеживание состояния сервисов (health checks)
 * - Поддержка высокой доступности (в кластерной конфигурации)
 * 
 * Микросервисы регистрируются в Eureka и могут находить друг друга по имени,
 * что позволяет динамически масштабировать систему.
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    private static final Logger logger = LoggerFactory.getLogger(EurekaServerApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(EurekaServerApplication.class);
        
        configureApplication(app);
        
        Environment env = app.run(args).getEnvironment();
        logApplicationStartup(env);
    }

    /**
     * Настраивает приложение перед запуском.
     */
    private static void configureApplication(SpringApplication app) {
        // Можно добавить кастомные листенеры или настройки
        app.setAdditionalProfiles("eureka-server");
    }

    /**
     * Логирует информацию о запуске Eureka Server.
     */
    private static void logApplicationStartup(Environment env) {
        String serverPort = env.getProperty("server.port", "8761");
        String hostAddress = "localhost";
        
        String[] activeProfiles = env.getActiveProfiles();
        if (activeProfiles.length == 0) {
            activeProfiles = env.getDefaultProfiles();
        }

        logger.info("""
                
                Eureka Server is running!
                
                ----------------------------------------------------------
                Access URLs:
                Local: \t\thttp://localhost:{}
                Dashboard: \thttp://localhost:{}
                Status: \t\thttp://localhost:{}/actuator/health
                
                Configuration:
                Port: \t\t{}
                Profile(s): \t{}
                Instance ID: \t{}
                Renew Threshold: \t{}
                Renew Percent: \t{}
                ----------------------------------------------------------
                """,
            serverPort,
            serverPort,
            serverPort,
            serverPort,
            Arrays.toString(activeProfiles),
            env.getProperty("eureka.instance.instance-id", "unknown"),
            env.getProperty("eureka.server.renewal-threshold-update-interval-ms", "default"),
            env.getProperty("eureka.server.renewal-percent-threshold", "default")
        );

        // Дополнительная информация для разработки
        if (Arrays.stream(activeProfiles).anyMatch(profile -> profile.equalsIgnoreCase("dev"))) {
            logDevelopmentInfo(env);
        }
    }

    /**
     * Логирует дополнительную информацию для development профиля.
     */
    private static void logDevelopmentInfo(Environment env) {
        String serverPort = env.getProperty("server.port", "8761");
        
        logger.info("""
                Development Mode Active:
                - Self Preservation: \t{}
                - Registry Sync: \t{}
                - Peer Nodes: \t\t{}
                
                Useful Endpoints:
                - Eureka Dashboard: \thttp://localhost:{}
                - Actuator Health: \thttp://localhost:{}/actuator/health
                - Actuator Info: \thttp://localhost:{}/actuator/info
                - Actuator Metrics: \thttp://localhost:{}/actuator/metrics
                """,
            env.getProperty("eureka.server.enable-self-preservation", "true"),
            env.getProperty("eureka.server.wait-time-in-ms-when-sync-empty", "default"),
            env.getProperty("eureka.server.peer-node-urls", "none"),
            serverPort, serverPort, serverPort, serverPort
        );
    }
}
