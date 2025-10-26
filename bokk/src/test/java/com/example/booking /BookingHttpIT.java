package com.example.booking;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Интеграционный тест для Booking Service.
 * Тестирует полный HTTP поток бронирования с мокированием Hotel Service через WireMock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@ContextConfiguration(initializers = BookingHttpIT.WireMockInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingHttpIT {

    private static final String JWT_SECRET = "test-jwt-secret-for-integration-tests-min-32-chars";
    private static final String HOTEL_HOLD_PATH = "/rooms/\\d+/hold";
    private static final String HOTEL_CONFIRM_PATH = "/rooms/\\d+/confirm";
    private static final String HOTEL_RELEASE_PATH = "/rooms/\\d+/release";
    
    private static final Long TEST_USER_ID = 100L;
    private static final Long TEST_ROOM_ID = 1L;
    private static final String TEST_USERNAME = "it-user";

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
                "hotel.service.timeout-ms=2000",
                "hotel.service.max-retries=2",
                "security.jwt.secret=" + JWT_SECRET
            ).applyTo(context.getEnvironment());
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    /**
     * Создает JWT токен для тестового пользователя.
     */
    private String createTestUserToken() {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        
        return Jwts.builder()
                .setSubject(TEST_USER_ID.toString())
                .claim("scope", "USER")
                .claim("username", TEST_USERNAME)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    /**
     * Создает JWT токен для тестового администратора.
     */
    private String createTestAdminToken() {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        
        return Jwts.builder()
                .setSubject("101")
                .claim("scope", "ADMIN")
                .claim("username", "it-admin")
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }

    @BeforeAll
    static void setupWireMock() {
        WireMock.configureFor("localhost", WireMockInitializer.wireMockServer.port());
    }

    @BeforeEach
    void resetWireMockStubs() {
        WireMockInitializer.wireMockServer.resetAll();
    }

    @AfterAll
    static void cleanup() {
        WireMockInitializer.wireMockServer.stop();
    }

    @Test
    @DisplayName("Успешное создание бронирования - полный сценарий саги")
    void createBooking_WhenHotelServiceSucceeds_ShouldReturnConfirmedBooking() {
        // Given
        String requestId = UUID.randomUUID().toString();
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = LocalDate.now().plusDays(3);
        
        stubHotelServiceCalls();

        // When & Then
        webTestClient.post().uri("/bookings")
                .header("Authorization", "Bearer " + createTestUserToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBookingRequest(requestId, TEST_ROOM_ID, startDate, endDate))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CONFIRMED")
                .jsonPath("$.userId").isEqualTo(TEST_USER_ID.intValue())
                .jsonPath("$.roomId").isEqualTo(TEST_ROOM_ID.intValue())
                .jsonPath("$.requestId").isEqualTo(requestId);
    }

    @Test
    @DisplayName("Создание бронирования с идемпотентностью - возвращает существующее бронирование")
    void createBooking_WithDuplicateRequestId_ShouldReturnExistingBooking() {
        // Given
        String requestId = UUID.randomUUID().toString();
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = LocalDate.now().plusDays(2);
        
        stubHotelServiceCalls();

        // First request
        webTestClient.post().uri("/bookings")
                .header("Authorization", "Bearer " + createTestUserToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBookingRequest(requestId, TEST_ROOM_ID, startDate, endDate))
                .exchange()
                .expectStatus().isCreated();

        // Second request with same requestId
        webTestClient.post().uri("/bookings")
                .header("Authorization", "Bearer " + createTestUserToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBookingRequest(requestId, TEST_ROOM_ID, startDate, endDate))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Создание бронирования при ошибке Hotel Service - должна сработать компенсация")
    void createBooking_WhenHotelServiceFails_ShouldCancelBookingWithCompensation() {
        // Given
        String requestId = UUID.randomUUID().toString();
        
        // Hotel hold succeeds but confirm fails
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_HOLD_PATH))
                .willReturn(okJson("{}"))
        );
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_CONFIRM_PATH))
                .willReturn(serverError())
        );
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_RELEASE_PATH))
                .willReturn(okJson("{}"))
        );

        // When & Then
        webTestClient.post().uri("/bookings")
                .header("Authorization", "Bearer " + createTestUserToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBookingRequest(requestId, TEST_ROOM_ID, 
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(3)))
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.error").isNotEmpty();
    }

    @Test
    @DisplayName("Получение списка бронирований пользователя - должен вернуть только свои бронирования")
    void getMyBookings_WhenUserHasBookings_ShouldReturnUserBookingsOnly() {
        // Given
        webTestClient.post().uri("/bookings")
                .header("Authorization", "Bearer " + createTestUserToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createBookingRequest(UUID.randomUUID().toString(), TEST_ROOM_ID,
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(2)))
                .exchange()
                .expectStatus().isCreated();

        // When & Then
        webTestClient.get().uri("/bookings")
                .header("Authorization", "Bearer " + createTestUserToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.bookings.length()").isEqualTo(1)
                .jsonPath("$.bookings[0].userId").isEqualTo(TEST_USER_ID.intValue());
    }

    @Test
    @DisplayName("Получение всех бронирований администратором - должен вернуть все бронирования")
    void getAllBookings_WhenAdminUser_ShouldReturnAllBookings() {
        // When & Then
        webTestClient.get().uri("/bookings/all")
                .header("Authorization", "Bearer " + createTestAdminToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.bookings").isArray();
    }

    @Test
    @DisplayName("Получение всех бронирований обычным пользователем - должен вернуть 403")
    void getAllBookings_WhenRegularUser_ShouldReturnForbidden() {
        // When & Then
        webTestClient.get().uri("/bookings/all")
                .header("Authorization", "Bearer " + createTestUserToken())
                .exchange()
                .expectStatus().isForbidden();
    }

    // Вспомогательные методы

    private void stubHotelServiceCalls() {
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_HOLD_PATH))
                .willReturn(okJson("{}"))
        );
        WireMockInitializer.wireMockServer.stubFor(
            post(urlPathMatching(HOTEL_CONFIRM_PATH))
                .willReturn(okJson("{}"))
        );
    }

    private Map<String, Object> createBookingRequest(String requestId, Long roomId, 
                                                   LocalDate startDate, LocalDate endDate) {
        return Map.of(
            "roomId", roomId,
            "startDate", startDate.toString(),
            "endDate", endDate.toString(),
            "requestId", requestId
        );
    }
}
