package com.example.hotel;

import com.example.hotel.model.Hotel;
import com.example.hotel.model.Room;
import com.example.hotel.model.RoomReservationLock;
import com.example.hotel.repo.HotelRepository;
import com.example.hotel.repo.RoomRepository;
import com.example.hotel.repo.RoomReservationLockRepository;
import com.example.hotel.service.HotelService;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты для проверки доступности номеров и работы распределенных саг.
 * Тестирует основные сценарии блокировки, подтверждения и освобождения номеров
 * с акцентом на идемпотентность и согласованность данных.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class HotelAvailabilityTests {

    private static final Logger logger = LoggerFactory.getLogger(HotelAvailabilityTests.class);

    @Autowired
    private HotelRepository hotelRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomReservationLockRepository lockRepository;

    @Autowired
    private HotelService hotelService;

    private Hotel testHotel;
    private Room testRoom;

    @BeforeEach
    void setUp() {
        // Очистка данных перед каждым тестом
        lockRepository.deleteAll();
        roomRepository.deleteAll();
        hotelRepository.deleteAll();

        // Создание тестовых данных
        testHotel = createTestHotel();
        testRoom = createTestRoom();
        
        logger.debug("Test setup completed - Hotel: {}, Room: {}", 
                   testHotel.getName(), testRoom.getNumber());
    }

    @Test
    @DisplayName("Полный поток саги: блокировка → подтверждение → проверка идемпотентности")
    void holdConfirmRelease_FullSagaFlow_ShouldBeIdempotent() {
        // Given
        String requestId = "saga-flow-test-1";
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(2);

        logger.info("Starting full saga flow test - requestId: {}, dates: {} to {}", 
                   requestId, startDate, endDate);

        // When - Step 1: Блокировка номера
        RoomReservationLock lock1 = hotelService.holdRoom(requestId, testRoom.getId(), startDate, endDate);
        
        // Then - Step 1
        assertNotNull(lock1, "Lock should be created");
        assertEquals(RoomReservationLock.LockStatus.HELD, lock1.getStatus());
        assertEquals(requestId, lock1.getRequestId());
        assertEquals(testRoom.getId(), lock1.getRoomId());
        assertEquals(startDate, lock1.getStartDate());
        assertEquals(endDate, lock1.getEndDate());
        logger.debug("Step 1 - Room hold successful: lockId={}", lock1.getId());

        // When - Step 2: Повторная блокировка (идемпотентность)
        RoomReservationLock lock2 = hotelService.holdRoom(requestId, testRoom.getId(), startDate, endDate);
        
        // Then - Step 2
        assertEquals(lock1.getId(), lock2.getId(), "Idempotency: same requestId should return same lock");
        assertEquals(RoomReservationLock.LockStatus.HELD, lock2.getStatus());
        logger.debug("Step 2 - Idempotency check passed");

        // When - Step 3: Подтверждение блокировки
        RoomReservationLock confirmedLock1 = hotelService.confirmHold(requestId);
        
        // Then - Step 3
        assertEquals(RoomReservationLock.LockStatus.CONFIRMED, confirmedLock1.getStatus());
        assertEquals(lock1.getId(), confirmedLock1.getId());
        
        // Проверяем, что счетчик бронирований увеличился
        Room updatedRoom = roomRepository.findById(testRoom.getId()).orElseThrow();
        assertEquals(1L, updatedRoom.getTimesBooked(), "Times booked should be incremented");
        logger.debug("Step 3 - Room confirmation successful");

        // When - Step 4: Повторное подтверждение (идемпотентность)
        RoomReservationLock confirmedLock2 = hotelService.confirmHold(requestId);
        
        // Then - Step 4
        assertEquals(RoomReservationLock.LockStatus.CONFIRMED, confirmedLock2.getStatus());
        assertEquals(confirmedLock1.getId(), confirmedLock2.getId());
        logger.debug("Step 4 - Confirmation idempotency check passed");

        // When - Step 5: Попытка освобождения после подтверждения (no-op)
        RoomReservationLock releasedLock = hotelService.releaseHold(requestId);
        
        // Then - Step 5
        assertEquals(RoomReservationLock.LockStatus.CONFIRMED, releasedLock.getStatus(), 
                    "Release after confirm should be no-op");
        logger.debug("Step 5 - Release after confirm is correctly no-op");

        // Verify final state
        assertEquals(1L, updatedRoom.getTimesBooked(), "Times booked should remain 1");
        logger.info("Full saga flow test completed successfully");
    }

    @Test
    @DisplayName("Компенсирующий поток саги: блокировка → освобождение при ошибке")
    void holdRelease_CompensationFlow_ShouldReleaseLock() {
        // Given
        String requestId = "compensation-flow-test-1";
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(1);

        logger.info("Starting compensation flow test - requestId: {}", requestId);

        // When - Step 1: Блокировка номера
        RoomReservationLock lock = hotelService.holdRoom(requestId, testRoom.getId(), startDate, endDate);
        
        // Then - Step 1
        assertEquals(RoomReservationLock.LockStatus.HELD, lock.getStatus());
        logger.debug("Step 1 - Room hold successful");

        // When - Step 2: Освобождение блокировки (компенсация)
        RoomReservationLock releasedLock = hotelService.releaseHold(requestId);
        
        // Then - Step 2
        assertEquals(RoomReservationLock.LockStatus.RELEASED, releasedLock.getStatus());
        assertEquals(lock.getId(), releasedLock.getId());
        logger.debug("Step 2 - Room release successful");

        // When - Step 3: Повторное освобождение (идемпотентность)
        RoomReservationLock releasedLock2 = hotelService.releaseHold(requestId);
        
        // Then - Step 3
        assertEquals(RoomReservationLock.LockStatus.RELEASED, releasedLock2.getStatus());
        assertEquals(releasedLock.getId(), releasedLock2.getId());
        logger.debug("Step 3 - Release idempotency check passed");

        // Verify room statistics unchanged
        Room room = roomRepository.findById(testRoom.getId()).orElseThrow();
        assertEquals(0L, room.getTimesBooked(), "Times booked should remain 0 for released lock");
        logger.info("Compensation flow test completed successfully");
    }

    @Test
    @DisplayName("Конфликт блокировок: предотвращение двойного бронирования")
    void holdRoom_WithConflictingDates_ShouldThrowException() {
        // Given
        String requestId1 = "conflict-test-1";
        String requestId2 = "conflict-test-2";
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(2);

        logger.info("Starting conflict test - dates: {} to {}", startDate, endDate);

        // When - Первая блокировка
        RoomReservationLock firstLock = hotelService.holdRoom(requestId1, testRoom.getId(), startDate, endDate);
        assertNotNull(firstLock);
        logger.debug("First lock created: {}", firstLock.getId());

        // Then - Вторая блокировка должна вызвать конфликт
        IllegalStateException exception = assertThrows(IllegalStateException.class, 
            () -> hotelService.holdRoom(requestId2, testRoom.getId(), startDate, endDate),
            "Should throw exception for conflicting dates"
        );

        // Verify exception message
        assertTrue(exception.getMessage().contains("not available") || 
                  exception.getMessage().contains("conflict"),
                  "Exception should indicate room is not available");
        logger.debug("Conflict detected correctly: {}", exception.getMessage());

        // Verify only one lock exists
        List<RoomReservationLock> allLocks = lockRepository.findAll();
        assertEquals(1, allLocks.size(), "Should have only one lock despite conflict attempt");
        logger.info("Conflict test completed successfully");
    }

    @Test
    @DisplayName("Блокировка с невалидными параметрами: должна выбрасывать исключение")
    void holdRoom_WithInvalidParameters_ShouldThrowException() {
        // Given
        String requestId = "invalid-params-test";
        LocalDate startDate = LocalDate.now().minusDays(1); // Дата в прошлом
        LocalDate endDate = LocalDate.now().plusDays(1);

        logger.info("Starting invalid parameters test");

        // When & Then - Невалидная дата начала
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> hotelService.holdRoom(requestId, testRoom.getId(), startDate, endDate),
            "Should throw exception for past start date"
        );

        assertTrue(exception.getMessage().contains("past") || 
                  exception.getMessage().contains("invalid"),
                  "Exception should mention date validation");
        logger.debug("Invalid parameters handled correctly: {}", exception.getMessage());
    }

    @Test
    @DisplayName("Подтверждение несуществующей блокировки: должно выбрасывать исключение")
    void confirmHold_WithNonExistentRequestId_ShouldThrowException() {
        // Given
        String nonExistentRequestId = "non-existent-request-123";

        logger.info("Starting non-existent lock test");

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> hotelService.confirmHold(nonExistentRequestId),
            "Should throw exception for non-existent lock"
        );

        assertTrue(exception.getMessage().contains("not found") ||
                  exception.getMessage().contains("exist"),
                  "Exception should indicate lock not found");
        logger.debug("Non-existent lock handled correctly: {}", exception.getMessage());
    }

    @Test
    @DisplayName("Освобождение несуществующей блокировки: должно выбрасывать исключение")
    void releaseHold_WithNonExistentRequestId_ShouldThrowException() {
        // Given
        String nonExistentRequestId = "non-existent-release-123";

        logger.info("Starting non-existent release test");

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> hotelService.releaseHold(nonExistentRequestId),
            "Should throw exception for non-existent lock"
        );

        assertTrue(exception.getMessage().contains("not found") ||
                  exception.getMessage().contains("exist"),
                  "Exception should indicate lock not found");
        logger.debug("Non-existent release handled correctly: {}", exception.getMessage());
    }

    @Test
    @DisplayName("Блокировка недоступного номера: должна выбрасывать исключение")
    void holdRoom_ForUnavailableRoom_ShouldThrowException() {
        // Given
        Room unavailableRoom = createUnavailableRoom();
        String requestId = "unavailable-room-test";
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(1);

        logger.info("Starting unavailable room test");

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> hotelService.holdRoom(requestId, unavailableRoom.getId(), startDate, endDate),
            "Should throw exception for unavailable room"
        );

        assertTrue(exception.getMessage().contains("not available") ||
                  exception.getMessage().contains("unavailable"),
                  "Exception should indicate room is not available");
        logger.debug("Unavailable room handled correctly: {}", exception.getMessage());
    }

    @Test
    @DisplayName("Перекрывающиеся даты блокировок: должны правильно определять конфликты")
    void holdRoom_WithOverlappingDates_ShouldDetectConflicts() {
        // Given
        String requestId1 = "overlap-test-1";
        String requestId2 = "overlap-test-2";
        LocalDate baseDate = LocalDate.now().plusDays(1);

        // Первая блокировка: 1-3 числа
        RoomReservationLock firstLock = hotelService.holdRoom(
            requestId1, testRoom.getId(), baseDate, baseDate.plusDays(2));
        assertNotNull(firstLock);
        logger.debug("First lock created for dates: {} to {}", baseDate, baseDate.plusDays(2));

        // Test cases for overlapping dates
        LocalDate[][] overlappingCases = {
            {baseDate, baseDate.plusDays(1)},           // Полное перекрытие
            {baseDate.minusDays(1), baseDate.plusDays(1)}, // Начало до, конец во время
            {baseDate.plusDays(1), baseDate.plusDays(3)},  // Начало во время, конец после
            {baseDate.minusDays(1), baseDate.plusDays(3)}  // Полное покрытие
        };

        for (int i = 0; i < overlappingCases.length; i++) {
            LocalDate[] dates = overlappingCases[i];
            String testRequestId = requestId2 + "-" + i;

            logger.debug("Testing overlap case {}: {} to {}", i, dates[0], dates[1]);

            // When & Then - Каждый перекрывающий случай должен вызвать конфликт
            assertThrows(IllegalStateException.class,
                () -> hotelService.holdRoom(testRequestId, testRoom.getId(), dates[0], dates[1]),
                "Should throw exception for overlapping dates case " + i
            );
        }

        logger.info("All overlapping date cases handled correctly");
    }

    // ==================== HELPER METHODS ====================

    /**
     * Создает тестовый отель.
     */
    private Hotel createTestHotel() {
        Hotel hotel = Hotel.builder()
                .name("Grand Test Hotel")
                .city("Test City")
                .address("123 Test Street")
                .phoneNumber("+1-555-TEST")
                .description("A test hotel for integration tests")
                .starRating(4)
                .build();
        
        return hotelRepository.save(hotel);
    }

    /**
     * Создает тестовый номер.
     */
    private Room createTestRoom() {
        Room room = Room.builder()
                .number("101")
                .capacity(2)
                .type(Room.RoomType.STANDARD)
                .pricePerNight(java.math.BigDecimal.valueOf(150.00))
                .description("Standard test room")
                .hasWiFi(true)
                .hasAirConditioning(true)
                .hasTV(true)
                .hasMiniBar(false)
                .hotel(testHotel)
                .build();
        
        return roomRepository.save(room);
    }

    /**
     * Создает недоступный номер для тестирования.
     */
    private Room createUnavailableRoom() {
        Room room = Room.builder()
                .number("999")
                .capacity(1)
                .type(Room.RoomType.SUITE)
                .pricePerNight(java.math.BigDecimal.valueOf(300.00))
                .hotel(testHotel)
                .build();
        
        room.setAvailable(false);
        room.setStatus(Room.RoomStatus.MAINTENANCE);
        
        return roomRepository.save(room);
    }

    @AfterEach
    void tearDown() {
        // Данные автоматически очищаются благодаря @Transactional
        logger.debug("Test teardown completed");
    }
}
