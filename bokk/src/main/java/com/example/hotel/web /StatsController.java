package com.example.hotel.web;

import com.example.hotel.m.Hotel;
import com.example.hotel.m.Room;
import com.example.hotel.repositor.HotelRepository;
import com.example.hotel.repositor.RoomRepository;
import com.example.hotel.repositor.RoomReservationLockRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Контроллер для предоставления аналитики и статистики по отелям и номерам.
 * Предоставляет API для бизнес-аналитики, мониторинга и отчетности.
 * Требует прав администратора для доступа к чувствительной статистике.
 */
@RestController
@RequestMapping("/api/stats")
@SecurityRequirement(name = "bearer-jwt")
@Tag(name = "Statistics", description = "API для аналитики и статистики по отелям и бронированиям")
public class StatsController {

    private static final Logger logger = LoggerFactory.getLogger(StatsController.class);
    
    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;
    private final RoomReservationLockRepository lockRepository;

    public StatsController(RoomRepository roomRepository, 
                          HotelRepository hotelRepository,
                          RoomReservationLockRepository lockRepository) {
        this.roomRepository = roomRepository;
        this.hotelRepository = hotelRepository;
        this.lockRepository = lockRepository;
    }

    /**
     * Получает самые популярные номера по количеству бронирований.
     */
    @GetMapping("/rooms/popular")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Популярные номера", 
               description = "Возвращает список номеров, отсортированный по количеству бронирований (по убыванию)")
    public ResponseEntity<PopularRoomsResponse> getPopularRooms(
            @Parameter(description = "Количество возвращаемых номеров") 
            @RequestParam(defaultValue = "10") int limit) {
        
        logger.debug("Fetching top {} popular rooms", limit);
        
        Pageable pageable = PageRequest.of(0, limit);
        List<Room> popularRooms = roomRepository.findTopNPopularRooms(pageable);
        
        PopularRoomsResponse response = new PopularRoomsResponse(
            popularRooms.stream()
                .map(PopularRoomStats::fromRoom)
                .toList(),
            limit
        );
        
        logger.debug("Found {} popular rooms", popularRooms.size());
        return ResponseEntity.ok(response);
    }

    /**
     * Получает статистику занятости номеров.
     */
    @GetMapping("/occupancy")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Статистика занятости", 
               description = "Возвращает статистику занятости номеров по отелям и типам номеров")
    public ResponseEntity<OccupancyStatsResponse> getOccupancyStatistics() {
        
        logger.debug("Fetching occupancy statistics");
        
        long totalRooms = roomRepository.count();
        long availableRooms = roomRepository.countAvailableRooms();
        long occupiedRooms = totalRooms - availableRooms;
        
        double occupancyRate = totalRooms > 0 ? 
            (double) occupiedRooms / totalRooms * 100 : 0.0;
        
        List<Object[]> roomTypeStats = roomRepository.findAveragePriceByRoomType();
        List<Object[]> hotelStats = hotelRepository.findHotelCountByCity();
        
        OccupancyStatsResponse response = new OccupancyStatsResponse(
            totalRooms,
            availableRooms,
            occupiedRooms,
            BigDecimal.valueOf(occupancyRate).setScale(2, RoundingMode.HALF_UP),
            roomTypeStats,
            hotelStats
        );
        
        logger.debug("Occupancy statistics: {}/{} rooms occupied ({})", 
                   occupiedRooms, totalRooms, response.occupancyRate() + "%");
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает статистику по типам номеров.
     */
    @GetMapping("/rooms/by-type")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Статистика по типам номеров", 
               description = "Возвращает аналитику по типам номеров (количество, средняя цена)")
    public ResponseEntity<RoomTypeStatsResponse> getRoomTypeStatistics() {
        
        logger.debug("Fetching room type statistics");
        
        List<Object[]> typeStats = roomRepository.findAveragePriceByRoomType();
        List<Object[]> hotelTypeStats = hotelRepository.findAverageRatingByCity();
        
        RoomTypeStatsResponse response = new RoomTypeStatsResponse(
            typeStats,
            hotelTypeStats
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает статистику бронирований за период.
     */
    @GetMapping("/bookings/time-period")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Статистика бронирований за период", 
               description = "Возвращает статистику бронирований за указанный период времени")
    public ResponseEntity<BookingTimeStatsResponse> getBookingStatisticsForPeriod(
            @Parameter(description = "Начальная дата периода", required = true) 
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            
            @Parameter(description = "Конечная дата периода", required = true) 
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        logger.debug("Fetching booking statistics for period: {} to {}", startDate, endDate);
        
        validateDateRange(startDate, endDate);
        
        // Статистика по блокировкам за период
        List<RoomReservationLock> periodLocks = lockRepository.findLocksForPeriod(startDate, endDate);
        
        long totalBookings = periodLocks.stream()
                .filter(lock -> lock.isConfirmed())
                .count();
        
        long cancelledBookings = periodLocks.stream()
                .filter(lock -> lock.isReleased())
                .count();
        
        double cancellationRate = totalBookings > 0 ? 
            (double) cancelledBookings / totalBookings * 100 : 0.0;
        
        // Группировка по дням
        Map<LocalDate, Long> bookingsByDay = periodLocks.stream()
                .filter(lock -> lock.isConfirmed())
                .collect(Collectors.groupingBy(
                    lock -> lock.getCreatedAt().toLocalDate(),
                    Collectors.counting()
                ));
        
        BookingTimeStatsResponse response = new BookingTimeStatsResponse(
            startDate,
            endDate,
            totalBookings,
            cancelledBookings,
            BigDecimal.valueOf(cancellationRate).setScale(2, RoundingMode.HALF_UP),
            bookingsByDay
        );
        
        logger.debug("Booking statistics: {} bookings, {}% cancellation rate", 
                   totalBookings, response.cancellationRate());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает статистику по городам.
     */
    @GetMapping("/cities")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Статистика по городам", 
               description = "Возвращает аналитику по отелям и номерам в разрезе городов")
    public ResponseEntity<CityStatsResponse> getCityStatistics() {
        
        logger.debug("Fetching city statistics");
        
        List<Object[]> hotelCountByCity = hotelRepository.findHotelCountByCity();
        List<Object[]> averageRatingByCity = hotelRepository.findAverageRatingByCity();
        List<String> allCities = hotelRepository.findAllDistinctCities();
        
        // Подсчет комнат по городам
        Map<String, Long> roomCountByCity = allCities.stream()
                .collect(Collectors.toMap(
                    city -> city,
                    city -> hotelRepository.findTotalRoomCountByCity(city)
                ));
        
        CityStatsResponse response = new CityStatsResponse(
            hotelCountByCity,
            averageRatingByCity,
            roomCountByCity
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает статистику по конкретному отелю.
     */
    @GetMapping("/hotels/{hotelId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Статистика по отелю", 
               description = "Возвращает детальную статистику по указанному отелю")
    public ResponseEntity<HotelStatsResponse> getHotelStatistics(
            @Parameter(description = "ID отеля", required = true) 
            @PathVariable Long hotelId) {
        
        logger.debug("Fetching statistics for hotel: {}", hotelId);
        
        return hotelRepository.findById(hotelId)
                .map(hotel -> {
                    long availableRooms = roomRepository.countAvailableRoomsByHotel(hotelId);
                    long totalRooms = hotel.getTotalRoomCount();
                    double availabilityRate = totalRooms > 0 ? 
                        (double) availableRooms / totalRooms * 100 : 0.0;
                    
                    List<Object[]> roomTypeStats = roomRepository.findRoomTypeStatisticsByHotel(hotelId);
                    
                    HotelStatsResponse response = new HotelStatsResponse(
                        hotel.getId(),
                        hotel.getName(),
                        hotel.getCity(),
                        totalRooms,
                        availableRooms,
                        BigDecimal.valueOf(availabilityRate).setScale(2, RoundingMode.HALF_UP),
                        hotel.getStarRating(),
                        roomTypeStats
                    );
                    
                    logger.debug("Hotel statistics: {} ({}% availability)", 
                               hotel.getName(), response.availabilityRate());
                    
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    logger.warn("Hotel not found for statistics: {}", hotelId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Получает активные блокировки и их статусы.
     */
    @GetMapping("/locks/active")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Активные блокировки", 
               description = "Возвращает статистику по активным блокировкам номеров. Только для администраторов")
    public ResponseEntity<ActiveLocksResponse> getActiveLocks() {
        
        logger.debug("Fetching active locks statistics");
        
        List<Object[]> lockStats = lockRepository.findLockStatisticsByStatus();
        List<Object[]> activeLocksByRoom = lockRepository.findActiveLockCountByRoom();
        
        // Находим истекшие блокировки
        List<RoomReservationLock> expiredLocks = lockRepository.findExpiredLocks(LocalDateTime.now());
        
        ActiveLocksResponse response = new ActiveLocksResponse(
            lockStats,
            activeLocksByRoom,
            expiredLocks.size()
        );
        
        logger.debug("Active locks statistics: {} expired locks found", expiredLocks.size());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает комнаты, требующие внимания (обслуживание, уборка).
     */
    @GetMapping("/rooms/attention-required")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Operation(summary = "Комнаты, требующие внимания", 
               description = "Возвращает список комнат, требующих обслуживания или уборки")
    public ResponseEntity<AttentionRequiredResponse> getRoomsRequiringAttention() {
        
        logger.debug("Fetching rooms requiring attention");
        
        List<Room> maintenanceRooms = roomRepository.findRoomsUnderMaintenance();
        List<Room> cleaningRooms = roomRepository.findRoomsUnderCleaning();
        
        AttentionRequiredResponse response = new AttentionRequiredResponse(
            maintenanceRooms.stream().map(RoomResponse::fromRoom).toList(),
            cleaningRooms.stream().map(RoomResponse::fromRoom).toList()
        );
        
        logger.debug("Found {} rooms under maintenance, {} rooms under cleaning", 
                   maintenanceRooms.size(), cleaningRooms.size());
        
        return ResponseEntity.ok(response);
    }

    // ==================== PRIVATE HELPER METHODS ====================

    /**
     * Проверяет валидность диапазона дат.
     */
    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date cannot be after end date");
        }
        
        if (endDate.isAfter(LocalDate.now().plusYears(1))) {
            throw new IllegalArgumentException("Date range cannot extend more than 1 year into the future");
        }
    }

    // ==================== DTO CLASSES ====================

    public record PopularRoomsResponse(List<PopularRoomStats> rooms, int limit) {}
    
    public record PopularRoomStats(
        Long roomId,
        String roomNumber,
        String hotelName,
        Room.RoomType type,
        Long timesBooked,
        BigDecimal pricePerNight
    ) {
        public static PopularRoomStats fromRoom(Room room) {
            return new PopularRoomStats(
                room.getId(),
                room.getNumber(),
                room.getHotel() != null ? room.getHotel().getName() : "Unknown",
                room.getType(),
                room.getTimesBooked(),
                room.getPricePerNight()
            );
        }
    }

    public record OccupancyStatsResponse(
        Long totalRooms,
        Long availableRooms,
        Long occupiedRooms,
        BigDecimal occupancyRate,
        List<Object[]> roomTypeStats,
        List<Object[]> hotelStats
    ) {}

    public record RoomTypeStatsResponse(
        List<Object[]> typeStatistics,
        List<Object[]> cityStatistics
    ) {}

    public record BookingTimeStatsResponse(
        LocalDate startDate,
        LocalDate endDate,
        Long totalBookings,
        Long cancelledBookings,
        BigDecimal cancellationRate,
        Map<LocalDate, Long> bookingsByDay
    ) {}

    public record CityStatsResponse(
        List<Object[]> hotelCountByCity,
        List<Object[]> averageRatingByCity,
        Map<String, Long> roomCountByCity
    ) {}

    public record HotelStatsResponse(
        Long hotelId,
        String hotelName,
        String city,
        Long totalRooms,
        Long availableRooms,
        BigDecimal availabilityRate,
        Integer starRating,
        List<Object[]> roomTypeStats
    ) {}

    public record ActiveLocksResponse(
        List<Object[]> lockStatistics,
        List<Object[]> activeLocksByRoom,
        Integer expiredLocksCount
    ) {}

    public record AttentionRequiredResponse(
        List<RoomResponse> maintenanceRooms,
        List<RoomResponse> cleaningRooms
    ) {}

    public record RoomResponse(
        Long id,
        String number,
        String hotelName,
        String status
    ) {
        public static RoomResponse fromRoom(Room room) {
            return new RoomResponse(
                room.getId(),
                room.getNumber(),
                room.getHotel() != null ? room.getHotel().getName() : "Unknown",
                room.getStatus().name()
            );
        }
    }
}
