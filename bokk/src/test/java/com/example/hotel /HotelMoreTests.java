package com.example.hotel;

import com.example.hotel.m.Hotel;
import com.example.hotel.m.Room;
import com.example.hotel.m.RoomReservationLock;
import com.example.hotel.repositor.HotelRepository;
import com.example.hotel.repositor.RoomRepository;
import com.example.hotel.service.HotelService;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Дополнительные интеграционные тесты для проверки сложных сценариев Hotel Service.
 * Тестирует конфликты дат, флаги доступности, статистику и edge cases.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class HotelMoreTests {

    private static final Logger logger = LoggerFactory.getLogger(HotelMoreTests.class);

    @Autowired
    private HotelRepository hotelRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private HotelService hotelService;

    private Hotel testHotel;
    private Room testRoom1;
    private Room testRoom2;

    @BeforeEach
    void setUp() {
        // Очистка и подготовка тестовых данных
        hotelRepository.deleteAll();
        roomRepository.deleteAll();

        testHotel = createTestHotel("Complex Test Hotel", "Test City");
        testRoom1 = createTestRoom("301", 2, true);
        testRoom2 = createTestRoom("302", 3, true);
        
        logger.debug("Test setup completed - Hotel: {}, Rooms: {}, {}", 
                   testHotel.getName(), testRoom1.getNumber(), testRoom2.getNumber());
    }

    @Test
    @DisplayName("Конфликт дат бронирования: должен выбрасывать исключение (аналог HTTP 409)")
    void holdRoom_WithDateConflict_ShouldThrowIllegalStateException() {
        // Given
        LocalDate startDate1 = LocalDate.now().plusDays(1);
        LocalDate endDate1 = startDate1.plusDays(2);
        String requestId1 = "conflict-test-primary";
        String requestId2 = "conflict-test-overlap";

        logger.info("Testing date conflict detection - dates: {} to {}", startDate1, endDate1);

        // When - Первая блокировка
        RoomReservationLock firstLock = hotelService.holdRoom(requestId1, testRoom1.getId(), startDate1, endDate1);
        assertNotNull(firstLock, "First lock should be created successfully");
        assertEquals(RoomReservationLock.LockStatus.HELD, firstLock.getStatus());
        logger.debug("First lock created: {} for room {}", firstLock.getId(), testRoom1.getId());

        // Then - Вторая блокировка с пересекающимися датами должна вызвать конфликт
        LocalDate overlappingStart = startDate1.plusDays(1); // Начинается во время первой блокировки
        LocalDate overlappingEnd = endDate1.plusDays(1);     // Заканчивается после первой блокировки

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> hotelService.holdRoom(requestId2, testRoom1.getId(), overlappingStart, overlappingEnd),
            "Should throw IllegalStateException for overlapping dates"
        );

        // Verify exception message indicates conflict
        assertTrue(exception.getMessage().toLowerCase().contains("not available") ||
                  exception.getMessage().toLowerCase().contains("conflict") ||
                  exception.getMessage().toLowerCase().contains("unavailable"),
                  "Exception message should indicate room is not available. Actual: " + exception.getMessage());

        logger.info("Date conflict correctly detected and prevented: {}", exception.getMessage());

        // Verify only one lock exists despite conflict attempt
        List<RoomReservationLock> allLocks = hotelService.listAllLocks();
        assertEquals(1, allLocks.size(), "Should have only one lock despite conflict attempt");
        assertEquals(requestId1, allLocks.get(0).getRequestId(), "Only the first lock should exist");
    }

    @Test
    @DisplayName("Различные сценарии конфликтов дат: полное и частичное перекрытие")
    void holdRoom_WithVariousDateConflicts_ShouldDetectAllConflicts() {
        // Given - Базовая блокировка
        LocalDate baseStart = LocalDate.now().plusDays(1);
        LocalDate baseEnd = baseStart.plusDays(3);
        String baseRequestId = "base-booking";
        
        RoomReservationLock baseLock = hotelService.holdRoom(baseRequestId, testRoom1.getId(), baseStart, baseEnd);
        assertNotNull(baseLock);
        logger.debug("Base lock created for dates: {} to {}", baseStart, baseEnd);

        // Test cases for different conflict scenarios
        Object[][] conflictScenarios = {
            // {scenarioName, conflictStart, conflictEnd, shouldConflict}
            {"Complete overlap", baseStart, baseEnd, true},
            {"Start inside", baseStart.plusDays(1), baseEnd.plusDays(1), true},
            {"End inside", baseStart.minusDays(1), baseEnd.minusDays(1), true},
            {"Complete containment", baseStart.plusDays(1), baseEnd.minusDays(1), true},
            {"Same dates", baseStart, baseEnd, true},
            {"Before (no conflict)", baseStart.minusDays(2), baseStart.minusDays(1), false},
            {"After (no conflict)", baseEnd.plusDays(1), baseEnd.plusDays(2), false},
            {"Adjacent end-start (no conflict)", baseEnd, baseEnd.plusDays(1), false},
            {"Adjacent start-end (no conflict)", baseStart.minusDays(1), baseStart, false}
        };

        for (Object[] scenario : conflictScenarios) {
            String scenarioName = (String) scenario[0];
            LocalDate conflictStart = (LocalDate) scenario[1];
            LocalDate conflictEnd = (LocalDate) scenario[2];
            boolean shouldConflict = (boolean) scenario[3];
            
            String testRequestId = "test-" + scenarioName.replace(" ", "-").toLowerCase();
            
            logger.debug("Testing scenario: {} - dates: {} to {}", scenarioName, conflictStart, conflictEnd);

            if (shouldConflict) {
                // Should throw exception for conflicting scenarios
                assertThrows(IllegalStateException.class,
                    () -> hotelService.holdRoom(testRequestId, testRoom1.getId(), conflictStart, conflictEnd),
                    "Scenario '" + scenarioName + "' should cause conflict"
                );
                logger.debug("Conflict correctly detected for scenario: {}", scenarioName);
            } else {
                // Should succeed for non-conflicting scenarios
                assertDoesNotThrow(() -> {
                    RoomReservationLock lock = hotelService.holdRoom(testRequestId, testRoom1.getId(), conflictStart, conflictEnd);
                    assertNotNull(lock);
                    logger.debug("No conflict for scenario: {} - lock created: {}", scenarioName, lock.getId());
                }, "Scenario '" + scenarioName + "' should not cause conflict");
            }
        }

        logger.info("All date conflict scenarios tested successfully");
    }

    @Test
    @DisplayName("Флаг available=false не влияет на логику занятости по датам")
    void holdRoom_WhenRoomNotAvailable_ShouldStillAllowDateBasedLocking() {
        // Given - Комната с available=false
        Room unavailableRoom = createTestRoom("999", 1, false);
        unavailableRoom.setAvailable(false);
        unavailableRoom.setStatus(Room.RoomStatus.MAINTENANCE);
        roomRepository.save(unavailableRoom);

        String requestId = "unavailable-room-test";
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(2);

        logger.info("Testing lock creation for unavailable room - room: {}", unavailableRoom.getNumber());

        // When & Then - Блокировка должна создаваться независимо от флага available
        // Это может быть спорным поведением, но тестируем существующую логику
        RoomReservationLock lock = hotelService.holdRoom(requestId, unavailableRoom.getId(), startDate, endDate);
        
        assertNotNull(lock, "Lock should be created even for unavailable room");
        assertEquals(RoomReservationLock.LockStatus.HELD, lock.getStatus());
        assertEquals(unavailableRoom.getId(), lock.getRoomId());
        assertEquals(requestId, lock.getRequestId());

        logger.info("Lock created for unavailable room - this tests current business logic");

        // Verify the room is still marked as unavailable
        Room refreshedRoom = roomRepository.findById(unavailableRoom.getId()).orElseThrow();
        assertFalse(refreshedRoom.getAvailable(), "Room should remain unavailable");
        assertEquals(Room.RoomStatus.MAINTENANCE, refreshedRoom.getStatus(), "Room status should remain MAINTENANCE");
    }

    @Test
    @DisplayName("Статистика популярных номеров: правильная сортировка по количеству бронирований")
    void getPopularRooms_ShouldReturnRoomsSortedByTimesBooked() {
        // Given - Создаем комнаты с разным количеством бронирований
        Room roomLowBooked = createTestRoom("401", 2, true);
        Room roomMediumBooked = createTestRoom("402", 2, true); 
        Room roomHighBooked = createTestRoom("403", 2, true);

        // Имитируем разное количество подтвержденных бронирований
        simulateConfirmedBookings(roomLowBooked, 1);
        simulateConfirmedBookings(roomMediumBooked, 3);
        simulateConfirmedBookings(roomHighBooked, 5);

        logger.info("Testing popular rooms statistics - bookings: 1, 3, 5");

        // When - Получаем популярные номера
        Pageable pageable = PageRequest.of(0, 10);
        List<Room> popularRooms = roomRepository.findTopNPopularRooms(pageable);

        // Then - Проверяем сортировку по убыванию timesBooked
        assertNotNull(popularRooms, "Popular rooms list should not be null");
        assertFalse(popularRooms.isEmpty(), "Popular rooms list should not be empty");

        // Проверяем что комнаты отсортированы по timesBooked (по убыванию)
        for (int i = 0; i < popularRooms.size() - 1; i++) {
            Room current = popularRooms.get(i);
            Room next = popularRooms.get(i + 1);
            assertTrue(current.getTimesBooked() >= next.getTimesBooked(),
                "Rooms should be sorted by timesBooked in descending order. " +
                current.getNumber() + "(" + current.getTimesBooked() + ") should be >= " +
                next.getNumber() + "(" + next.getTimesBooked() + ")");
        }

        // Проверяем что комната с наибольшим количеством бронирований первая
        Optional<Room> topRoom = popularRooms.stream()
                .filter(room -> room.getTimesBooked() == 5)
                .findFirst();
                
        assertTrue(topRoom.isPresent(), "Room with 5 bookings should be in the list");
        assertEquals(roomHighBooked.getId(), topRoom.get().getId(), 
                    "Room with highest timesBooked should be first");

        logger.info("Popular rooms sorting test passed - correct order maintained");
    }

    @Test
    @DisplayName("Сброс флага available не влияет на существующие блокировки")
    void roomAvailabilityChange_ShouldNotAffectExistingLocks() {
        // Given - Создаем блокировку для доступной комнаты
        String requestId = "availability-change-test";
        LocalDate startDate = LocalDate.now().plusDays(1);
        LocalDate endDate = startDate.plusDays(2);

        RoomReservationLock lock = hotelService.holdRoom(requestId, testRoom1.getId(), startDate, endDate);
        assertNotNull(lock);
        assertEquals(RoomReservationLock.LockStatus.HELD, lock.getStatus());
        logger.debug("Initial lock created for available room");

        // When - Делаем комнату недоступной
        testRoom1.setAvailable(false);
        testRoom1.setStatus(Room.RoomStatus.MAINTENANCE);
        roomRepository.save(testRoom1);

        // Then - Существующая блокировка должна остаться активной
        RoomReservationLock refreshedLock = hotelService.getLockByRequestId(requestId).orElseThrow();
        assertEquals(RoomReservationLock.LockStatus.HELD, refreshedLock.getStatus());
        assertEquals(lock.getId(), refreshedLock.getId());

        // И мы все еще можем подтвердить блокировку
        RoomReservationLock confirmedLock = hotelService.confirmHold(requestId);
        assertEquals(RoomReservationLock.LockStatus.CONFIRMED, confirmedLock.getStatus());

        logger.info("Room availability change test passed - existing locks preserved");
    }

    @Test
    @DisplayName("Одновременные блокировки разных комнат: не должно быть конфликтов")
    void holdRoom_ForDifferentRooms_ShouldNotConflict() {
        // Given
        LocalDate sameStartDate = LocalDate.now().plusDays(1);
        LocalDate sameEndDate = sameStartDate.plusDays(2);
        String requestId1 = "diff-room-1";
        String requestId2 = "diff-room-2";

        logger.info("Testing concurrent locks for different rooms");

        // When - Блокировки для разных комнат на одинаковые даты
        RoomReservationLock lock1 = hotelService.holdRoom(requestId1, testRoom1.getId(), sameStartDate, sameEndDate);
        RoomReservationLock lock2 = hotelService.holdRoom(requestId2, testRoom2.getId(), sameStartDate, sameEndDate);

        // Then - Обе блокировки должны быть созданы успешно
        assertNotNull(lock1, "First lock should be created");
        assertNotNull(lock2, "Second lock should be created");
        assertEquals(testRoom1.getId(), lock1.getRoomId());
        assertEquals(testRoom2.getId(), lock2.getRoomId());
        assertEquals(RoomReservationLock.LockStatus.HELD, lock1.getStatus());
        assertEquals(RoomReservationLock.LockStatus.HELD, lock2.getStatus());

        logger.info("Concurrent locks for different rooms test passed - no false conflicts");
    }

    // ==================== HELPER METHODS ====================

    /**
     * Создает тестовый отель.
     */
    private Hotel createTestHotel(String name, String city) {
        Hotel hotel = Hotel.builder()
                .name(name)
                .city(city)
                .address("123 Test Street")
                .phoneNumber("+1-555-TEST")
                .description("Test hotel for complex scenarios")
                .starRating(4)
                .build();

        return hotelRepository.save(hotel);
    }

    /**
     * Создает тестовый номер.
     */
    private Room createTestRoom(String number, int capacity, boolean available) {
        Room room = Room.builder()
                .number(number)
                .capacity(capacity)
                .type(Room.RoomType.STANDARD)
                .pricePerNight(java.math.BigDecimal.valueOf(100.00))
                .description("Test room " + number)
                .hasWiFi(true)
                .hasAirConditioning(true)
                .hasTV(true)
                .hasMiniBar(false)
                .hotel(testHotel)
                .build();

        room.setAvailable(available);
        if (!available) {
            room.setStatus(Room.RoomStatus.MAINTENANCE);
        }

        return roomRepository.save(room);
    }

    /**
     * Имитирует подтвержденные бронирования для комнаты.
     */
    private void simulateConfirmedBookings(Room room, int numberOfBookings) {
        for (int i = 0; i < numberOfBookings; i++) {
            String requestId = "sim-booking-" + room.getNumber() + "-" + i;
            LocalDate startDate = LocalDate.now().plusDays(i * 10 + 1);
            LocalDate endDate = startDate.plusDays(2);

            // Создаем и сразу подтверждаем блокировку
            RoomReservationLock lock = hotelService.holdRoom(requestId, room.getId(), startDate, endDate);
            hotelService.confirmHold(requestId);
        }

        // Обновляем комнату чтобы получить актуальные данные
        roomRepository.findById(room.getId()).ifPresent(updatedRoom -> {
            logger.debug("Room {} now has {} bookings", updatedRoom.getNumber(), updatedRoom.getTimesBooked());
        });
    }

    /**
     * Вспомогательный метод для получения блокировки по requestId.
     */
    private Optional<RoomReservationLock> getLockByRequestId(String requestId) {
        // В реальной системе этот метод был бы в HotelService
        // Для тестов используем прямое обращение к репозиторию
        return hotelService.listAllLocks().stream()
                .filter(lock -> lock.getRequestId().equals(requestId))
                .findFirst();
    }

    @AfterEach
    void tearDown() {
        logger.debug("Complex test completed - all transactions will be rolled back");
    }
}
