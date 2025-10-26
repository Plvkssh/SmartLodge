package com.example.booking.web;

import com.example.booking.m.User;
import com.example.booking.repositor.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Административный контроллер для управления пользователями.
 * Предоставляет эндпойнты для CRUD операций с пользователями (только для администраторов).
 */
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearer-jwt")
@Tag(name = "User Administration", description = "API для административного управления пользователями")
public class UserAdminController {
    
    private static final Logger logger = LoggerFactory.getLogger(UserAdminController.class);
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAdminController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Получает список всех пользователей с пагинацией.
     */
    @GetMapping
    @Operation(summary = "Получить список пользователей", description = "Возвращает paginated список всех пользователей системы")
    public ResponseEntity<UserListResponse> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        logger.info("Admin fetching users list, page: {}, size: {}", page, size);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<User> usersPage = userRepository.findAll(pageable);
        
        UserListResponse response = new UserListResponse(
            usersPage.getContent().stream().map(UserResponse::fromUser).toList(),
            usersPage.getNumber(),
            usersPage.getSize(),
            usersPage.getTotalElements()
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает пользователя по идентификатору.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Получить пользователя по ID", description = "Возвращает детальную информацию о пользователе")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        logger.debug("Admin fetching user with id: {}", id);
        
        return userRepository.findById(id)
                .map(user -> {
                    logger.debug("Found user: {}", user.getUsername());
                    return ResponseEntity.ok(UserResponse.fromUser(user));
                })
                .orElseGet(() -> {
                    logger.warn("User not found with id: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Обновляет информацию о пользователе.
     */
    @PutMapping("/{id}")
    @Operation(summary = "Обновить пользователя", description = "Обновляет информацию о пользователе")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        
        logger.info("Admin updating user with id: {}", id);
        
        return userRepository.findById(id)
                .map(existingUser -> {
                    User updatedUser = updateUserFromRequest(existingUser, request);
                    User savedUser = userRepository.save(updatedUser);
                    
                    logger.info("User updated successfully: {}", savedUser.getUsername());
                    return ResponseEntity.ok(UserResponse.fromUser(savedUser));
                })
                .orElseGet(() -> {
                    logger.warn("User not found for update, id: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Удаляет пользователя.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить пользователя", description = "Удаляет пользователя из системы")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        logger.info("Admin deleting user with id: {}", id);
        
        if (!userRepository.existsById(id)) {
            logger.warn("User not found for deletion, id: {}", id);
            return ResponseEntity.notFound().build();
        }
        
        userRepository.deleteById(id);
        logger.info("User deleted successfully, id: {}", id);
        
        return ResponseEntity.noContent().build();
    }

    /**
     * Поиск пользователей по имени.
     */
    @GetMapping("/search")
    @Operation(summary = "Поиск пользователей", description = "Ищет пользователей по частичному совпадению имени")
    public ResponseEntity<UserListResponse> searchUsers(
            @RequestParam String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        logger.debug("Admin searching users with username: {}", username);
        
        Pageable pageable = PageRequest.of(page, size);
        List<User> users = userRepository.findByUsernameContainingIgnoreCase(username);
        
        // Manual pagination for custom query
        int fromIndex = Math.min(page * size, users.size());
        int toIndex = Math.min((page + 1) * size, users.size());
        List<User> paginatedUsers = users.subList(fromIndex, toIndex);
        
        UserListResponse response = new UserListResponse(
            paginatedUsers.stream().map(UserResponse::fromUser).toList(),
            page,
            size,
            users.size()
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает список администраторов.
     */
    @GetMapping("/admins")
    @Operation(summary = "Получить администраторов", description = "Возвращает список всех администраторов системы")
    public ResponseEntity<UserListResponse> getAdmins() {
        logger.debug("Admin fetching list of administrators");
        
        List<User> admins = userRepository.findAdmins();
        UserListResponse response = new UserListResponse(
            admins.stream().map(UserResponse::fromUser).toList(),
            0,
            admins.size(),
            admins.size()
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Обновляет данные пользователя из запроса.
     */
    private User updateUserFromRequest(User user, UpdateUserRequest request) {
        if (request.username() != null && !request.username().isBlank()) {
            // Проверка уникальности username
            if (!user.getUsername().equals(request.username()) && 
                userRepository.existsByUsername(request.username())) {
                throw new IllegalArgumentException("Username already exists: " + request.username());
            }
            user.setUsername(request.username());
        }
        
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 6) {
                throw new IllegalArgumentException("Password must be at least 6 characters long");
            }
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        
        if (request.role() != null) {
            user.setRole(request.role());
        }
        
        return user;
    }

    // DTO классы

    public record UpdateUserRequest(
        String username,
        String password,
        User.UserRole role
    ) {}

    public record UserResponse(
        Long id,
        String username,
        String role,
        String createdAt
    ) {
        public static UserResponse fromUser(User user) {
            return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                // В реальной системе здесь могло бы быть поле createdAt
                "N/A"
            );
        }
    }

    public record UserListResponse(
        List<UserResponse> users,
        int page,
        int size,
        long totalElements
    ) {}

    /**
     * Обработчик исключений для контроллера.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Validation error in UserAdminController: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ex.getMessage()));
    }

    public record ErrorResponse(String error) {}
}
