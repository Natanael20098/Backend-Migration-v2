package com.zcloud.platform.repository;

import com.zcloud.platform.model.Property;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Property entity.
 * Anti-patterns: mix of JPQL and native queries, business logic in repo,
 * inconsistent return types (List vs raw), excessive custom queries.
 */
@Repository
public interface PropertyRepository extends JpaRepository<Property, UUID> {

    List<Property> findByCity(String city);

    List<Property> findByState(String state);

    // Anti-pattern: returns raw object, null if not found (should be Optional)
    Property findByPropertyType(String propertyType);

    List<Property> findByZipCode(String zipCode);

    List<Property> findByBedsGreaterThanEqual(Integer beds);

    List<Property> findByBathsGreaterThanEqual(BigDecimal baths);

    List<Property> findBySqftBetween(Integer minSqft, Integer maxSqft);

    // Anti-pattern: native SQL query when JPQL would suffice, joins across tables in repository
    @Query(value = "SELECT p.* FROM properties p " +
            "INNER JOIN listings l ON p.id = l.property_id " +
            "WHERE l.list_price BETWEEN :minPrice AND :maxPrice " +
            "AND l.status = 'ACTIVE' " +
            "ORDER BY l.list_price ASC",
            nativeQuery = true)
    List<Property> searchByPriceRange(@Param("minPrice") BigDecimal minPrice,
                                      @Param("maxPrice") BigDecimal maxPrice);

    // Anti-pattern: business logic query - filtering active listings belongs in service layer
    @Query(value = "SELECT p.* FROM properties p " +
            "INNER JOIN listings l ON p.id = l.property_id " +
            "WHERE p.city = :city AND p.beds >= :minBeds " +
            "AND p.baths >= :minBaths AND l.status = 'ACTIVE' " +
            "AND l.list_price <= :maxPrice",
            nativeQuery = true)
    List<Property> findAvailablePropertiesByCriteria(@Param("city") String city,
                                                      @Param("minBeds") Integer minBeds,
                                                      @Param("minBaths") Double minBaths,
                                                      @Param("maxPrice") BigDecimal maxPrice);

    // Anti-pattern: JPQL mixed with native queries in the same repo
    @Query("SELECT p FROM Property p WHERE p.yearBuilt >= :year AND p.sqft >= :minSqft")
    List<Property> findNewerLargeProperties(@Param("year") Integer year,
                                             @Param("minSqft") Integer minSqft);

    // Anti-pattern: counting in repository instead of using aggregation service
    @Query("SELECT COUNT(p) FROM Property p WHERE p.city = :city")
    long countPropertiesByCity(@Param("city") String city);

    // Anti-pattern: native query for something Spring Data could handle with method naming
    @Query(value = "SELECT DISTINCT p.city FROM properties p WHERE p.state = :state ORDER BY p.city",
            nativeQuery = true)
    List<String> findDistinctCitiesByState(@Param("state") String state);
}
