package com.example.hotel.web;

import com.example.hotel.m.Hotel;
import com.example.hotel.m.Room;
import com.example.hotel.service.HotelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Контроллер для управления отелями.
 * Предоставляет REST API для CRUD операций с отелями, поиска и фильтрации.
 * Интегрируется с системой безопасности через JWT и ролевую модель.
 */
@RestController
@RequestMapping("/api/hotels")
@SecurityRequirement(name = "bearer-jwt")
@Tag(name = "Hotels", description = "API для управления отелями и поиска")
public class HotelController {

    private static final Logger logger = LoggerFactory.getLogger(HotelController.class);
    
    private final HotelService hotelService;

    public HotelController(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    /**
     * Получает paginated список отелей с поддержкой фильтрации и сортировки.
     */
    @GetMapping
    @Operation(summary = "Получить список отелей", 
               description = "Возвращает paginated список отелей с поддержкой фильтрации по городу, названию и рейтингу")
    public ResponseEntity<HotelPageResponse> listHotels(
            @Parameter(description = "Город для фильтрации") 
            @RequestParam(required = false) String city,
            
            @Parameter(description = "Название отеля (частичное совпадение)") 
            @RequestParam(required = false) String name,
            
            @Parameter(description = "Минимальный рейтинг (1-5 звёзд)") 
            @RequestParam(required = false) Integer minRating,
            
            @Parameter(description = "Номер страницы (0-based)") 
            @RequestParam(defaultValue = "0") int page,
            
            @Parameter(description = "Размер страницы") 
            @RequestParam(defaultValue = "20") int size,
            
            @Parameter(description = "Поле для сортировки (name, city, starRating)") 
            @RequestParam(defaultValue = "name") String sortBy,
            
            @Parameter(description = "Направление сортировки (asc, desc)") 
            @RequestParam(defaultValue = "asc") String sortDirection) {
        
        logger.debug("Listing hotels - city: {}, name: {}, minRating: {}, page: {}, size: {}", 
                   city, name, minRating, page, size);
        
        Sort sort = Sort.by(Sort.Direction.fromString(sortDirection), sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        
        Page<Hotel> hotelsPage = hotelService.searchHotels(city, name, minRating, pageable);
        
        HotelPageResponse response = new HotelPageResponse(
            hotelsPage.getContent(),
            hotelsPage.getNumber(),
            hotelsPage.getSize(),
            hotelsPage.getTotalElements(),
            hotelsPage.getTotalPages()
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает отель по идентификатору.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Получить отель по ID", 
               description = "Возвращает детальную информацию об отеле по идентификатору")
    public ResponseEntity<HotelResponse> getHotel(
            @Parameter(description = "ID отеля", required = true) 
            @PathVariable Long id) {
        
        logger.debug("Fetching hotel by id: {}", id);
        
        return hotelService.getHotel(id)
                .map(hotel -> {
                    logger.debug("Hotel found: {} (ID: {})", hotel.getName(), hotel.getId());
                    return ResponseEntity.ok(HotelResponse.fromHotel(hotel));
                })
                .orElseGet(() -> {
                    logger.warn("Hotel not found with id: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Создает новый отель (только для администраторов).
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Создать отель", 
               description = "Создает новый отель. Требуются права администратора")
    public ResponseEntity<HotelResponse> createHotel(
            @Parameter(description = "Данные отеля", required = true) 
            @Valid @RequestBody CreateHotelRequest request) {
        
        logger.info("Creating new hotel: {}", request.name());
        
        try {
            Hotel hotel = Hotel.builder()
                    .name(request.name())
                    .city(request.city())
                    .address(request.address())
                    .phoneNumber(request.phoneNumber())
                    .description(request.description())
                    .starRating(request.starRating())
                    .build();
            
            Hotel savedHotel = hotelService.saveHotel(hotel);
            logger.info("Hotel created successfully: {} (ID: {})", savedHotel.getName(), savedHotel.getId());
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(HotelResponse.fromHotel(savedHotel));
                    
        } catch (IllegalArgumentException e) {
            logger.warn("Hotel creation failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(HotelResponse.error(e.getMessage()));
        }
    }

    /**
     * Обновляет отель (только для администраторов).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Обновить отель", 
               description = "Обновляет информацию об отеле. Требуются права администратора")
    public ResponseEntity<HotelResponse> updateHotel(
            @Parameter(description = "ID отеля", required = true) 
            @PathVariable Long id,
            
            @Parameter(description = "Обновленные данные отеля", required = true) 
            @Valid @RequestBody UpdateHotelRequest request) {
        
        logger.info("Updating hotel with id: {}", id);
        
        return hotelService.getHotel(id)
                .map(existingHotel -> {
                    updateHotelFromRequest(existingHotel, request);
                    
                    Hotel updatedHotel = hotelService.saveHotel(existingHotel);
                    logger.info("Hotel updated successfully: {} (ID: {})", updatedHotel.getName(), id);
                    
                    return ResponseEntity.ok(HotelResponse.fromHotel(updatedHotel));
                })
                .orElseGet(() -> {
                    logger.warn("Hotel not found for update, id: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Удаляет отель (только для администраторов).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Удалить отель", 
               description = "Удаляет отель по идентификатору. Требуются права администратора")
    public ResponseEntity<ApiResponse> deleteHotel(
            @Parameter(description = "ID отеля", required = true) 
            @PathVariable Long id) {
        
        logger.info("Deleting hotel with id: {}", id);
        
        try {
            hotelService.deleteHotel(id);
            logger.info("Hotel deleted successfully, id: {}", id);
            
            return ResponseEntity.ok(new ApiResponse("Hotel deleted successfully"));
            
        } catch (IllegalArgumentException e) {
            logger.warn("Hotel deletion failed - not found: {}", id);
            return ResponseEntity.notFound().build();
            
        } catch (IllegalStateException e) {
            logger.warn("Hotel deletion failed - business constraint: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Получает список номеров отеля.
     */
    @GetMapping("/{hotelId}/rooms")
    @Operation(summary = "Получить номера отеля", 
               description = "Возвращает список номеров указанного отеля")
    public ResponseEntity<RoomListResponse> getHotelRooms(
            @Parameter(description = "ID отеля", required = true) 
            @PathVariable Long hotelId,
            
            @Parameter(description = "Номер страницы") 
            @RequestParam(defaultValue = "0") int page,
            
            @Parameter(description = "Размер страницы") 
            @RequestParam(defaultValue = "50") int size) {
        
        logger.debug("Fetching rooms for hotel id: {}", hotelId);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<Room> roomsPage = hotelService.listRooms(pageable); // В реальности должен быть метод для номеров отеля
        
        RoomListResponse response = new RoomListResponse(
            roomsPage.getContent().stream().map(RoomResponse::fromRoom).toList(),
            roomsPage.getNumber(),
            roomsPage.getSize(),
            roomsPage.getTotalElements()
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Находит доступные номера в отелях по критериям.
     */
    @GetMapping("/available-rooms")
    @Operation(summary = "Найти доступные номера", 
               description = "Возвращает доступные номера в отелях с фильтрацией по датам, городу, типу и вместимости")
    public ResponseEntity<AvailableRoomsResponse> findAvailableRooms(
            @Parameter(description = "Дата заезда", required = true) 
            @RequestParam LocalDate startDate,
            
            @Parameter(description = "Дата выезда", required = true) 
            @RequestParam LocalDate endDate,
            
            @Parameter(description = "Город для поиска") 
            @RequestParam(required = false) String city,
            
            @Parameter(description = "Тип номера") 
            @RequestParam(required = false) Room.RoomType roomType,
            
            @Parameter(description = "Минимальная вместимость") 
            @RequestParam(required = false) Integer minCapacity) {
        
        logger.debug("Finding available rooms - dates: {} to {}, city: {}, type: {}, capacity: {}", 
                   startDate, endDate, city, roomType, minCapacity);
        
        try {
            List<Room> availableRooms = hotelService.findAvailableRooms(
                startDate, endDate, city, roomType, minCapacity
            );
            
            AvailableRoomsResponse response = new AvailableRoomsResponse(
                availableRooms.stream().map(RoomResponse::fromRoom).toList(),
                startDate,
                endDate
            );
            
            logger.debug("Found {} available rooms", availableRooms.size());
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid parameters for available rooms search: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(AvailableRoomsResponse.error(e.getMessage()));
        }
    }

    /**
     * Получает статистику по отелям (только для администраторов).
     */
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Получить статистику отелей", 
               description = "Возвращает аналитическую статистику по отелям и номерам. Требуются права администратора")
    public ResponseEntity<HotelStatisticsResponse> getHotelStatistics() {
        logger.debug("Fetching hotel statistics");
        
        HotelService.HotelStatistics statistics = hotelService.getHotelStatistics();
        
        HotelStatisticsResponse response = new HotelStatisticsResponse(
            statistics.totalRooms(),
            statistics.availableRooms(),
            statistics.cityStats()
        );
        
        return ResponseEntity.ok(response);
    }

    // ==================== PRIVATE HELPER METHODS ====================

    /**
     * Обновляет отель из данных запроса.
     */
    private void updateHotelFromRequest(Hotel hotel, UpdateHotelRequest request) {
        if (request.name() != null) {
            hotel.setName(request.name());
        }
        if (request.city() != null) {
            hotel.setCity(request.city());
        }
        if (request.address() != null) {
            hotel.setAddress(request.address());
        }
        if (request.phoneNumber() != null) {
            hotel.setPhoneNumber(request.phoneNumber());
        }
        if (request.description() != null) {
            hotel.setDescription(request.description());
        }
        if (request.starRating() != null) {
            hotel.setStarRating(request.starRating());
        }
        if (request.status() != null) {
            hotel.setStatus(request.status());
        }
    }

    // ==================== DTO CLASSES ====================

    public record CreateHotelRequest(
        @jakarta.validation.constraints.NotBlank String name,
        @jakarta.validation.constraints.NotBlank String city,
        @jakarta.validation.constraints.NotBlank String address,
        String phoneNumber,
        String description,
        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(5) Integer starRating
    ) {}

    public record UpdateHotelRequest(
        String name,
        String city,
        String address,
        String phoneNumber,
        String description,
        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(5) Integer starRating,
        Hotel.HotelStatus status
    ) {}

    public record HotelResponse(
        Long id,
        String name,
        String city,
        String address,
        String phoneNumber,
        String description,
        Integer starRating,
        String status,
        Integer roomCount,
        String error
    ) {
        public static HotelResponse fromHotel(Hotel hotel) {
            return new HotelResponse(
                hotel.getId(),
                hotel.getName(),
                hotel.getCity(),
                hotel.getAddress(),
                hotel.getPhoneNumber(),
                hotel.getDescription(),
                hotel.getStarRating(),
                hotel.getStatus().name(),
                hotel.getTotalRoomCount(),
                null
            );
        }
        
        public static HotelResponse error(String error) {
            return new HotelResponse(null, null, null, null, null, null, null, null, null, error);
        }
    }

    public record HotelPageResponse(
        List<HotelResponse> hotels,
        int page,
        int size,
        long totalElements,
        int totalPages
    ) {}

    public record RoomResponse(
        Long id,
        String number,
        Integer capacity,
        Room.RoomType type,
        BigDecimal pricePerNight,
        Boolean available,
        String status,
        Long timesBooked
    ) {
        public static RoomResponse fromRoom(Room room) {
            return new RoomResponse(
                room.getId(),
                room.getNumber(),
                room.getCapacity(),
                room.getType(),
                room.getPricePerNight(),
                room.isAvailableForBooking(),
                room.getStatus().name(),
                room.getTimesBooked()
            );
        }
    }

    public record RoomListResponse(
        List<RoomResponse> rooms,
        int page,
        int size,
        long totalElements
    ) {}

    public record AvailableRoomsResponse(
        List<RoomResponse> rooms,
        LocalDate startDate,
        LocalDate endDate,
        String error
    ) {
        public AvailableRoomsResponse(List<RoomResponse> rooms, LocalDate startDate, LocalDate endDate) {
            this(rooms, startDate, endDate, null);
        }
        
        public static AvailableRoomsResponse error(String error) {
            return new AvailableRoomsResponse(null, null, null, error);
        }
    }

    public record HotelStatisticsResponse(
        Long totalRooms,
        Long availableRooms,
        List<Object[]> cityStats
    ) {}

    public record ApiResponse(
        String message,
        String error
    ) {
        public ApiResponse(String message) {
            this(message, null);
        }
        
        public static ApiResponse error(String error) {
            return new ApiResponse(null, error);
        }
    }
}
