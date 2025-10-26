package com.example.booking.service;

import com.example.booking.m.User;
import com.example.booking.repositor.UserRepository;
import com.example.booking.security.JwtSecretKeyProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * Сервис аутентификации и регистрации пользователей.
 * Обеспечивает регистрацию, вход и генерацию JWT токенов.
 */
@Service
@Transactional
public class AuthService {

    private static final int TOKEN_EXPIRATION_HOURS = 24;
    private static final String CLAIM_SCOPE = "scope";
    private static final String CLAIM_USERNAME = "username";

    private final UserRepository userRepository;
    private final SecretKey jwtSecretKey;

    public AuthService(UserRepository userRepository, 
                      @org.springframework.beans.factory.annotation.Value("${security.jwt.secret}") String secret) {
        this.userRepository = userRepository;
        this.jwtSecretKey = JwtSecretKeyProvider.getHmacKey(secret);
    }

    /**
     * Регистрирует нового пользователя в системе.
     * 
     * @param username имя пользователя
     * @param password пароль (будет захэширован)
     * @param isAdmin флаг администратора
     * @return созданный пользователь
     * @throws IllegalArgumentException если пользователь уже существует
     */
    public User registerUser(String username, String password, boolean isAdmin) {
        validateCredentials(username, password);
        
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("User with username '" + username + "' already exists");
        }

        String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
        User.UserRole role = isAdmin ? User.UserRole.ADMIN : User.UserRole.USER;
        
        User newUser = User.createUser(username, passwordHash, role);
        return userRepository.save(newUser);
    }

    /**
     * Регистрирует нового обычного пользователя.
     */
    public User registerUser(String username, String password) {
        return registerUser(username, password, false);
    }

    /**
     * Аутентифицирует пользователя и генерирует JWT токен.
     * 
     * @param username имя пользователя
     * @param password пароль
     * @return JWT токен для доступа к API
     * @throws IllegalArgumentException если неверные учетные данные
     */
    public String authenticate(String username, String password) {
        validateCredentials(username, password);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!BCrypt.checkpw(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid password");
        }

        return generateToken(user);
    }

    /**
     * Генерирует JWT токен для пользователя.
     */
    private String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiration = now.plusSeconds(TOKEN_EXPIRATION_HOURS * 3600);

        return Jwts.builder()
                .setSubject(user.getId().toString())
                .claim(CLAIM_SCOPE, user.getRole().name())
                .claim(CLAIM_USERNAME, user.getUsername())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiration))
                .signWith(jwtSecretKey)
                .compact();
    }

    /**
     * Валидирует учетные данные при регистрации и входе.
     */
    private void validateCredentials(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        if (password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters long");
        }
    }

    /**
     * Проверяет существование пользователя.
     */
    public boolean userExists(String username) {
        return userRepository.existsByUsername(username);
    }

    /**
     * Возвращает информацию о пользователе по имени.
     */
    public User getUserInfo(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }
}
