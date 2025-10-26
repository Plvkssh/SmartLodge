package com.example.hotel.m;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Сущность блокировки номера для распределенной саги бронирования.
 * Обеспечивает временную блокировку номера на период бронирования
 * для предотвращения двойного бронирования и поддержания согласованности данных.
 */
@Entity
@Table(name = "room_reservation_locks", uniqueConstraints = {
    @UniqueConstraint(name = "uk_lock_request", columnNames = {"requestId"})
})
public class RoomReservationLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Request ID is required")
    @Column(nullable = false, length = 36) // UUID length
    private String requestId;

    @NotNull(message = "Room ID is required")
    @Column(nullable = false)
    private Long roomId;

    @NotNull(message = "Start date is required")
    @Column(nullable = false)
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LockStatus status = LockStatus.HELD;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    private LocalDateTime expiresAt; // Время истечения блокировки

    private String correlationId; // Для трассировки распределенной транзакции

    /**
     * Статусы блокировки номера в процессе саги бронирования.
     * HELD → CONFIRMED (успех) или RELEASED (откат)
     */
    public enum LockStatus {
        HELD,       // Номер временно заблокирован
        CONFIRMED,  // Бронирование подтверждено
        RELEASED,   // Блокировка снята (компенсация)
        EXPIRED     // Блокировка истекла по времени
    }

    // Конструкторы
    public RoomReservationLock() {}

    public RoomReservationLock(String requestId, Long roomId, LocalDate startDate, LocalDate endDate) {
        this.requestId = requestId;
        this.roomId = roomId;
        this.startDate = startDate;
        this.endDate = endDate;
        validateDates();
    }

    public RoomReservationLock(String requestId, Long roomId, LocalDate startDate, 
                             LocalDate endDate, String correlationId) {
        this.requestId = requestId;
        this.roomId = roomId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.correlationId = correlationId;
        validateDates();
    }

    // Бизнес-методы

    /**
     * Проверяет валидность дат блокировки.
     */
    private void validateDates() {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date cannot be after end date");
        }
        if (startDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Start date cannot be in the past");
        }
    }

    /**
     * Подтверждает блокировку (переводит в статус CONFIRMED).
     */
    public void confirm() {
        if (!LockStatus.HELD.equals(this.status)) {
            throw new IllegalStateException(
                String.format("Cannot confirm lock in status: %s", this.status)
            );
        }
        this.status = LockStatus.CONFIRMED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Освобождает блокировку (переводит в статус RELEASED).
     */
    public void release() {
        if (LockStatus.RELEASED.equals(this.status) || LockStatus.EXPIRED.equals(this.status)) {
            throw new IllegalStateException(
                String.format("Lock already in final status: %s", this.status)
            );
        }
        this.status = LockStatus.RELEASED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Помечает блокировку как истекшую.
     */
    public void expire() {
        this.status = LockStatus.EXPIRED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Проверяет, активна ли блокировка (HELD статус).
     */
    public boolean isActive() {
        return LockStatus.HELD.equals(this.status);
    }

    /**
     * Проверяет, подтверждена ли блокировка.
     */
    public boolean isConfirmed() {
        return LockStatus.CONFIRMED.equals(this.status);
    }

    /**
     * Проверяет, освобождена ли блокировка.
     */
    public boolean isReleased() {
        return LockStatus.RELEASED.equals(this.status);
    }

    /**
     * Проверяет, истекла ли блокировка.
     */
    public boolean isExpired() {
        return LockStatus.EXPIRED.equals(this.status) || 
               (expiresAt != null && LocalDateTime.now().isAfter(expiresAt));
    }

    /**
     * Устанавливает время истечения блокировки (например, 15 минут).
     */
    public void setExpirationMinutes(int minutes) {
        this.expiresAt = LocalDateTime.now().plusMinutes(minutes);
    }

    /**
     * Проверяет конфликт дат с другой блокировкой.
     */
    public boolean hasDateConflict(RoomReservationLock other) {
        if (!this.roomId.equals(other.roomId)) {
            return false;
        }
        
        return (this.startDate.isBefore(other.endDate) || this.startDate.equals(other.endDate)) &&
               (this.endDate.isAfter(other.startDate) || this.endDate.equals(other.startDate));
    }

    /**
     * Проверяет, относится ли блокировка к указанному запросу.
     */
    public boolean isForRequest(String requestId) {
        return this.requestId.equals(requestId);
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { 
        this.startDate = startDate; 
        validateDates();
    }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { 
        this.endDate = endDate; 
        validateDates();
    }

    public LockStatus getStatus() { return status; }
    public void setStatus(LockStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    // equals, hashCode, toString

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoomReservationLock that = (RoomReservationLock) o;
        return Objects.equals(id, that.id) && 
               Objects.equals(requestId, that.requestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, requestId);
    }

    @Override
    public String toString() {
        return String.format(
            "RoomReservationLock{id=%d, roomId=%d, status=%s, dates=%s to %s}",
            id, roomId, status, startDate, endDate
        );
    }

    /**
     * Строитель для удобного создания блокировок.
     */
    public static class Builder {
        private String requestId;
        private Long roomId;
        private LocalDate startDate;
        private LocalDate endDate;
        private String correlationId;
        private Integer expirationMinutes;

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder roomId(Long roomId) {
            this.roomId = roomId;
            return this;
        }

        public Builder startDate(LocalDate startDate) {
            this.startDate = startDate;
            return this;
        }

        public Builder endDate(LocalDate endDate) {
            this.endDate = endDate;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder expirationMinutes(Integer expirationMinutes) {
            this.expirationMinutes = expirationMinutes;
            return this;
        }

        public RoomReservationLock build() {
            RoomReservationLock lock = new RoomReservationLock(requestId, roomId, startDate, endDate, correlationId);
            if (expirationMinutes != null) {
                lock.setExpirationMinutes(expirationMinutes);
            }
            return lock;
        }
    }

    /**
     * Создает строитель для блокировки.
     */
    public static Builder builder() {
        return new Builder();
    }
}
