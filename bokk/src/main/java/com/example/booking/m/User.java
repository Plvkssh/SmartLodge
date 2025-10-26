package com.example.booking.model;

import jakarta.persistence.*;

/**
 * Сущность пользователя системы.
 * Поддерживает аутентификацию и авторизацию через роли.
 */
@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_username", columnNames = "username")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    /**
     * Роли пользователей в системе
     */
    public enum UserRole {
        USER,   // Обычный пользователь - может создавать свои бронирования
        ADMIN   // Администратор - полный доступ ко всем функциям
    }

    // Конструкторы
    public User() {}

    public User(String username, String passwordHash, UserRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }

    /**
     * Проверяет, является ли пользователь администратором
     */
    public boolean isAdmin() {
        return UserRole.ADMIN.equals(this.role);
    }

    /**
     * Проверяет, является ли пользователь обычным пользователем
     */
    public boolean isRegularUser() {
        return UserRole.USER.equals(this.role);
    }

    @Override
    public String toString() {
        return String.format(
            "User{id=%d, username='%s', role=%s}",
            id, username, role
        );
    }

    /**
     * Создает пользователя с ролью USER
     */
    public static User createUser(String username, String passwordHash) {
        return new User(username, passwordHash, UserRole.USER);
    }

    /**
     * Создает пользователя с ролью ADMIN
     */
    public static User createAdmin(String username, String passwordHash) {
        return new User(username, passwordHash, UserRole.ADMIN);
    }
}
