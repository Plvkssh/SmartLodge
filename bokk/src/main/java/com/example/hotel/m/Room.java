package com.example.hotel.m;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Сущность номера в отеле.
 * Представляет комнату с характеристиками, доступностью и статистикой бронирований.
 * Связана с Hotel через отношение Many-to-One.
 */
@Entity
@Table(name = "rooms", uniqueConstraints = {
    @UniqueConstraint(name = "uk_room_hotel_number", columnNames = {"hotel_id", "number"})
})
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Room number is required")
    @Size(min = 1, max = 10, message = "Room number must be between 1 and 10 characters")
    @Column(nullable = false, length = 10)
    private String number;

    @NotNull(message = "Capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    @Column(nullable = false)
    private Integer capacity = 1;

    @Column(nullable = false)
    private Long timesBooked = 0L;

    @Column(nullable = false)
    private Boolean available = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomType type = RoomType.STANDARD;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerNight = BigDecimal.ZERO;

    @Column(nullable = false)
    private Boolean hasWiFi = true;

    @Column(nullable = false)
    private Boolean hasAirConditioning = true;

    @Column(nullable = false)
    private Boolean hasTV = true;

    @Column(nullable = false)
    private Boolean hasMiniBar = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hotel_id", nullable = false, foreignKey = @ForeignKey(name = "fk_room_hotel"))
    private Hotel hotel;

    /**
     * Типы номеров для классификации.
     */
    public enum RoomType {
        STANDARD,
        DELUXE,
        SUITE,
        EXECUTIVE,
        PRESIDENTIAL
    }

    /**
     * Статусы номера для управления доступностью.
     */
    public enum RoomStatus {
        AVAILABLE,
        OCCUPIED,
        MAINTENANCE,
        CLEANING
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomStatus status = RoomStatus.AVAILABLE;

    // Конструкторы
    public Room() {}

    public Room(String number, Integer capacity, Hotel hotel) {
        this.number = number;
        this.capacity = capacity;
        this.hotel = hotel;
    }

    public Room(String number, Integer capacity, RoomType type, BigDecimal pricePerNight, Hotel hotel) {
        this.number = number;
        this.capacity = capacity;
        this.type = type;
        this.pricePerNight = pricePerNight;
        this.hotel = hotel;
    }

    // Бизнес-методы

    /**
     * Увеличивает счетчик бронирований.
     */
    public void incrementTimesBooked() {
        this.timesBooked++;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Проверяет, доступен ли номер для бронирования.
     */
    public boolean isAvailableForBooking() {
        return available && 
               RoomStatus.AVAILABLE.equals(status) && 
               hotel != null && 
               hotel.isActive();
    }

    /**
     * Бронирует номер (переводит в статус занято).
     */
    public void book() {
        if (!isAvailableForBooking()) {
            throw new IllegalStateException("Room is not available for booking");
        }
        this.status = RoomStatus.OCCUPIED;
        incrementTimesBooked();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Освобождает номер (переводит в статус уборки).
     */
    public void release() {
        this.status = RoomStatus.CLEANING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Делает номер доступным после уборки.
     */
    public void makeAvailable() {
        this.status = RoomStatus.AVAILABLE;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Переводит номер в режим обслуживания.
     */
    public void setMaintenance() {
        this.status = RoomStatus.MAINTENANCE;
        this.available = false;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Активирует номер после обслуживания.
     */
    public void activate() {
        this.status = RoomStatus.AVAILABLE;
        this.available = true;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Возвращает базовую информацию о номере для API.
     */
    public RoomInfo toRoomInfo() {
        return new RoomInfo(
            this.id,
            this.number,
            this.capacity,
            this.timesBooked,
            this.type,
            this.pricePerNight,
            this.isAvailableForBooking()
        );
    }

    /**
     * DTO для передачи информации о номере.
     */
    public record RoomInfo(
        Long id,
        String number,
        Integer capacity,
        Long timesBooked,
        RoomType type,
        BigDecimal pricePerNight,
        Boolean available
    ) {}

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }

    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { 
        if (capacity != null && capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1");
        }
        this.capacity = capacity; 
    }

    public Long getTimesBooked() { return timesBooked; }
    public void setTimesBooked(Long timesBooked) { 
        if (timesBooked != null && timesBooked < 0) {
            throw new IllegalArgumentException("Times booked cannot be negative");
        }
        this.timesBooked = timesBooked; 
    }

    public Boolean getAvailable() { return available; }
    public void setAvailable(Boolean available) { this.available = available; }

    public RoomType getType() { return type; }
    public void setType(RoomType type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPricePerNight() { return pricePerNight; }
    public void setPricePerNight(BigDecimal pricePerNight) { 
        if (pricePerNight != null && pricePerNight.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        this.pricePerNight = pricePerNight; 
    }

    public Boolean getHasWiFi() { return hasWiFi; }
    public void setHasWiFi(Boolean hasWiFi) { this.hasWiFi = hasWiFi; }

    public Boolean getHasAirConditioning() { return hasAirConditioning; }
    public void setHasAirConditioning(Boolean hasAirConditioning) { this.hasAirConditioning = hasAirConditioning; }

    public Boolean getHasTV() { return hasTV; }
    public void setHasTV(Boolean hasTV) { this.hasTV = hasTV; }

    public Boolean getHasMiniBar() { return hasMiniBar; }
    public void setHasMiniBar(Boolean hasMiniBar) { this.hasMiniBar = hasMiniBar; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Hotel getHotel() { return hotel; }
    public void setHotel(Hotel hotel) { this.hotel = hotel; }

    public RoomStatus getStatus() { return status; }
    public void setStatus(RoomStatus status) { this.status = status; }

    // equals, hashCode, toString

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Room room = (Room) o;
        return Objects.equals(id, room.id) && 
               Objects.equals(number, room.number) && 
               Objects.equals(hotel != null ? hotel.getId() : null, room.hotel != null ? room.hotel.getId() : null);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, number, hotel != null ? hotel.getId() : null);
    }

    @Override
    public String toString() {
        return String.format(
            "Room{id=%d, number='%s', type=%s, capacity=%d, booked=%d, available=%s}",
            id, number, type, capacity, timesBooked, isAvailableForBooking()
        );
    }

    /**
     * Строитель для удобного создания номеров.
     */
    public static class Builder {
        private String number;
        private Integer capacity = 1;
        private RoomType type = RoomType.STANDARD;
        private BigDecimal pricePerNight = BigDecimal.ZERO;
        private Hotel hotel;
        private String description;
        private Boolean hasWiFi = true;
        private Boolean hasAirConditioning = true;
        private Boolean hasTV = true;
        private Boolean hasMiniBar = false;

        public Builder number(String number) {
            this.number = number;
            return this;
        }

        public Builder capacity(Integer capacity) {
            this.capacity = capacity;
            return this;
        }

        public Builder type(RoomType type) {
            this.type = type;
            return this;
        }

        public Builder pricePerNight(BigDecimal pricePerNight) {
            this.pricePerNight = pricePerNight;
            return this;
        }

        public Builder hotel(Hotel hotel) {
            this.hotel = hotel;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder hasWiFi(Boolean hasWiFi) {
            this.hasWiFi = hasWiFi;
            return this;
        }

        public Builder hasAirConditioning(Boolean hasAirConditioning) {
            this.hasAirConditioning = hasAirConditioning;
            return this;
        }

        public Builder hasTV(Boolean hasTV) {
            this.hasTV = hasTV;
            return this;
        }

        public Builder hasMiniBar(Boolean hasMiniBar) {
            this.hasMiniBar = hasMiniBar;
            return this;
        }

        public Room build() {
            Room room = new Room(number, capacity, type, pricePerNight, hotel);
            room.setDescription(description);
            room.setHasWiFi(hasWiFi);
            room.setHasAirConditioning(hasAirConditioning);
            room.setHasTV(hasTV);
            room.setHasMiniBar(hasMiniBar);
            return room;
        }
    }

    /**
     * Создает строитель для номера.
     */
    public static Builder builder() {
        return new Builder();
    }
}
