package com.example.booking.service;

import com.example.booking.m.Booking;
import com.example.booking.repositor.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сервис для управления бронированиями.
 * Реализует распределенную сагу для согласованности данных между сервисами.
 */
@Service
@Transactional
public class BookingService {
    
    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);
    
    // Константы для саги
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String HOTEL_HOLD_PATH = "/rooms/{roomId}/hold";
    private static final String HOTEL_CONFIRM_PATH = "/rooms/{roomId}/confirm";
    private static final String HOTEL_RELEASE_PATH = "/rooms/{roomId}/release";
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(300);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);
    
    private final BookingRepository bookingRepository;
    private final WebClient hotelWebClient;
    private final Duration timeout;
    private final int maxRetries;

    public BookingService(
            BookingRepository bookingRepository,
            WebClient.Builder webClientBuilder,
            @Value("${hotel.service.base-url}") String hotelBaseUrl,
            @Value("${hotel.service.timeout-ms:5000}") int timeoutMs,
            @Value("${hotel.service.max-retries:3}") int maxRetries
    ) {
        this.bookingRepository = bookingRepository;
        this.hotelWebClient = webClientBuilder.baseUrl(hotelBaseUrl).build();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.maxRetries = maxRetries;
        
        logger.info("BookingService initialized with timeout: {}ms, retries: {}", timeoutMs, maxRetries);
    }

    /**
     * Создает новое бронирование с поддержкой идемпотентности и распределенной саги.
     * PENDING → (hold → confirm → CONFIRMED) или (error → release → CANCELLED)
     */
    @Transactional
    public Booking createBooking(Long userId, Long roomId, LocalDate startDate, 
                               LocalDate endDate, String requestId) {
        validateBookingParameters(userId, roomId, startDate, endDate, requestId);
        
        String correlationId = generateCorrelationId();
        logger.info("[{}] Starting booking process for user: {}, room: {}", 
                   correlationId, userId, roomId);

        // Проверка идемпотентности
        Booking booking = handleIdempotency(requestId, correlationId);
        if (booking != null) {
            return booking;
        }

        // Создание бронирования в статусе PENDING
        booking = createPendingBooking(userId, roomId, startDate, endDate, requestId, correlationId);
        
        try {
            executeBookingSaga(booking, correlationId);
            logger.info("[{}] Booking saga completed successfully", correlationId);
        } catch (Exception sagaException) {
            handleSagaFailure(booking, correlationId, sagaException);
        }

        return booking;
    }

    /**
     * Проверяет параметры бронирования.
     */
    private void validateBookingParameters(Long userId, Long roomId, LocalDate startDate, 
                                         LocalDate endDate, String requestId) {
        if (userId == null || roomId == null || startDate == null || endDate == null || requestId == null) {
            throw new IllegalArgumentException("All booking parameters must be provided");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date cannot be after end date");
        }
        if (startDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Start date cannot be in the past");
        }
    }

    /**
     * Обрабатывает идемпотентность запроса.
     */
    private Booking handleIdempotency(String requestId, String correlationId) {
        return bookingRepository.findByRequestId(requestId)
                .map(existingBooking -> {
                    logger.info("[{}] Idempotency: found existing booking with id: {}", 
                               correlationId, existingBooking.getId());
                    return existingBooking;
                })
                .orElse(null);
    }

    /**
     * Создает бронирование в статусе PENDING.
     */
    private Booking createPendingBooking(Long userId, Long roomId, LocalDate startDate, 
                                       LocalDate endDate, String requestId, String correlationId) {
        Booking booking = new Booking();
        booking.setRequestId(requestId);
        booking.setUserId(userId);
        booking.setRoomId(roomId);
        booking.setStartDate(startDate);
        booking.setEndDate(endDate);
        booking.setStatus(Booking.BookingStatus.PENDING);
        booking.setCorrelationId(correlationId);
        booking.setCreatedAt(OffsetDateTime.now());
        
        Booking savedBooking = bookingRepository.save(booking);
        logger.info("[{}] PENDING booking created with id: {}", correlationId, savedBooking.getId());
        
        return savedBooking;
    }

    /**
     * Выполняет распределенную сагу бронирования.
     */
    private void executeBookingSaga(Booking booking, String correlationId) {
        // Шаг 1: Удержание номера в Hotel Service
        holdRoomInHotel(booking, correlationId);
        
        // Шаг 2: Подтверждение бронирования в Hotel Service
        confirmRoomInHotel(booking, correlationId);
        
        // Шаг 3: Подтверждение бронирования в Booking Service
        booking.confirm();
        bookingRepository.save(booking);
        logger.info("[{}] Booking CONFIRMED successfully", correlationId);
    }

    /**
     * Удерживает номер в Hotel Service.
     */
    private void holdRoomInHotel(Booking booking, String correlationId) {
        Map<String, String> holdPayload = Map.of(
            "requestId", booking.getRequestId(),
            "startDate", booking.getStartDate().toString(),
            "endDate", booking.getEndDate().toString()
        );

        callHotelService(HOTEL_HOLD_PATH, booking.getRoomId(), holdPayload, correlationId)
            .block(timeout);
        
        logger.info("[{}] Room hold successful for room: {}", correlationId, booking.getRoomId());
    }

    /**
     * Подтверждает номер в Hotel Service.
     */
    private void confirmRoomInHotel(Booking booking, String correlationId) {
        Map<String, String> confirmPayload = Map.of("requestId", booking.getRequestId());

        callHotelService(HOTEL_CONFIRM_PATH, booking.getRoomId(), confirmPayload, correlationId)
            .block(timeout);
        
        logger.info("[{}] Room confirmation successful for room: {}", correlationId, booking.getRoomId());
    }

    /**
     * Обрабатывает неудачу саги с компенсирующими действиями.
     */
    private void handleSagaFailure(Booking booking, String correlationId, Exception exception) {
        logger.error("[{}] Booking saga failed: {}", correlationId, exception.getMessage(), exception);
        
        // Компенсирующее действие: освобождение номера в Hotel Service
        performCompensation(booking, correlationId);
        
        // Отмена бронирования
        booking.cancel();
        bookingRepository.save(booking);
        logger.info("[{}] Booking CANCELLED due to saga failure", correlationId);
    }

    /**
     * Выполняет компенсирующие действия при неудаче саги.
     */
    private void performCompensation(Booking booking, String correlationId) {
        try {
            Map<String, String> releasePayload = Map.of("requestId", booking.getRequestId());
            callHotelService(HOTEL_RELEASE_PATH, booking.getRoomId(), releasePayload, correlationId)
                .block(timeout);
            logger.info("[{}] Compensation: room released successfully", correlationId);
        } catch (Exception compensationException) {
            logger.error("[{}] Compensation failed: {}", correlationId, compensationException.getMessage());
            // В реальной системе здесь может быть алерт или retry механизм
        }
    }

    /**
     * Вызывает Hotel Service с retry и timeout.
     */
    private Mono<String> callHotelService(String pathTemplate, Long roomId, 
                                        Map<String, String> payload, String correlationId) {
        String path = pathTemplate.replace("{roomId}", roomId.toString());
        
        return hotelWebClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .header(CORRELATION_HEADER, correlationId)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxRetries, RETRY_BACKOFF).maxBackoff(MAX_BACKOFF))
                .doOnSubscribe(sub -> logger.debug("[{}] Calling Hotel Service: {}", correlationId, path))
                .doOnSuccess(response -> logger.debug("[{}] Hotel Service response: {}", correlationId, response))
                .doOnError(error -> logger.error("[{}] Hotel Service call failed: {}", correlationId, error.getMessage()));
    }

    /**
     * Генерирует идентификатор корреляции для трассировки.
     */
    private String generateCorrelationId() {
        return "booking-" + UUID.randomUUID().toString();
    }

    /**
     * Получает рекомендации по номерам с сортировкой по статистике бронирований.
     */
    public Mono<List<RoomSuggestion>> getRoomSuggestions() {
        return hotelWebClient.get()
                .uri("/hotels/rooms")
                .retrieve()
                .bodyToFlux(RoomSuggestion.class)
                .collectList()
                .map(this::sortRoomsByBookingStatistics)
                .doOnSuccess(rooms -> logger.debug("Retrieved {} room suggestions", rooms.size()))
                .doOnError(error -> logger.error("Failed to get room suggestions: {}", error.getMessage()));
    }

    /**
     * Сортирует комнаты по статистике бронирований (алгоритм равномерного распределения).
     */
    private List<RoomSuggestion> sortRoomsByBookingStatistics(List<RoomSuggestion> rooms) {
        return rooms.stream()
                .sorted(Comparator
                    .comparingLong(RoomSuggestion::timesBooked)
                    .thenComparing(RoomSuggestion::id))
                .toList();
    }

    /**
     * DTO для представления комнаты с статистикой бронирований.
     */
    public record RoomSuggestion(Long id, String number, long timesBooked) {}
}
