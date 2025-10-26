package com.example.hotel.service;

import com.example.hotel.m.Hotel;
import com.example.hotel.m.Room;
import com.example.hotel.m.RoomReservationLock;
import com.example.hotel.repositor.HotelRepository;
import com.example.hotel.repositor.RoomRepository;
import com.example.hotel.repositor.RoomReservationLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Сервис для управления отелями, номерами и блокировками бронирований.
 * Обеспечивает CRUD операции, управление доступностью номеров и интеграцию с Booking Service через саги.
 */
@Service
@Transactional
public class HotelService {
    
    private static final Logger logger = LoggerFactory.getLogger(HotelService.class);
    
    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final RoomReservationLockRepository lockRepository;

    public HotelService(HotelRepository hotelRepository, 
                       RoomRepository roomRepository, 
                       RoomReservationLockRepository lockRepository) {
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
        this.lockRepository = lockRepository;
        
        logger.info("HotelService initialized with repositories");
    }

    // ==================== HOTEL CRUD OPERATIONS ====================

    /**
     * Получает список всех отелей с пагинацией.
     */
    public Page<Hotel> listHotels(Pageable pageable) {
        logger.debug("Fetching hotels page: {}", pageable.getPageNumber());
        return hotelRepository.findAll(pageable);
    }

    /**
     * Получает отель по идентификатору.
     */
    public Optional<Hotel> getHotel(Long id) {
        logger.debug("Fetching hotel by id: {}", id);
        return hotelRepository.findById(id);
    }

    /**
     * Сохраняет отель (создание или обновление).
     */
    public Hotel saveHotel(Hotel hotel) {
        logger.info("Saving hotel: {}", hotel.getName());
        
        if (hotel.getId() == null) {
            // Проверка уникальности при создании
            if (hotelRepository.existsByNameAndCity(hotel.getName(), hotel.getCity())) {
                throw new IllegalArgumentException(
                    String.format("Hotel with name '%s' already exists in city '%s'", 
                                hotel.getName(), hotel.getCity())
                );
            }
        }
        
        Hotel savedHotel = hotelRepository.save(hotel);
        logger.info("Hotel saved successfully: {} (ID: {})", savedHotel.getName(), savedHotel.getId());
        return savedHotel;
    }

    /**
     * Удаляет отель по идентификатору.
     */
    public void deleteHotel(Long id) {
        logger.info("Deleting hotel with id: {}", id);
        
        Hotel hotel = hotelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Hotel not found with id: " + id));
        
        // Проверяем, есть ли активные бронирования
        if (hasActiveBookings(hotel)) {
            throw new IllegalStateException("Cannot delete hotel with active bookings");
        }
        
        hotelRepository.deleteById(id);
        logger.info("Hotel deleted successfully: {} (ID: {})", hotel.getName(), id);
    }

    /**
     * Ищет отели по различным критериям.
     */
    public Page<Hotel> searchHotels(String city, String name, Integer minRating, Pageable pageable) {
        logger.debug("Searching hotels - city: {}, name: {}, minRating: {}", city, name, minRating);
        
        if (city != null && name != null) {
            return hotelRepository.findByCityAndNameContainingIgnoreCase(city, name, pageable);
        } else if (city != null) {
            return hotelRepository.findByCity(city, pageable);
        } else if (name != null) {
            return hotelRepository.findByNameContainingIgnoreCase(name, pageable);
        } else if (minRating != null) {
            return hotelRepository.findByStarRatingGreaterThanEqual(minRating, pageable);
        } else {
            return hotelRepository.findAll(pageable);
        }
    }

    // ==================== ROOM CRUD OPERATIONS ====================

    /**
     * Получает список всех номеров с пагинацией.
     */
    public Page<Room> listRooms(Pageable pageable) {
        logger.debug("Fetching rooms page: {}", pageable.getPageNumber());
        return roomRepository.findAll(pageable);
    }

    /**
     * Получает номер по идентификатору.
     */
    public Optional<Room> getRoom(Long id) {
        logger.debug("Fetching room by id: {}", id);
        return roomRepository.findById(id);
    }

