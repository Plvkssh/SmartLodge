package com.example.booking.repositor;

import com.example.booking.m.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы с бронированиями.
 * Поддерживает поиск по идемпотентности, пользователям и проверку доступности номеров.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Находит бронирование по ключу идемпотентности.
     * Используется для предотвращения дублирующих запросов.
     */
    Optional<Booking> findByRequestId(String requestId);

    /**
     * Находит все бронирования пользователя.
     * Используется для фильтрации "только свои бронирования".
     */
    List<Booking> findByUserId(Long userId);

    /**
     * Проверяет существование активного бронирования по ключу идемпотентности.
     */
    boolean existsByRequestId(String requestId);

    /**
     * Находит бронирования по идентификатору комнаты.
     */
    List<Booking> findByRoomId(Long roomId);

    /**
     * Находит активные (подтвержденные) бронирования для указанной комнаты в заданный период.
     * Используется для проверки доступности номера при создании нового бронирования.
     */
    @Query("SELECT b FROM Booking b WHERE b.roomId = :roomId AND b.status = 'CONFIRMED' " +
           "AND ((b.startDate BETWEEN :startDate AND :endDate) OR " +
           "(b.endDate BETWEEN :startDate AND :endDate) OR " +
           "(b.startDate <= :startDate AND b.endDate >= :endDate))")
    List<Booking> findConflictingBookings(@Param("roomId") Long roomId,
                                         @Param("startDate") LocalDate startDate,
                                         @Param("endDate") LocalDate endDate);

    /**
     * Находит все бронирования в статусе PENDING.
     * Используется для обработки саг и компенсирующих действий.
     */
    List<Booking> findByStatus(Booking.BookingStatus status);

    /**
     * Находит бронирования пользователя с пагинацией.
     * Используется для реализации пагинации в API.
     */
    @Query("SELECT b FROM Booking b WHERE b.userId = :userId ORDER BY b.createdAt DESC")
    List<Booking> findUserBookingsWithPagination(@Param("userId") Long userId,
                                                org.springframework.data.domain.Pageable pageable);
}
