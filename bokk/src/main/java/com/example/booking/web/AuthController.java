package com.example.booking.web;

import com.example.booking.m.User;
import com.example.booking.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Контроллер для аутентификации и регистрации пользователей.
 * Предоставляет эндпойнты для регистрации, входа и управления учетными записями.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private static final String TOKEN_TYPE = "Bearer";
    
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Регистрирует нового пользователя в системе.
     * 
     * @param request данные для регистрации
     * @return зарегистрированный пользователь (без пароля)
     */
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegistrationRequest request) {
        logger.info("Registration attempt for username: {}", request.username());
        
        try {
            User user = authService.registerUser(request.username(), request.password(), request.isAdmin());
            logger.info("User registered successfully: {}", user.getUsername());
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(UserResponse.fromUser(user));
                    
        } catch (IllegalArgumentException e) {
            logger.warn("Registration failed for {}: {}", request.username(), e.getMessage());
            return ResponseEntity.badRequest()
                    .body(UserResponse.error(e.getMessage()));
        }
    }

    /**
     * Аутентифицирует пользователя и возвращает JWT токен.
     * 
     * @param request данные для входа
     * @return JWT токен доступа
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        logger.info("Login attempt for username: {}", request.username());
        
        try {
            String token = authService.authenticate(request.username(), request.password());
            logger.info("User logged in successfully: {}", request.username());
            
            return ResponseEntity.ok(new AuthResponse(token, TOKEN_TYPE));
            
        } catch (IllegalArgumentException e) {
            logger.warn("Login failed for {}: {}", request.username(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(AuthResponse.error("Invalid credentials"));
        }
    }

    /**
     * Проверяет доступность имени пользователя.
     * 
     * @param username имя пользователя для проверки
     * @return результат проверки доступности
     */
    @GetMapping("/check-username")
    public ResponseEntity<AvailabilityResponse> checkUsernameAvailability(
            @RequestParam @NotBlank String username) {
        
        boolean available = !authService.userExists(username);
        logger.debug("Username availability check: {} - {}", username, available ? "available" : "taken");
        
        return ResponseEntity.ok(new AvailabilityResponse(username, available));
    }

    /**
     * Получает информацию о текущем пользователе.
     * 
     * @param username имя пользователя
     * @return информация о пользователе
     */
    @GetMapping("/user/{username}")
    public ResponseEntity<UserResponse> getUserInfo(@PathVariable String username) {
        logger.debug("Fetching user info for: {}", username);
        
        try {
            User user = authService.getUserInfo(username);
            return ResponseEntity.ok(UserResponse.fromUser(user));
            
        } catch (IllegalArgumentException e) {
            logger.warn("User not found: {}", username);
            return ResponseEntity.notFound().build();
        }
    }

    // DTO классы для запросов и ответов

    /**
     * Запрос на регистрацию пользователя.
     */
    public record RegistrationRequest(
        @NotBlank(message = "Username is required")
        String username,
        
        @NotBlank(message = "Password is required")
        String password,
        
        boolean isAdmin
    ) {}

    /**
     * Запрос на аутентификацию.
     */
    public record LoginRequest(
        @NotBlank(message = "Username is required")
        String username,
        
        @NotBlank(message = "Password is required")
        String password
    ) {}

    /**
     * Ответ с JWT токеном.
     */
    public record AuthResponse(
        String accessToken,
        String tokenType,
        String error
    ) {
        public AuthResponse(String accessToken, String tokenType) {
            this(accessToken, tokenType, null);
        }
        
        public static AuthResponse error(String error) {
            return new AuthResponse(null, null, error);
        }
    }

    /**
     * Ответ с информацией о пользователе.
     */
    public record UserResponse(
        Long id,
        String username,
        String role,
        String error
    ) {
        public static UserResponse fromUser(User user) {
            return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                null
            );
        }
        
        public static UserResponse error(String error) {
            return new UserResponse(null, null, null, error);
        }
    }

    /**
     * Ответ проверки доступности имени пользователя.
     */
    public record AvailabilityResponse(
        String username,
        boolean available
    ) {}
}
