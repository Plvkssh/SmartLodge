package com.example.booking.web;

import com.example.booking.m.Booking;
import com.example.booking.repositor.BookingRepository;
import com.example.booking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Контроллер для управления бронированиями.
 * Предоставляет эндпойнты для создания, просмотра и управления бронированиями.
 */
@RestController
@RequestMapping("/bookings")
@SecurityRequirement(name = "bearer-jwt")
@Tag(name = "Bookings", description = "API для управления бронированиями отелей")
public class BookingController {
    
    private static final Logger logger = LoggerFactory.getLogger(BookingController.class);
    
    private final BookingService bookingService;
    private final BookingRepository bookingRepository;

    public BookingController(BookingService bookingService, BookingRepository bookingRepository) {
        this.bookingService = bookingService;
        this.bookingRepository = bookingRepository;
    }

    /**
     * Создает новое бронирование с поддержкой идемпотентности.
     */
    @PostMapping
    @Operation(summary = "Создать бронирование", description = "Создает новое бронирование с проверкой доступности номера")
    public ResponseEntity<BookingResponse> createBooking(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateBookingRequest request) {
        
        Long userId = extractUserId(jwt);
        String requestId = generateRequestIdIfMissing(request.requestId());
        
        logger.info("Creating booking for user: {}, room: {}, dates: {} to {}", 
                   userId, request.roomId(), request.startDate(), request.endDate());
        
        try {
            Booking booking = bookingService.createBooking(
                userId, request.roomId(), request.startDate(), request.endDate(), requestId
            );
            
            logger.info("Booking created successfully: {}", booking.getId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(BookingResponse.fromBooking(booking));
                    
        } catch (IllegalArgumentException e) {
            logger.warn("Booking creation failed for user {}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(BookingResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error during booking creation for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BookingResponse.error("Internal server error"));
        }
    }

    /**
     * Получает бронирования текущего пользователя с поддержкой пагинации.
     */
    @GetMapping
    @Operation(summary = "Получить мои бронирования", description = "Возвращает список бронирований текущего пользователя")
    public ResponseEntity<BookingListResponse> getMyBookings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Long userId = extractUserId(jwt);
        logger.debug("Fetching bookings for user: {}, page: {}, size: {}", userId, page, size);
        
        Pageable pageable = PageRequest.of(page, size);
        List<Booking> userBookings = bookingRepository.findUserBookingsWithPagination(userId, pageable);
        
        return ResponseEntity.ok(new BookingListResponse(userBookings, page, size));
    }

    /**
     * Получает рекомендации по номерам с учетом статистики бронирований.
     */
    @GetMapping("/suggestions")
    @Operation(summary = "Получить рекомендации номеров", description = "Возвращает отсортированный список номеров для равномерного распределения")
    public ResponseEntity<RoomSuggestionsResponse> getRoomSuggestions() {
        logger.debug("Fetching room suggestions");
        
        return bookingService.getRoomSuggestions()
                .map(rooms -> {
                    logger.debug("Returning {} room suggestions", rooms.size());
                    return ResponseEntity.ok(new RoomSuggestionsResponse(rooms));
                })
                .onErrorReturn(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(RoomSuggestionsResponse.error("Hotel service unavailable")))
                .block();
    }

    /**
     * Получает все бронирования (только для администраторов).
     */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Получить все бронирования", description = "Возвращает все бронирования в системе (только для администраторов)")
    public ResponseEntity<BookingListResponse> getAllBookings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        logger.info("Admin {} fetching all bookings, page: {}, size: {}", 
                   jwt.getSubject(), page, size);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<Booking> allBookings = bookingRepository.findAll(pageable);
        
        return ResponseEntity.ok(new BookingListResponse(
            allBookings.getContent(), 
            allBookings.getNumber(), 
            allBookings.getSize(),
            allBookings.getTotalElements()
        ));
    }

    /**
     * Получает конкретное бронирование по ID.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Получить бронирование по ID", description = "Возвращает бронирование по идентификатору с проверкой прав доступа")
    public ResponseEntity<BookingResponse> getBookingById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        
        Long userId = extractUserId(jwt);
        logger.debug("Fetching booking {} for user {}", id, userId);
        
        return bookingRepository.findById(id)
                .map(booking -> {
                    if (!booking.getUserId().equals(userId) && !isAdmin(jwt)) {
                        logger.warn("User {} attempted to access booking {} without permission", userId, id);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                    }
                    return ResponseEntity.ok(BookingResponse.fromBooking(booking));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Отменяет бронирование.
     */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Отменить бронирование", description = "Отменяет существующее бронирование")
    public ResponseEntity<BookingResponse> cancelBooking(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        
        Long userId = extractUserId(jwt);
        logger.info("Cancelling booking {} for user {}", id, userId);
        
        // Реализация отмены бронирования
        // В реальной системе здесь была бы логика отмены с компенсирующими действиями
        
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(BookingResponse.error("Cancel functionality not implemented"));
    }

    // Вспомогательные методы

    private Long extractUserId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    private boolean isAdmin(Jwt jwt) {
        return "ADMIN".equals(jwt.getClaimAsString("scope"));
    }

    private String generateRequestIdIfMissing(String requestId) {
        return requestId != null ? requestId : UUID.randomUUID().toString();
    }

    // DTO классы

    public record CreateBookingRequest(
        @jakarta.validation.constraints.NotNull(message = "Room ID is required")
        Long roomId,
        
        @jakarta.validation.constraints.NotNull(message = "Start date is required")
        LocalDate startDate,
        
        @jakarta.validation.constraints.NotNull(message = "End date is required")
        LocalDate endDate,
        
        String requestId
    ) {
        public CreateBookingRequest {
            if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
                throw new IllegalArgumentException("Start date cannot be after end date");
            }
            if (startDate != null && startDate.isBefore(LocalDate.now())) {
                throw new IllegalArgumentException("Start date cannot be in the past");
            }
        }
    }

    public record BookingResponse(
        Long id,
        String requestId,
        Long userId,
        Long roomId,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String correlationId,
        String error
    ) {
        public static BookingResponse fromBooking(Booking booking) {
            return new BookingResponse(
                booking.getId(),
                booking.getRequestId(),
                booking.getUserId(),
                booking.getRoomId(),
                booking.getStartDate(),
                booking.getEndDate(),
                booking.getStatus().name(),
                booking.getCorrelationId(),
                null
            );
        }
        
        public static BookingResponse error(String error) {
            return new BookingResponse(null, null, null, null, null, null, null, null, error);
        }
    }

    public record BookingListResponse(
        List<BookingResponse> bookings,
        int page,
        int size,
        long totalElements
    ) {
        public BookingListResponse(List<Booking> bookings, int page, int size) {
            this(bookings.stream().map(BookingResponse::fromBooking).toList(), page, size, bookings.size());
        }
        
        public BookingListResponse(List<Booking> bookings, int page, int size, long totalElements) {
            this(bookings.stream().map(BookingResponse::fromBooking).toList(), page, size, totalElements);
        }
    }

    public record RoomSuggestionsResponse(
        List<BookingService.RoomSuggestion> suggestions,
        String error
    ) {
        public RoomSuggestionsResponse(List<BookingService.RoomSuggestion> suggestions) {
            this(suggestions, null);
        }
        
        public static RoomSuggestionsResponse error(String error) {
            return new RoomSuggestionsResponse(null, error);
        }
    }
}
