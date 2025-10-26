package com.example.hotel.repositor;

import com.example.hotel.m.RoomReservationLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для управления блокировками номеров.
 * Обеспечивает работу распределенной саги бронирования, предотвращая двойные бронирования.
 */
@Repository
public interface RoomReservationLockRepository extends JpaRepository<RoomReservationLock, Long> {

    /**
     * Находит блокировку по идентификатору запроса (для идемпотентности).
     */
    Optional<RoomReservationLock> findByRequestId(String requestId);

    /**
     * Проверяет существование блокировки по идентификатору запроса.
     */
    boolean existsByRequestId(String requestId);

    /**
     * Находит все блокировки для указанной комнаты.
     */
    List<RoomReservationLock> findByRoomId(Long roomId);

    /**
     * Находит все блокировки для указанной комнаты с пагинацией.
     */
    Page<RoomReservationLock> findByRoomId(Long roomId, Pageable pageable);

    /**
     * Находит активные блокировки для указанной комнаты (HELD статус).
     */
    List<RoomReservationLock> findByRoomIdAndStatus(Long roomId, RoomReservationLock.LockStatus status);

    /**
     * Находит блокировки по статусу.
     */
    List<RoomReservationLock> findByStatus(RoomReservationLock.LockStatus status);

    /**
     * Находит блокировки по статусу с пагинацией.
     */
    Page<RoomReservationLock> findByStatus(RoomReservationLock.LockStatus status, Pageable pageable);

    /**
     * Находит блокировки по нескольким статусам.
     */
    List<RoomReservationLock> findByStatusIn(List<RoomReservationLock.LockStatus> statuses);

    /**
     * Находит конфликтующие блокировки для комнаты в указанный период.
     * Используется для проверки доступности номера при создании новой блокировки.
     */
    @Query("SELECT l FROM RoomReservationLock l WHERE " +
           "l.roomId = :roomId AND " +
           "l.status IN :statuses AND " +
           "((l.startDate BETWEEN :startDate AND :endDate) OR " +
           "(l.endDate BETWEEN :startDate AND :endDate) OR " +
           "(l.startDate <= :startDate AND l.endDate >= :endDate))")
    List<RoomReservationLock> findConflictingLocks(
            @Param("roomId") Long roomId,
            @Param("statuses") List<RoomReservationLock.LockStatus> statuses,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Проверяет, есть ли конфликтующие блокировки для комнаты в указанный период.
     */
    @Query("SELECT COUNT(l) > 0 FROM RoomReservationLock l WHERE " +
           "l.roomId = :roomId AND " +
           "l.status IN :statuses AND " +
           "((l.startDate BETWEEN :startDate AND :endDate) OR " +
           "(l.endDate BETWEEN :startDate AND :endDate) OR " +
           "(l.startDate <= :startDate AND l.endDate >= :endDate))")
    boolean existsConflictingLock(
            @Param("roomId") Long roomId,
            @Param("statuses") List<RoomReservationLock.LockStatus> statuses,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Находит истекшие блокировки (HELD статус с expiredAt в прошлом).
     */
    @Query("SELECT l FROM RoomReservationLock l WHERE " +
           "l.status = 'HELD' AND " +
           "l.expiresAt < :currentTime")
    List<RoomReservationLock> findExpiredLocks(@Param("currentTime") LocalDateTime currentTime);

    /**
     * Находит блокировки, созданные после указанной даты.
     */
    List<RoomReservationLock> findByCreatedAtAfter(LocalDateTime since);

    /**
     * Находит блокировки по идентификатору корреляции (для трассировки саг).
     */
    List<RoomReservationLock> findByCorrelationId(String correlationId);

    /**
     * Находит блокировки для указанного периода дат.
     */
    @Query("SELECT l FROM RoomReservationLock l WHERE " +
           "((l.startDate BETWEEN :startDate AND :endDate) OR " +
           "(l.endDate BETWEEN :startDate AND :endDate))")
    List<RoomReservationLock> findLocksForPeriod(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Находит активные блокировки для комнаты после указанной даты.
     */
    @Query("SELECT l FROM RoomReservationLock l WHERE " +
           "l.roomId = :roomId AND " +
           "l.status = 'HELD' AND " +
           "l.endDate >= :date")
    List<RoomReservationLock> findActiveLocksByRoomAfterDate(
            @Param("roomId") Long roomId,
            @Param("date") LocalDate date);

    /**
     * Подтверждает блокировку (переводит в статус CONFIRMED).
     */
    @Query("UPDATE RoomReservationLock l SET l.status = 'CONFIRMED', l.updatedAt = CURRENT_TIMESTAMP WHERE l.requestId = :requestId")
    @Modifying
    int confirmLock(@Param("requestId") String requestId);

    /**
     * Освобождает блокировку (переводит в статус RELEASED).
     */
    @Query("UPDATE RoomReservationLock l SET l.status = 'RELEASED', l.updatedAt = CURRENT_TIMESTAMP WHERE l.requestId = :requestId")
    @Modifying
    int releaseLock(@Param("requestId") String requestId);

    /**
     * Помечает блокировки как истекшие (переводит в статус EXPIRED).
     */
    @Query("UPDATE RoomReservationLock l SET l.status = 'EXPIRED', l.updatedAt = CURRENT_TIMESTAMP WHERE l.status = 'HELD' AND l.expiresAt < :currentTime")
    @Modifying
    int expireLocks(@Param("currentTime") LocalDateTime currentTime);

    /**
     * Удаляет старые завершенные блокировки (RELEASED, EXPIRED).
     */
    @Query("DELETE FROM RoomReservationLock l WHERE l.status IN ('RELEASED', 'EXPIRED') AND l.updatedAt < :cutoffDate")
    @Modifying
    int deleteOldCompletedLocks(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Находит статистику блокировок по статусам.
     */
    @Query("SELECT l.status, COUNT(l) FROM RoomReservationLock l GROUP BY l.status")
    List<Object[]> findLockStatisticsByStatus();

    /**
     * Находит количество активных блокировок по комнатам.
     */
    @Query("SELECT l.roomId, COUNT(l) FROM RoomReservationLock l WHERE l.status = 'HELD' GROUP BY l.roomId")
    List<Object[]> findActiveLockCountByRoom();

    /**
     * Находит блокировки с истекающим сроком (в течение следующих N минут).
     */
    @Query("SELECT l FROM RoomReservationLock l WHERE l.status = 'HELD' AND l.expiresAt BETWEEN :now AND :threshold")
    List<RoomReservationLock> findLocksExpiringSoon(
            @Param("now") LocalDateTime now,
            @Param("threshold") LocalDateTime threshold);

    /**
     * Находит все блокировки для указанных комнат в указанный период.
     */
    @Query("SELECT l FROM RoomReservationLock l WHERE " +
           "l.roomId IN :roomIds AND " +
           "l.status IN :statuses AND " +
           "((l.startDate BETWEEN :startDate AND :endDate) OR " +
           "(l.endDate BETWEEN :startDate AND :endDate))")
    List<RoomReservationLock> findLocksForRoomsInPeriod(
            @Param("roomIds") List<Long> roomIds,
            @Param("statuses") List<RoomReservationLock.LockStatus> statuses,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /**
     * Находит последние блокировки для комнаты (отсортированные по дате создания).
     */
    @Query("SELECT l FROM RoomReservationLock l WHERE l.roomId = :roomId ORDER BY l.createdAt DESC")
    List<RoomReservationLock> findRecentLocksByRoom(@Param("roomId") Long roomId, Pageable pageable);
}