    /**
     * Сохраняет номер (создание или обновление).
     */
    public Room saveRoom(Room room) {
        logger.info("Saving room: {} in hotel: {}", 
                   room.getNumber(), room.getHotel() != null ? room.getHotel().getName() : "unknown");
        
        if (room.getId() == null) {
            // Проверка уникальности номера в отеле
            if (roomRepository.existsByNumberAndHotelId(room.getNumber(), room.getHotel().getId())) {
                throw new IllegalArgumentException(
                    String.format("Room with number '%s' already exists in hotel", room.getNumber())
                );
            }
        }
        
        Room savedRoom = roomRepository.save(room);
        logger.info("Room saved successfully: {} (ID: {})", savedRoom.getNumber(), savedRoom.getId());
        return savedRoom;
    }

    /**
     * Удаляет номер по идентификатору.
     */
    public void deleteRoom(Long id) {
        logger.info("Deleting room with id: {}", id);
        
        Room room = roomRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Room not found with id: " + id));
        
        // Проверяем активные блокировки
        if (hasActiveLocks(room)) {
            throw new IllegalStateException("Cannot delete room with active reservations");
        }
        
        roomRepository.deleteById(id);
        logger.info("Room deleted successfully: {} (ID: {})", room.getNumber(), id);
    }

    /**
     * Находит доступные номера для бронирования с равномерным распределением.
     */
    public List<Room> findAvailableRooms(LocalDate startDate, LocalDate endDate, String city, 
                                       Room.RoomType type, Integer minCapacity) {
        logger.debug("Finding available rooms - dates: {} to {}, city: {}, type: {}, capacity: {}", 
                   startDate, endDate, city, type, minCapacity);
        
        validateBookingDates(startDate, endDate);
        
        if (city != null) {
            return roomRepository.findAvailableRoomsByCityOrderByTimesBookedAsc(city);
        }
        
        return roomRepository.findAvailableRoomsOrderByTimesBookedAsc();
    }

    // ==================== RESERVATION LOCK OPERATIONS ====================

