package com.example.booking;

import com.example.booking.m.Booking;
import com.example.booking.repositor.BookingRepository;
import com.example.booking.service.BookingService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты для BookingService.
 * Тестирует бизнес-логику сервиса бронирований с мокированием внешних зависимостей.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = BookingServiceTests.WireMockInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingServiceTests {

    private static final String HOTEL_HOLD_PATH = "/rooms/\\d+/hold";
    private static final String HOTEL_CONFIRM_PATH = "/rooms/\\d+/confirm";
    private static final String HOTEL_RELEASE_PATH = "/rooms/\\d+/release";
    private static final String HOTEL_ROOMS_PATH = "/hotels/rooms";

    /**
     * Инициализатор WireMock для тестового окружения.
     */
    static class WireMockInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        
        static final WireMockServer wireMockServer = new WireMockServer(0);

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            wireMockServer.start();
            int port = wireMockServer.port();
            
            TestPropertyValues.of(
                "hotel.service.base-url=http://localhost:" + port,
                "hotel.service.timeout-ms=1000",
                "hotel.service.max-retries=1"
            ).applyTo(context.getEnvironment());
        }
    }

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @BeforeAll
    static void setupWireMock() {
        WireMock.configureFor("localhost", WireMockInitializer.wireMockServer.port());
    }

    @BeforeEach
    void setUp() {
        WireMockInitializer.wireMockServer.resetAll();
        bookingRepository.deleteAll(); // Очистка базы перед каждым тестом
    }

    @AfterAll
    static void tearDown() {
        WireMockInitializer.wireMockServer.stop();
    }

    @Test
    @DisplayName("Успешный поток бронирования - создает подтвержденное бронирование")
    void createBooking_WhenHotelServiceSucceeds_ShouldReturnConfirmedBooking() {
        // Given
        stubSuccessfulHotelServiceCalls();
        Long userId = 1L;
        Long roomId = 10L;
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(2);
        String requestId = "test-success-request";

        // When
        Booking result = bookingService.createBooking(userId, roomId, startDate, endDate, requestId);

        // Then
        assertNotNull(result);
        assertEquals(Booking.BookingStatus.CONFIRMED, result.getStatus());
        assertEquals(userId, result.getUserId());
        assertEquals(roomId, result.getRoomId());
        assertEquals(requestId, result.getRequestId());
        assertNotNull(result.getCorrelationId());
        
        // Verify booking is persisted
        Optional<Booking> persistedBooking = bookingRepository.findById(result.getId());
        assertTrue(persistedBooking.isPresent());
        assertEquals(Booking.BookingStatus.CONFIRMED, persistedBooking.get().getStatus());
    }

    @Test
    @DisplayName("Поток с ошибкой Hotel Service - создает отмененное бронирование с компенсацией")
    void createBooking_WhenHotelServiceHoldFails_ShouldReturnCancelledBookingWithCompensation() {
        // Given
        stubHotelServiceHoldFailure();
        Long userId = 2L;
        Long roomId = 11L;
        String requestId = "test-failure-request";

        // When
        Booking result = bookingService.createBooking(userId, roomId, 
            LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), requestId);

        // Then
        assertNotNull(result);
        assertEquals(Booking.BookingStatus.CANCELLED, result.getStatus());
        
        // Verify compensation call was made
        WireMockInitializer.wireMockServer.verify(
            postRequestedFor(urlPathMatching(HOTEL_RELEASE_PATH))
        );
    }

    @Test
    @DisplayName("Поток с таймаутом Hotel Service - создает отмененное бронирование")
    void createBooking_WhenHotelServiceTimesOut_ShouldReturnCancelledBooking() {
        // Given
        stubHotelServiceTimeout();
        Long userId = 3L;
        Long roomId = 12L;
        String requestId = "test-timeout-request";

        // When
        Booking result = bookingService.createBooking(userId, roomId,
            LocalDate.now().plusDays(1), LocalDate.now().plusDays(1), requestId);

        // Then
        assertNotNull(result);
        assertEquals(Booking.BookingStatus.CANCELLED, result.getStatus());
    }

    @Test
    @DisplayName("Идемпотентность - дублирующий запрос возвращает существующее бронирование")
    void createBooking_WithDuplicateRequestId_ShouldReturnExistingBooking() {
        // Given
        stubSuccessfulHotelServiceCalls();
        Long userId = 4L;
        Long roomId = 13L;
        String requestId = "test-idempotent-request";

        // First call
        Booking firstBooking = bookingService.createBooking(userId, roomId,
            LocalDate.now().plusDays(1), LocalDate.now().plusDays(1), requestId);

        // When - Second call with same requestId
        Booking secondBooking = bookingService.createBooking(userId, roomId,
            LocalDate.now().plusDays(1), LocalDate.now().plusDays(1), requestId);

        // Then
        assertNotNull(secondBooking);
        assertEquals(firstBooking.getId(), secondBooking.getId());
        assertEquals(firstBooking.getRequestId(), secondBooking.getRequestId());
        
        // Verify Hotel Service was called only once (for the first request)
        WireMockInitializer.wireMockServer.verify(1, 
            postRequestedFor(urlPathMatching(HOTEL_HOLD_PATH))
        );
        WireMockInitializer.wireMockServer.verify(1,
            postRequestedFor(urlPathMatching(HOTEL_CONFIRM_PATH))
        );
    }

    @Test
    @DisplayName("Получение рекомендаций по номерам - возвращает отсортированный список")
    void getRoomSuggestions_WhenHotelServiceReturnsRooms_ShouldReturnSortedList() {
        // Given
        String roomsJson = """
            [
                {"id":1, "number":"101", "timesBooked":5},
                {"id":2, "number":"102", "timesBooked":1},
                {"id":3, "number":"103", "timesBooked":3}
            ]
            """;
        
        WireMockInitializer.wireMockServer.stubFor(
            get(urlEqualTo(HOTEL_ROOMS_PATH))
                .willReturn(okJson(roomsJson))
        );

        // When
        List<BookingService.RoomSuggestion> result = bookingService.getRoomSuggestions().block();

        // Then
        assertNotNull(result);
        assertEquals(3, result.size());
        
        // Verify sorting: by timesBooked ascending, then by id
        assertEquals(2L, result.get(0).id());  // timesBooked: 1
        assertEquals(3L, result.get(1).id());  // timesBooked: 3  
        assertEquals(1L, result.get(2).id());  // timesBooked: 5
        
        assertEquals("102", result.get(0).number());
        assertEquals(1L, result.get(0).timesBooked());
    }

    @Test
    @DisplayName("Создание бронирования с невалидными параметрами - выбрасывает исключение")
    void createBooking_WithInvalidParameters_ShouldThrowException() {
        // Given
        Long userId = 5L;
        Long roomId = 14L;
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = LocalDate.now(); // end before start

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            bookingService.createBooking(userId, roomId, startDate, endDate, "test-invalid");
        });
    }

    @Test
    @DisplayName("Создание бронирования в прошлом - выбрасывает исключение")
    void createBooking_WithPastStartDate_ShouldThrowException() {
        // Given
        Long userId = 6L;
        Long roomId = 15L;
        LocalDate startDate = LocalDate.now().minusDays(1); // past date
        LocalDate endDate = LocalDate.now().plusDays(1);

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            bookingService.createBooking(userId, roomId, startDate, endDate, "test-past-date");
        });
    }

    // Вспомогательные методы для настройки WireMock

    private void stubSuccessfulHotelServiceCalls() {
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_HOLD_PATH))
                .willReturn(okJson("{\"status\":\"held\"}"))
        );
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_CONFIRM_PATH))
                .willReturn(okJson("{\"status\":\"confirmed\"}"))
        );
    }

    private void stubHotelServiceHoldFailure() {
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_HOLD_PATH))
                .willReturn(serverError())
        );
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_RELEASE_PATH))
                .willReturn(okJson("{\"status\":\"released\"}"))
        );
    }

    private void stubHotelServiceTimeout() {
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_HOLD_PATH))
                .willReturn(aResponse().withFixedDelay(2000).withStatus(200))
        );
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_RELEASE_PATH))
                .willReturn(okJson("{\"status\":\"released\"}"))
        );
    }
}
