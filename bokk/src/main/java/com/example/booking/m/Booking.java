package com.example.booking.m;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Сущность бронирования отеля.
 * Поддерживает идемпотентность через requestId и трассировку через correlationId.
 */
@Entity
@Table(name = "bookings", uniqueConstraints = {
    @UniqueConstraint(name = "uk_booking_request", columnNames = {"requestId"})
})
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String requestId; // Ключ идемпотентности для предотвращения дублирования

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    private String correlationId; // Идентификатор для трассировки запросов между сервисами

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Статусы жизненного цикла бронирования.
     * PENDING -> CONFIRMED (успех) или CANCELLED (откат саги)
     */
    public enum BookingStatus {
        PENDING,    // Ожидание подтверждения от Hotel Service
        CONFIRMED,  // Бронирование подтверждено
        CANCELLED   // Бронирование отменено
    }

    // Конструкторы
    public Booking() {}

    public Booking(String requestId, Long userId, Long roomId, 
                  LocalDate startDate, LocalDate endDate, String correlationId) {
        this.requestId = requestId;
        this.userId = userId;
        this.roomId = roomId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = BookingStatus.PENDING;
        this.correlationId = correlationId;
        this.createdAt = OffsetDateTime.now();
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * Проверяет, активно ли бронирование (подтверждено)
     */
    public boolean isConfirmed() {
        return BookingStatus.CONFIRMED.equals(this.status);
    }

    /**
     * Проверяет, находится ли бронирование в ожидании
     */
    public boolean isPending() {
        return BookingStatus.PENDING.equals(this.status);
    }

    /**
     * Подтверждает бронирование
     */
    public void confirm() {
        this.status = BookingStatus.CONFIRMED;
    }

    /**
     * Отменяет бронирование
     */
    public void cancel() {
        this.status = BookingStatus.CANCELLED;
    }

    @Override
    public String toString() {
        return String.format(
            "Booking{id=%d, userId=%d, roomId=%d, status=%s, period=%s to %s}",
            id, userId, roomId, status, startDate, endDate
        );
    }
}
