package com.example.hotel.repositor;

import com.example.hotel.mo.Hotel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для работы с отелями.
 * Предоставляет методы для поиска, фильтрации и анализа отелей.
 */
@Repository
public interface HotelRepository extends JpaRepository<Hotel, Long> {

    /**
     * Находит отель по имени (точное совпадение).
     */
    Optional<Hotel> findByName(String name);

    /**
     * Находит отели по названию города.
     */
    List<Hotel> findByCity(String city);

    /**
     * Находит отели по городу с пагинацией.
     */
    Page<Hotel> findByCity(String city, Pageable pageable);

    /**
     * Находит отели по частичному совпадению названия (case-insensitive).
     */
    @Query("SELECT h FROM Hotel h WHERE LOWER(h.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<Hotel> findByNameContainingIgnoreCase(@Param("name") String name);

    /**
     * Находит отели по частичному совпадению названия с пагинацией.
     */
    @Query("SELECT h FROM Hotel h WHERE LOWER(h.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    Page<Hotel> findByNameContainingIgnoreCase(@Param("name") String name, Pageable pageable);

    /**
     * Находит отели по городу и частичному совпадению названия.
     */
    @Query("SELECT h FROM Hotel h WHERE h.city = :city AND LOWER(h.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<Hotel> findByCityAndNameContainingIgnoreCase(@Param("city") String city, @Param("name") String name);

    /**
     * Находит отели по рейтингу (звездности).
     */
    List<Hotel> findByStarRating(Integer starRating);

    /**
     * Находит отели с рейтингом больше или равным указанному.
     */
    List<Hotel> findByStarRatingGreaterThanEqual(Integer minStarRating);

    /**
     * Находит отели с рейтингом в указанном диапазоне.
     */
    List<Hotel> findByStarRatingBetween(Integer minRating, Integer maxRating);

    /**
     * Находит активные отели.
     */
    List<Hotel> findByActiveTrue();

    /**
     * Находит активные отели с пагинацией.
     */
    Page<Hotel> findByActiveTrue(Pageable pageable);

    /**
     * Находит отели по статусу.
     */
    List<Hotel> findByStatus(Hotel.HotelStatus status);

    /**
     * Находит отели по статусу с пагинацией.
     */
    Page<Hotel> findByStatus(Hotel.HotelStatus status, Pageable pageable);

    /**
     * Проверяет существование отеля с указанным именем в городе.
     */
    @Query("SELECT COUNT(h) > 0 FROM Hotel h WHERE h.name = :name AND h.city = :city")
    boolean existsByNameAndCity(@Param("name") String name, @Param("city") String city);

    /**
     * Находит все уникальные города, в которых есть отели.
     */
    @Query("SELECT DISTINCT h.city FROM Hotel h ORDER BY h.city")
    List<String> findAllDistinctCities();

    /**
     * Находит количество отелей в указанном городе.
     */
    @Query("SELECT COUNT(h) FROM Hotel h WHERE h.city = :city")
    long countByCity(@Param("city") String city);

    /**
     * Находит отели с минимальным количеством комнат.
     */
    @Query("SELECT h FROM Hotel h WHERE SIZE(h.rooms) >= :minRooms")
    List<Hotel> findHotelsWithMinimumRooms(@Param("minRooms") int minRooms);

    /**
     * Находит отели с количеством комнат в указанном диапазоне.
     */
    @Query("SELECT h FROM Hotel h WHERE SIZE(h.rooms) BETWEEN :minRooms AND :maxRooms")
    List<Hotel> findHotelsWithRoomCountBetween(@Param("minRooms") int minRooms, @Param("maxRooms") int maxRooms);

    /**
     * Находит отели, отсортированные по рейтингу (по убыванию).
     */
    @Query("SELECT h FROM Hotel h ORDER BY h.starRating DESC")
    List<Hotel> findAllOrderByStarRatingDesc();

    /**
     * Находит отели, отсортированные по рейтингу с пагинацией.
     */
    @Query("SELECT h FROM Hotel h ORDER BY h.starRating DESC")
    Page<Hotel> findAllOrderByStarRatingDesc(Pageable pageable);

    /**
     * Находит отели, отсортированные по количеству комнат (по убыванию).
     */
    @Query("SELECT h FROM Hotel h ORDER BY SIZE(h.rooms) DESC")
    List<Hotel> findAllOrderByRoomCountDesc();

    /**
     * Находит топ-N отелей по рейтингу.
     */
    @Query("SELECT h FROM Hotel h ORDER BY h.starRating DESC, h.name ASC")
    List<Hotel> findTopNByStarRating(Pageable pageable);

    /**
     * Находит отели с доступными комнатами в указанные даты.
     * Используется для поиска отелей, где есть свободные номера.
     */
    @Query("SELECT DISTINCT h FROM Hotel h JOIN h.rooms r WHERE r.available = true AND h.active = true")
    List<Hotel> findHotelsWithAvailableRooms();

    /**
     * Находит отели с доступными комнатами в указанном городе.
     */
    @Query("SELECT DISTINCT h FROM Hotel h JOIN h.rooms r WHERE h.city = :city AND r.available = true AND h.active = true")
    List<Hotel> findHotelsWithAvailableRoomsInCity(@Param("city") String city);

    /**
     * Находит отели по списку идентификаторов.
     */
    List<Hotel> findByIdIn(List<Long> ids);

    /**
     * Находит статистику по отелям (количество отелей по городам).
     */
    @Query("SELECT h.city, COUNT(h) FROM Hotel h GROUP BY h.city ORDER BY COUNT(h) DESC")
    List<Object[]> findHotelCountByCity();

    /**
     * Находит средний рейтинг отелей по городам.
     */
    @Query("SELECT h.city, AVG(h.starRating) FROM Hotel h GROUP BY h.city ORDER BY AVG(h.starRating) DESC")
    List<Object[]> findAverageRatingByCity();

    /**
     * Находит общее количество комнат во всех отелях.
     */
    @Query("SELECT SUM(SIZE(h.rooms)) FROM Hotel h")
    Long findTotalRoomCount();

    /**
     * Находит общее количество комнат в отелях указанного города.
     */
    @Query("SELECT SUM(SIZE(h.rooms)) FROM Hotel h WHERE h.city = :city")
    Long findTotalRoomCountByCity(@Param("city") String city);
}
