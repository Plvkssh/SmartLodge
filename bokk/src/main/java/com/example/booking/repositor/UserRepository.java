package com.example.booking.repositor;

import com.example.booking.m.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы с пользователями.
 * Поддерживает аутентификацию, поиск по имени и управление ролями.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Находит пользователя по имени.
     * Используется для аутентификации при входе в систему.
     */
    Optional<User> findByUsername(String username);

    /**
     * Проверяет существование пользователя с указанным именем.
     * Используется при регистрации для предотвращения дубликатов.
     */
    boolean existsByUsername(String username);

    /**
     * Находит всех пользователей с указанной ролью.
     * Используется для административных функций.
     */
    List<User> findByRole(User.UserRole role);

    /**
     * Находит пользователей по частичному совпадению имени.
     * Используется для поиска в административном интерфейсе.
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%'))")
    List<User> findByUsernameContainingIgnoreCase(@Param("username") String username);

    /**
     * Находит администраторов системы.
     * Упрощает поиск пользователей с правами ADMIN.
     */
    default List<User> findAdmins() {
        return findByRole(User.UserRole.ADMIN);
    }

    /**
     * Находит обычных пользователей.
     * Упрощает поиск пользователей с правами USER.
     */
    default List<User> findRegularUsers() {
        return findByRole(User.UserRole.USER);
    }
}
