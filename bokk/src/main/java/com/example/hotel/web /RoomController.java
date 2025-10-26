package com.example.hotel.web;

import com.example.hotel.m.Room;
import com.example.hotel.m.RoomReservationLock;
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
 * Контроллер для управления номерами отелей.
 * Предоставляет REST API для CRUD операций с номерами, управления доступностью
 * и интеграции с системой бронирования через распределенные саги.
 */
@RestController
@RequestMapping("/api/rooms")
@SecurityRequirement(name = "bearer-jwt")
@Tag(name = "Rooms", description = "API для управления номерами отелей и их доступностью")
public class RoomController {

    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);
    
    private final HotelService hotelService;

    public RoomController(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    /**
     * Получает paginated список номеров с поддержкой фильтрации.
     */
    @GetMapping
    @Operation(summary = "Получить список номеров", 
               description = "Возвращает paginated список номеров с поддержкой фильтрации по отелю, типу и доступности")
    public ResponseEntity<RoomPageResponse> listRooms(
            @Parameter(description = "ID отеля для фильтрации") 
            @RequestParam(required = false) Long hotelId,
            
            @Parameter(description = "Тип номера") 
            @RequestParam(required = false) Room.RoomType type,
            
            @Parameter(description = "Минимальная вместимость") 
            @RequestParam(required = false) Integer minCapacity,
            
            @Parameter(description = "Максимальная цена за ночь") 
            @RequestParam(required = false) BigDecimal maxPrice,
            
            @Parameter(description = "Только доступные номера") 
            @RequestParam(defaultValue = "false") boolean availableOnly,
            
            @Parameter(description = "Номер страницы") 
            @RequestParam(defaultValue = "0") int page,
            
            @Parameter(description = "Размер страницы") 
            @RequestParam(defaultValue = "20") int size,
            
            @Parameter(description = "Поле для сортировки") 
            @RequestParam(defaultValue = "number") String sortBy) {
        
        logger.debug("Listing rooms - hotelId: {}, type: {}, minCapacity: {}, availableOnly: {}", 
                   hotelId, type, minCapacity, availableOnly);
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy));
        Page<Room> roomsPage = hotelService.listRooms(pageable); // В реальности должен быть метод с фильтрами
        
        RoomPageResponse response = new RoomPageResponse(
            roomsPage.getContent().stream().map(RoomResponse::fromRoom).toList(),
            roomsPage.getNumber(),
            roomsPage.getSize(),
            roomsPage.getTotalElements(),
            roomsPage.getTotalPages()
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Получает номер по идентификатору.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Получить номер по ID", 
               description = "Возвращает детальную информацию о номере по идентификатору")
    public ResponseEntity<RoomResponse> getRoom(
            @Parameter(description = "ID номера", required = true) 
            @PathVariable Long id) {
        
        logger.debug("Fetching room by id: {}", id);
        
        return hotelService.getRoom(id)
                .map(room -> {
                    logger.debug("Room found: {} (ID: {})", room.getNumber(), room.getId());
                    return ResponseEntity.ok(RoomResponse.fromRoom(room));
                })
                .orElseGet(() -> {
                    logger.warn("Room not found with id: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Создает новый номер (только для администраторов).
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Создать номер", 
               description = "Создает новый номер в отеле. Требуются права администратора")
    public ResponseEntity<RoomResponse> createRoom(
            @Parameter(description = "Данные номера", required = true) 
            @Valid @RequestBody CreateRoomRequest request) {
        
        logger.info("Creating new room: {} in hotel: {}", request.number(), request.hotelId());
        
        try {
            // В реальной системе здесь был бы поиск отеля по ID
            Room room = Room.builder()
                    .number(request.number())
                    .capacity(request.capacity())
                    .type(request.type())
                    .pricePerNight(request.pricePerNight())
                    .description(request.description())
                    .hasWiFi(request.hasWiFi())
                    .hasAirConditioning(request.hasAirConditioning())
                    .hasTV(request.hasTV())
                    .hasMiniBar(request.hasMiniBar())
                    .build();
            
            Room savedRoom = hotelService.saveRoom(room);
            logger.info("Room created successfully: {} (ID: {})", savedRoom.getNumber(), savedRoom.getId());
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(RoomResponse.fromRoom(savedRoom));
                    
        } catch (IllegalArgumentException e) {
            logger.warn("Room creation failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(RoomResponse.error(e.getMessage()));
        }
    }

    /**
     * Обновляет номер (только для администраторов).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Обновить номер", 
               description = "Обновляет информацию о номере. Требуются права администратора")
    public ResponseEntity<RoomResponse> updateRoom(
            @Parameter(description = "ID номера", required = true) 
            @PathVariable Long id,
            
            @Parameter(description = "Обновленные данные номера", required = true) 
            @Valid @RequestBody UpdateRoomRequest request) {
        
        logger.info("Updating room with id: {}", id);
        
        return hotelService.getRoom(id)
                .map(existingRoom -> {
                    updateRoomFromRequest(existingRoom, request);
                    
                    Room updatedRoom = hotelService.saveRoom(existingRoom);
                    logger.info("Room updated successfully: {} (ID: {})", updatedRoom.getNumber(), id);
                    
                    return ResponseEntity.ok(RoomResponse.fromRoom(updatedRoom));
                })
                .orElseGet(() -> {
                    logger.warn("Room not found for update, id: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Удаляет номер (только для администраторов).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Удалить номер", 
               description = "Удаляет номер по идентификатору. Требуются права администратора")
    public ResponseEntity<ApiResponse> deleteRoom(
            @Parameter(description = "ID номера", required = true) 
            @PathVariable Long id) {
        
        logger.info("Deleting room with id: {}", id);
        
        try {
            hotelService.deleteRoom(id);
            logger.info("Room deleted successfully, id: {}", id);
            
            return ResponseEntity.ok(new ApiResponse("Room deleted successfully"));
            
        } catch (IllegalArgumentException e) {
            logger.warn("Room deletion failed - not found: {}", id);
            return ResponseEntity.notFound().build();
            
        } catch (IllegalStateException e) {
            logger.warn("Room deletion failed - business constraint: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== AVAILABILITY MANAGEMENT (SAGA ENDPOINTS) ====================

    /**
     * Временно блокирует номер для бронирования (шаг 1 саги).
     * Доступно для сервисов бронирования и администраторов.
     */
    @PostMapping("/{id}/hold")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @Operation(summary = "Заблокировать номер", 
               description = "Временно блокирует номер на указанные даты для бронирования. Используется в распределенной саге.")
    public ResponseEntity<LockResponse> holdRoom(
            @Parameter(description = "ID номера", required = true) 
            @PathVariable Long id,
            
            @Parameter(description = "Данные для блокировки", required = true) 
            @Valid @RequestBody HoldRoomRequest request) {
        
        logger.info("Attempting to hold room - roomId: {}, requestId: {}, dates: {} to {}", 
                   id, request.requestId(), request.startDate(), request.endDate());
        
        try {
            RoomReservationLock lock = hotelService.holdRoom(
                request.requestId(), id, request.startDate(), request.endDate()
            );
            
            logger.info("Room hold successful - lockId: {}, roomId: {}", lock.getId(), id);
            return ResponseEntity.ok(LockResponse.fromLock(lock));
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid hold request for room {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(LockResponse.error(e.getMessage()));
                    
        } catch (IllegalStateException e) {
            logger.warn("Room hold conflict for room {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(LockResponse.error(e.getMessage()));
        }
    }

    /**
     * Подтверждает блокировку номера (шаг 2 саги).
     * Доступно для сервисов бронирования и администраторов.
     */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @Operation(summary = "Подтвердить блокировку", 
               description = "Подтверждает ранее созданную блокировку номера. Финализирует бронирование в саге.")
    public ResponseEntity<LockResponse> confirmHold(
            @Parameter(description = "ID номера", required = true) 
            @PathVariable Long id,
            
            @Parameter(description = "Данные для подтверждения", required = true) 
            @Valid @RequestBody ConfirmHoldRequest request) {
        
        logger.info("Confirming room hold - roomId: {}, requestId: {}", id, request.requestId());
        
        try {
            RoomReservationLock lock = hotelService.confirmHold(request.requestId());
            logger.info("Room hold confirmed successfully - lockId: {}, roomId: {}", lock.getId(), id);
            
            return ResponseEntity.ok(LockResponse.fromLock(lock));
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid confirm request for room {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(LockResponse.error(e.getMessage()));
                    
        } catch (IllegalStateException e) {
            logger.warn("Room confirm failed for room {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(LockResponse.error(e.getMessage()));
        }
    }

    /**
     * Освобождает блокировку номера (компенсирующее действие саги).
     * Доступно для сервисов бронирования и администраторов.
     */
    @PostMapping("/{id}/release")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    @Operation(summary = "Освободить блокировку", 
               description = "Освобождает блокировку номера. Используется для компенсирующих действий в саге.")
    public ResponseEntity<LockResponse> releaseHold(
            @Parameter(description = "ID номера", required = true) 
            @PathVariable Long id,
            
            @Parameter(description = "Данные для освобождения", required = true) 
            @Valid @RequestBody ReleaseHoldRequest request) {
        
        logger.info("Releasing room hold - roomId: {}, requestId: {}", id, request.requestId());
        
        try {
            RoomReservationLock lock = hotelService.releaseHold(request.requestId());
            logger.info("Room hold released successfully - lockId: {}, roomId: {}", lock.getId(), id);
            
            return ResponseEntity.ok(LockResponse.fromLock(lock));
            
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid release request for room {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(LockResponse.error(e.getMessage()));
                    
        } catch (IllegalStateException e) {
            logger.warn("Room release failed for room {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(LockResponse.error(e.getMessage()));
        }
    }

    /**
     * Получает статистику по номеру (только для администраторов).
     */
    @GetMapping("/{id}/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Получить статистику номера", 
               description = "Возвращает статистику бронирований и использования номера. Требуются права администратора")
    public ResponseEntity<RoomStatisticsResponse> getRoomStatistics(
            @Parameter(description = "ID номера", required = true) 
            @PathVariable Long id) {
        
        logger.debug("Fetching statistics for room: {}", id);
        
        return hotelService.getRoom(id)
                .map(room -> {
                    RoomStatisticsResponse response = new RoomStatisticsResponse(
                        room.getId(),
                        room.getNumber(),
                        room.getTimesBooked(),
                        room.getCreatedAt(),
                        room.getStatus().name()
                    );
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    logger.warn("Room not found for statistics, id: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    // ==================== PRIVATE HELPER METHODS ====================

    /**
     * Обновляет номер из данных запроса.
     */
    private void updateRoomFromRequest(Room room, UpdateRoomRequest request) {
        if (request.number() != null) {
            room.setNumber(request.number());
        }
        if (request.capacity() != null) {
            room.setCapacity(request.capacity());
        }
        if (request.type() != null) {
            room.setType(request.type());
        }
        if (request.pricePerNight() != null) {
            room.setPricePerNight(request.pricePerNight());
        }
        if (request.description() != null) {
            room.setDescription(request.description());
        }
        if (request.hasWiFi() != null) {
            room.setHasWiFi(request.hasWiFi());
        }
        if (request.hasAirConditioning() != null) {
            room.setHasAirConditioning(request.hasAirConditioning());
        }
        if (request.hasTV() != null) {
            room.setHasTV(request.hasTV());
        }
        if (request.hasMiniBar() != null) {
            room.setHasMiniBar(request.hasMiniBar());
        }
        if (request.status() != null) {
            room.setStatus(request.status());
        }
    }

    // ==================== DTO CLASSES ====================

    public record CreateRoomRequest(
        @jakarta.validation.constraints.NotBlank String number,
        @jakarta.validation.constraints.NotNull Long hotelId,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(1) Integer capacity,
        @jakarta.validation.constraints.NotNull Room.RoomType type,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0.0") BigDecimal pricePerNight,
        String description,
        Boolean hasWiFi,
        Boolean hasAirConditioning,
        Boolean hasTV,
        Boolean hasMiniBar
    ) {}

    public record UpdateRoomRequest(
        String number,
        @jakarta.validation.constraints.Min(1) Integer capacity,
        Room.RoomType type,
        @jakarta.validation.constraints.DecimalMin("0.0") BigDecimal pricePerNight,
        String description,
        Boolean hasWiFi,
        Boolean hasAirConditioning,
        Boolean hasTV,
        Boolean hasMiniBar,
        Room.RoomStatus status
    ) {}

    public record HoldRoomRequest(
        @jakarta.validation.constraints.NotBlank String requestId,
        @jakarta.validation.constraints.NotNull LocalDate startDate,
        @jakarta.validation.constraints.NotNull LocalDate endDate
    ) {}

    public record ConfirmHoldRequest(
        @jakarta.validation.constraints.NotBlank String requestId
    ) {}

    public record ReleaseHoldRequest(
        @jakarta.validation.constraints.NotBlank String requestId
    ) {}

    public record RoomResponse(
        Long id,
        String number,
        Integer capacity,
        Room.RoomType type,
        BigDecimal pricePerNight,
        String description,
        Boolean hasWiFi,
        Boolean hasAirConditioning,
        Boolean hasTV,
        Boolean hasMiniBar,
        Boolean available,
        String status,
        Long timesBooked,
        String error
    ) {
        public static RoomResponse fromRoom(Room room) {
            return new RoomResponse(
                room.getId(),
                room.getNumber(),
                room.getCapacity(),
                room.getType(),
                room.getPricePerNight(),
                room.getDescription(),
                room.getHasWiFi(),
                room.getHasAirConditioning(),
                room.getHasTV(),
                room.getHasMiniBar(),
                room.isAvailableForBooking(),
                room.getStatus().name(),
                room.getTimesBooked(),
                null
            );
        }
        
        public static RoomResponse error(String error) {
            return new RoomResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, error);
        }
    }

    public record RoomPageResponse(
        List<RoomResponse> rooms,
        int page,
        int size,
        long totalElements,
        int totalPages
    ) {}

    public record LockResponse(
        Long id,
        String requestId,
        Long roomId,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String error
    ) {
        public static LockResponse fromLock(RoomReservationLock lock) {
            return new LockResponse(
                lock.getId(),
                lock.getRequestId(),
                lock.getRoomId(),
                lock.getStartDate(),
                lock.getEndDate(),
                lock.getStatus().name(),
                null
            );
        }
        
        public static LockResponse error(String error) {
            return new LockResponse(null, null, null, null, null, null, error);
        }
    }

    public record RoomStatisticsResponse(
        Long roomId,
        String roomNumber,
        Long timesBooked,
        java.time.LocalDateTime createdAt,
        String currentStatus
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