    /**
     * Временно блокирует номер для бронирования (шаг 1 саги).
     */
    @Transactional
    public RoomReservationLock holdRoom(String requestId, Long roomId, LocalDate startDate, LocalDate endDate) {
        logger.info("Attempting to hold room - requestId: {}, roomId: {}, dates: {} to {}", 
                   requestId, roomId, startDate, endDate);
        
        validateBookingParameters(requestId, roomId, startDate, endDate);
        
        // Проверка идемпотентности
        Optional<RoomReservationLock> existingLock = lockRepository.findByRequestId(requestId);
        if (existingLock.isPresent()) {
            logger.info("Idempotency: found existing lock for requestId: {}", requestId);
            return existingLock.get();
        }
        
        // Проверка доступности номера
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomId));
        
        if (!room.isAvailableForBooking()) {
            throw new IllegalStateException("Room is not available for booking: " + roomId);
        }
        
        // Проверка конфликтующих блокировок
        boolean hasConflict = lockRepository.existsConflictingLock(
            roomId, 
            List.of(RoomReservationLock.LockStatus.HELD, RoomReservationLock.LockStatus.CONFIRMED), 
            startDate, 
            endDate
        );
        
        if (hasConflict) {
            logger.warn("Room conflict detected - roomId: {}, dates: {} to {}", roomId, startDate, endDate);
            throw new IllegalStateException("Room is not available for the selected dates");
        }
        
        // Создание блокировки
        RoomReservationLock lock = RoomReservationLock.builder()
                .requestId(requestId)
                .roomId(roomId)
                .startDate(startDate)
                .endDate(endDate)
                .expirationMinutes(15) // 15 минут на подтверждение
                .build();
        
        RoomReservationLock savedLock = lockRepository.save(lock);
        logger.info("Room hold successful - lockId: {}, roomId: {}", savedLock.getId(), roomId);
        
        return savedLock;
    }

    /**
     * Подтверждает блокировку номера (шаг 2 саги).
     */
    @Transactional
    public RoomReservationLock confirmHold(String requestId) {
        logger.info("Confirming room hold - requestId: {}", requestId);
        
        RoomReservationLock lock = lockRepository.findByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException("Hold not found for requestId: " + requestId));
        
        // Идемпотентность - если уже подтверждено, возвращаем существующее
        if (lock.isConfirmed()) {
            logger.info("Idempotency: lock already confirmed for requestId: {}", requestId);
            return lock;
        }
        
        // Проверка валидности блокировки
        if (lock.isReleased()) {
            throw new IllegalStateException("Hold already released for requestId: " + requestId);
        }
        
        if (lock.isExpired()) {
            throw new IllegalStateException("Hold expired for requestId: " + requestId);
        }
        
        // Подтверждение блокировки
        lock.confirm();
        RoomReservationLock confirmedLock = lockRepository.save(lock);
        
        // Обновление статистики бронирований
        roomRepository.incrementTimesBooked(lock.getRoomId());
        
        logger.info("Room hold confirmed successfully - lockId: {}, roomId: {}", 
                   confirmedLock.getId(), lock.getRoomId());
        
        return confirmedLock;
    }

    /**
     * Освобождает блокировку номера (компенсирующее действие).
     */
    @Transactional
    public RoomReservationLock releaseHold(String requestId) {
        logger.info("Releasing room hold - requestId: {}", requestId);
        
        RoomReservationLock lock = lockRepository.findByRequestId(requestId)
                .orElseThrow(() -> new IllegalStateException("Hold not found for requestId: " + requestId));
        
        // Идемпотентность - если уже освобождено, возвращаем существующее
        if (lock.isReleased()) {
            logger.info("Idempotency: lock already released for requestId: {}", requestId);
            return lock;
        }
        
        // Не освобождаем подтвержденные блокировки
        if (lock.isConfirmed()) {
            logger.info("Lock already confirmed - skipping release for requestId: {}", requestId);
            return lock;
        }
        
        // Освобождение блокировки
        lock.release();
        RoomReservationLock releasedLock = lockRepository.save(lock);
        
        logger.info("Room hold released successfully - lockId: {}, roomId: {}", 
                   releasedLock.getId(), lock.getRoomId());
        
        return releasedLock;
    }

    // ==================== ANALYTICS AND STATISTICS ====================

    /**
     * Получает статистику по отелям.
     */
    public HotelStatistics getHotelStatistics() {
        logger.debug("Fetching hotel statistics");
        
        Long totalRooms = roomRepository.findTotalRoomCount();
        Long availableRooms = roomRepository.countAvailableRooms();
        List<Object[]> cityStats = hotelRepository.findHotelCountByCity();
        
        return new HotelStatistics(totalRooms, availableRooms, cityStats);
    }

    /**
     * Получает статистику по конкретному отелю.
     */
    public HotelDetailedStatistics getHotelDetailedStatistics(Long hotelId) {
        logger.debug("Fetching detailed statistics for hotel: {}", hotelId);
        
        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new IllegalArgumentException("Hotel not found: " + hotelId));
        
        Long availableRooms = roomRepository.countAvailableRoomsByHotel(hotelId);
        List<Object[]> roomTypeStats = roomRepository.findRoomTypeStatisticsByHotel(hotelId);
        
        return new HotelDetailedStatistics(hotel, availableRooms, roomTypeStats);
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private void validateBookingParameters(String requestId, Long roomId, LocalDate startDate, LocalDate endDate) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("Request ID is required");
        }
        if (roomId == null) {
            throw new IllegalArgumentException("Room ID is required");
        }
        validateBookingDates(startDate, endDate);
    }

    private void validateBookingDates(LocalDate startDate, LocalDate endDate) {
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

    private boolean hasActiveBookings(Hotel hotel) {
        // Проверяем активные блокировки для всех номеров отеля
        return hotel.getRooms().stream()
                .anyMatch(this::hasActiveLocks);
    }

    private boolean hasActiveLocks(Room room) {
        List<RoomReservationLock> activeLocks = lockRepository.findByRoomIdAndStatus(
            room.getId(), RoomReservationLock.LockStatus.HELD);
        return !activeLocks.isEmpty();
    }

    // ==================== DTO CLASSES FOR ANALYTICS ====================

    public record HotelStatistics(
        Long totalRooms,
        Long availableRooms,
        List<Object[]> cityStats
    ) {}

    public record HotelDetailedStatistics(
        Hotel hotel,
        Long availableRooms,
        List<Object[]> roomTypeStats
    ) {}
}
