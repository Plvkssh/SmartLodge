package com.example.hotel.repositor;

import com.example.hotel.m.Room;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы с номерами отелей.
 * Предоставляет методы для поиска, фильтрации комнат и управления их доступностью.
 */
@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

    /**
     * Находит комнату по номеру и отелю.
     */
    Optional<Room> findByNumberAndHotelId(String number, Long hotelId);

    /**
     * Находит все комнаты указанного отеля.
     */
    List<Room> findByHotelId(Long hotelId);

    /**
     * Находит все комнаты указанного отеля с пагинацией.
     */
    Page<Room> findByHotelId(Long hotelId, Pageable pageable);

    /**
     * Находит доступные комнаты в указанном отеле.
     */
    List<Room> findByHotelIdAndAvailableTrue(Long hotelId);

    /**
     * Находит доступные комнаты в указанном отеле с пагинацией.
     */
    Page<Room> findByHotelIdAndAvailableTrue(Long hotelId, Pageable pageable);

    /**
     * Находит комнаты по типу.
     */
    List<Room> findByType(Room.RoomType type);

    /**
     * Находит комнаты по типу с пагинацией.
     */
    Page<Room> findByType(Room.RoomType type, Pageable pageable);

    /**
     * Находит комнаты по типу в указанном отеле.
     */
    List<Room> findByHotelIdAndType(Long hotelId, Room.RoomType type);

    /**
     * Находит комнаты по вместимости (минимум).
     */
    List<Room> findByCapacityGreaterThanEqual(Integer minCapacity);

    /**
     * Находит комнаты по вместимости в диапазоне.
     */
    List<Room> findByCapacityBetween(Integer minCapacity, Integer maxCapacity);

    /**
     * Находит комнаты по цене за ночь (максимум).
     */
    List<Room> findByPricePerNightLessThanEqual(BigDecimal maxPrice);

    /**
     * Находит комнаты по цене за ночь в диапазоне.
     */
    List<Room> findByPricePerNightBetween(BigDecimal minPrice, BigDecimal maxPrice);

    /**
     * Находит комнаты с указанными удобствами.
     */
    List<Room> findByHasWiFiTrueAndHasAirConditioningTrueAndHasTVTrue();

    /**
     * Находит комнаты с мини-баром.
     */
    List<Room> findByHasMiniBarTrue();

    /**
     * Находит комнаты по статусу.
     */
    List<Room> findByStatus(Room.RoomStatus status);

    /**
     * Находит комнаты по статусу с пагинацией.
     */
    Page<Room> findByStatus(Room.RoomStatus status, Pageable pageable);

    /**
     * Проверяет существование комнаты с указанным номером в отеле.
     */
    boolean existsByNumberAndHotelId(String number, Long hotelId);

    /**
     * Находит комнаты, отсортированные по цене (по возрастанию).
     */
    @Query("SELECT r FROM Room r ORDER BY r.pricePerNight ASC")
    List<Room> findAllOrderByPriceAsc();

    /**
     * Находит комнаты, отсортированные по цене с пагинацией.
     */
    @Query("SELECT r FROM Room r ORDER BY r.pricePerNight ASC")
    Page<Room> findAllOrderByPriceAsc(Pageable pageable);

    /**
     * Находит комнаты, отсортированные по количеству бронирований (по возрастанию).
     * Используется для алгоритма равномерного распределения.
     */
    @Query("SELECT r FROM Room r WHERE r.available = true ORDER BY r.timesBooked ASC, r.id ASC")
    List<Room> findAvailableRoomsOrderByTimesBookedAsc();

    /**
     * Находит доступные комнаты в указанном отеле, отсортированные по timesBooked.
     */
    @Query("SELECT r FROM Room r WHERE r.hotel.id = :hotelId AND r.available = true ORDER BY r.timesBooked ASC, r.id ASC")
    List<Room> findAvailableRoomsByHotelOrderByTimesBookedAsc(@Param("hotelId") Long hotelId);

    /**
     * Находит доступные комнаты в указанном городе, отсортированные по timesBooked.
     */
    @Query("SELECT r FROM Room r WHERE r.hotel.city = :city AND r.available = true ORDER BY r.timesBooked ASC, r.id ASC")
    List<Room> findAvailableRoomsByCityOrderByTimesBookedAsc(@Param("city") String city);

    /**
     * Находит комнаты с минимальным количеством бронирований для равномерного распределения.
     */
    @Query("SELECT r FROM Room r WHERE r.available = true AND r.timesBooked = (SELECT MIN(r2.timesBooked) FROM Room r2 WHERE r2.available = true)")
    List<Room> findLeastBookedAvailableRooms();

    /**
     * Находит доступные комнаты с фильтрами по типу и вместимости.
     */
    @Query("SELECT r FROM Room r WHERE r.available = true AND r.type = :type AND r.capacity >= :minCapacity ORDER BY r.timesBooked ASC")
    List<Room> findAvailableRoomsByTypeAndCapacity(@Param("type") Room.RoomType type, @Param("minCapacity") Integer minCapacity);

    /**
     * Находит комнаты, которые не забронированы на указанные даты.
     * Используется для проверки доступности при создании бронирования.
     */
    @Query("SELECT r FROM Room r WHERE r.id NOT IN (" +
           "SELECT l.roomId FROM RoomReservationLock l " +
           "WHERE l.status = 'HELD' AND " +
           "((l.startDate BETWEEN :startDate AND :endDate) OR " +
           "(l.endDate BETWEEN :startDate AND :endDate) OR " +
           "(l.startDate <= :startDate AND l.endDate >= :endDate))) " +
           "AND r.available = true")
    List<Room> findAvailableRoomsForDates(@Param("startDate") LocalDate startDate, 
                                        @Param("endDate") LocalDate endDate);

    /**
     * Находит доступные комнаты в указанном отеле на указанные даты.
     */
    @Query("SELECT r FROM Room r WHERE r.hotel.id = :hotelId AND r.id NOT IN (" +
           "SELECT l.roomId FROM RoomReservationLock l " +
           "WHERE l.status = 'HELD' AND " +
           "((l.startDate BETWEEN :startDate AND :endDate) OR " +
           "(l.endDate BETWEEN :startDate AND :endDate) OR " +
           "(l.startDate <= :startDate AND l.endDate >= :endDate))) " +
           "AND r.available = true")
    List<Room> findAvailableRoomsInHotelForDates(@Param("hotelId") Long hotelId,
                                               @Param("startDate") LocalDate startDate,
                                               @Param("endDate") LocalDate endDate);

    /**
     * Находит топ-N самых популярных комнат (по количеству бронирований).
     */
    @Query("SELECT r FROM Room r ORDER BY r.timesBooked DESC")
    List<Room> findTopNPopularRooms(Pageable pageable);

    /**
     * Находит статистику по типам комнат в отеле.
     */
    @Query("SELECT r.type, COUNT(r), AVG(r.pricePerNight) FROM Room r WHERE r.hotel.id = :hotelId GROUP BY r.type")
    List<Object[]> findRoomTypeStatisticsByHotel(@Param("hotelId") Long hotelId);

    /**
     * Находит среднюю цену комнат по типам.
     */
    @Query("SELECT r.type, AVG(r.pricePerNight) FROM Room r GROUP BY r.type ORDER BY AVG(r.pricePerNight) DESC")
    List<Object[]> findAveragePriceByRoomType();

    /**
     * Находит общее количество доступных комнат.
     */
    @Query("SELECT COUNT(r) FROM Room r WHERE r.available = true")
    long countAvailableRooms();

    /**
     * Находит количество доступных комнат в указанном отеле.
     */
    @Query("SELECT COUNT(r) FROM Room r WHERE r.hotel.id = :hotelId AND r.available = true")
    long countAvailableRoomsByHotel(@Param("hotelId") Long hotelId);

    /**
     * Находит комнаты, требующие обслуживания (статус MAINTENANCE).
     */
    @Query("SELECT r FROM Room r WHERE r.status = 'MAINTENANCE'")
    List<Room> findRoomsUnderMaintenance();

    /**
     * Находит комнаты, которые находятся в процессе уборки.
     */
    @Query("SELECT r FROM Room r WHERE r.status = 'CLEANING'")
    List<Room> findRoomsUnderCleaning();

    /**
     * Обновляет счетчик бронирований для комнаты.
     */
    @Query("UPDATE Room r SET r.timesBooked = r.timesBooked + 1, r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :roomId")
    @Modifying
    void incrementTimesBooked(@Param("roomId") Long roomId);

    /**
     * Обновляет статус комнаты.
     */
    @Query("UPDATE Room r SET r.status = :status, r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :roomId")
    @Modifying
    void updateRoomStatus(@Param("roomId") Long roomId, @Param("status") Room.RoomStatus status);

    /**
     * Находит комнаты, которые были обновлены после указанной даты.
     */
    List<Room> findByUpdatedAtAfter(LocalDateTime since);

    /**
     * Находит комнаты по списку идентификаторов.
     */
    List<Room> findByIdIn(List<Long> ids);
}
