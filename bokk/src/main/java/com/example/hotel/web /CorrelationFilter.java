package com.example.hotel.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Фильтр для управления корреляцией запросов в микросервисной архитектуре.
 * Генерирует и распространяет идентификатор корреляции для трассировки запросов
 * через все сервисы. Интегрируется с MDC для автоматического логирования.
 * 
 * Порядок: высокий приоритет для обработки в начале цепочки фильтров.
 */
@Component
@Order(1) // Высокий приоритет для обработки в начале цепочки
public class CorrelationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(CorrelationFilter.class);
    
    // Константы для заголовков и MDC
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String USER_AGENT_HEADER = "User-Agent";
    
    // Исключаемые пути из логирования
    private static final String[] EXCLUDED_PATHS = {
        "/actuator/health",
        "/actuator/info",
        "/h2-console"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                  HttpServletResponse response, 
                                  FilterChain filterChain) 
            throws ServletException, IOException {
        
        String correlationId = extractOrGenerateCorrelationId(request);
        String requestId = extractRequestId(request);
        
        // Устанавливаем correlationId в MDC для автоматического логирования
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        
        try {
            // Добавляем correlationId в заголовки ответа
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
            if (requestId != null) {
                response.setHeader(REQUEST_ID_HEADER, requestId);
            }
            
            // Логируем начало обработки запроса (только для не-исключенных путей)
            if (shouldLogRequest(request)) {
                logRequestStart(request, correlationId, requestId);
            }
            
            // Замер времени выполнения запроса
            long startTime = System.currentTimeMillis();
            
            // Продолжаем цепочку фильтров
            filterChain.doFilter(request, response);
            
            // Логируем завершение обработки запроса
            if (shouldLogRequest(request)) {
                long duration = System.currentTimeMillis() - startTime;
                logRequestCompletion(request, response, correlationId, duration);
            }
            
        } finally {
            // Очищаем MDC после обработки запроса
            MDC.clear();
        }
    }

    /**
     * Извлекает correlationId из заголовка или генерирует новый.
     */
    private String extractOrGenerateCorrelationId(HttpServletRequest request) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        
        if (isValidCorrelationId(correlationId)) {
            logger.trace("Using existing correlation ID from header: {}", correlationId);
            return correlationId;
        } else {
            String newCorrelationId = generateCorrelationId();
            logger.trace("Generated new correlation ID: {}", newCorrelationId);
            return newCorrelationId;
        }
    }

    /**
     * Извлекает requestId из заголовка.
     */
    private String extractRequestId(HttpServletRequest request) {
        return request.getHeader(REQUEST_ID_HEADER);
    }

    /**
     * Проверяет валидность correlationId.
     */
    private boolean isValidCorrelationId(String correlationId) {
        return correlationId != null && 
               !correlationId.trim().isEmpty() && 
               correlationId.length() <= 100; // Разумное ограничение длины
    }

    /**
     * Генерирует новый идентификатор корреляции.
     */
    private String generateCorrelationId() {
        return "hotel-" + UUID.randomUUID().toString();
    }

    /**
     * Логирует начало обработки запроса.
     */
    private void logRequestStart(HttpServletRequest request, String correlationId, String requestId) {
        String userAgent = request.getHeader(USER_AGENT_HEADER);
        String clientInfo = getClientInfo(request);
        
        if (requestId != null) {
            logger.info("[{}] Started processing request [{}] - {} {} from {} (User-Agent: {})", 
                       correlationId, requestId, request.getMethod(), 
                       getRequestUri(request), clientInfo, abbreviateUserAgent(userAgent));
        } else {
            logger.info("[{}] Started processing request - {} {} from {} (User-Agent: {})", 
                       correlationId, request.getMethod(), getRequestUri(request), 
                       clientInfo, abbreviateUserAgent(userAgent));
        }
    }

    /**
     * Логирует завершение обработки запроса.
     */
    private void logRequestCompletion(HttpServletRequest request, HttpServletResponse response, 
                                    String correlationId, long duration) {
        int statusCode = response.getStatus();
        String logMessage = "[{}] Completed request - {} {} - Status: {} - Duration: {}ms";
        
        if (statusCode >= 400) {
            logger.warn(logMessage, correlationId, request.getMethod(), 
                       getRequestUri(request), statusCode, duration);
        } else {
            logger.info(logMessage, correlationId, request.getMethod(), 
                       getRequestUri(request), statusCode, duration);
        }
    }

    /**
     * Получает информацию о клиенте.
     */
    private String getClientInfo(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim() + " (via proxy)";
        }
        
        return clientIp;
    }

    /**
     * Сокращает User-Agent для логирования.
     */
    private String abbreviateUserAgent(String userAgent) {
        if (userAgent == null || userAgent.length() <= 50) {
            return userAgent;
        }
        return userAgent.substring(0, 47) + "...";
    }

    /**
     * Получает URI запроса с query parameters.
     */
    private String getRequestUri(HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null) {
            return request.getRequestURI();
        }
        return request.getRequestURI() + "?" + queryString;
    }

    /**
     * Проверяет, нужно ли логировать запрос.
     */
    private boolean shouldLogRequest(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        
        // Не логируем health checks и другие технические эндпойнты
        for (String excludedPath : EXCLUDED_PATHS) {
            if (requestUri.startsWith(excludedPath)) {
                return false;
            }
        }
        
        // Не логируем OPTIONS запросы (CORS preflight)
        return !HttpMethod.OPTIONS.name().equals(request.getMethod());
    }

    /**
     * Позволяет настроить исключения для определенных путей.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        
        // Можно добавить дополнительные исключения здесь
        for (String excludedPath : EXCLUDED_PATHS) {
            if (requestUri.startsWith(excludedPath) && 
                requestUri.equals(excludedPath) || 
                requestUri.startsWith(excludedPath + "/")) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Утилитарный метод для получения correlationId из текущего контекста.
     */
    public static String getCurrentCorrelationId() {
        return MDC.get(CORRELATION_ID_MDC_KEY);
    }

    /**
     * Утилитарный метод для установки correlationId в контекст.
     */
    public static void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.trim().isEmpty()) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }
    }
}
