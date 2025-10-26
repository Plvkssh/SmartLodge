package com.example.hotel.m;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Сущность отеля.
 * Представляет отель с номерами, местоположением и контактной информацией.
 * Связана с Room через отношение One-to-Many.
 */
@Entity
@Table(name = "hotels", uniqueConstraints = {
    @UniqueConstraint(name = "uk_hotel_name_city", columnNames = {"name", "city"})
})
public class Hotel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Hotel name is required")
    @Size(min = 2, max = 100, message = "Hotel name must be between 2 and 100 characters")
    @Column(nullable = false, length = 100)
    private String name;

    @NotBlank(message = "City is required")
    @Size(min = 2, max = 50, message = "City must be between 2 and 50 characters")
    @Column(nullable = false, length = 50)
    private String city;

    @NotBlank(message = "Address is required")
    @Size(min = 5, max = 200, message = "Address must be between 5 and 200 characters")
    @Column(nullable = false, length = 200)
    private String address;

    @Size(max = 20)
    @Column(length = 20)
    private String phoneNumber;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Integer starRating = 3; // 1-5 stars

    @Column(nullable = false)
    private Boolean active = true;

    @OneToMany(mappedBy = "hotel", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Room> rooms = new ArrayList<>();

    /**
     * Статусы отеля для управления доступностью.
     */
    public enum HotelStatus {
        ACTIVE,
        INACTIVE,
        MAINTENANCE
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HotelStatus status = HotelStatus.ACTIVE;

    // Конструкторы
    public Hotel() {}

    public Hotel(String name, String city, String address) {
        this.name = name;
        this.city = city;
        this.address = address;
    }

    public Hotel(String name, String city, String address, String phoneNumber, 
                String description, Integer starRating) {
        this.name = name;
        this.city = city;
        this.address = address;
        this.phoneNumber = phoneNumber;
        this.description = description;
        this.starRating = starRating;
    }

    // Бизнес-методы

    /**
     * Добавляет комнату к отелю.
     */
    public void addRoom(Room room) {
        rooms.add(room);
        room.setHotel(this);
    }

    /**
     * Удаляет комнату из отеля.
     */
    public void removeRoom(Room room) {
        rooms.remove(room);
        room.setHotel(null);
    }

    /**
     * Проверяет, активен ли отель.
     */
    public boolean isActive() {
        return HotelStatus.ACTIVE.equals(this.status) && Boolean.TRUE.equals(this.active);
    }

    /**
     * Активирует отель.
     */
    public void activate() {
        this.status = HotelStatus.ACTIVE;
        this.active = true;
    }

    /**
     * Деактивирует отель.
     */
    public void deactivate() {
        this.status = HotelStatus.INACTIVE;
        this.active = false;
    }

    /**
     * Переводит отель в режим обслуживания.
     */
    public void setMaintenance() {
        this.status = HotelStatus.MAINTENANCE;
        this.active = false;
    }

    /**
     * Возвращает количество доступных комнат в отеле.
     */
    public long getAvailableRoomCount() {
        return rooms.stream()
                .filter(Room::isAvailable)
                .count();
    }

    /**
     * Возвращает общее количество комнат в отеле.
     */
    public int getTotalRoomCount() {
        return rooms.size();
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getStarRating() { return starRating; }
    public void setStarRating(Integer starRating) { 
        if (starRating != null && (starRating < 1 || starRating > 5)) {
            throw new IllegalArgumentException("Star rating must be between 1 and 5");
        }
        this.starRating = starRating; 
    }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public HotelStatus getStatus() { return status; }
    public void setStatus(HotelStatus status) { this.status = status; }

    public List<Room> getRooms() { return rooms; }
    public void setRooms(List<Room> rooms) { this.rooms = rooms; }

    // equals, hashCode, toString

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Hotel hotel = (Hotel) o;
        return Objects.equals(id, hotel.id) && 
               Objects.equals(name, hotel.name) && 
               Objects.equals(city, hotel.city);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, city);
    }

    @Override
    public String toString() {
        return String.format(
            "Hotel{id=%d, name='%s', city='%s', rating=%d, rooms=%d}",
            id, name, city, starRating, rooms.size()
        );
    }

    /**
     * Строитель для удобного создания отелей.
     */
    public static class Builder {
        private String name;
        private String city;
        private String address;
        private String phoneNumber;
        private String description;
        private Integer starRating = 3;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder city(String city) {
            this.city = city;
            return this;
        }

        public Builder address(String address) {
            this.address = address;
            return this;
        }

        public Builder phoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder starRating(Integer starRating) {
            this.starRating = starRating;
            return this;
        }

        public Hotel build() {
            return new Hotel(name, city, address, phoneNumber, description, starRating);
        }
    }

    /**
     * Создает строитель для отеля.
     */
    public static Builder builder() {
        return new Builder();
    }
}
