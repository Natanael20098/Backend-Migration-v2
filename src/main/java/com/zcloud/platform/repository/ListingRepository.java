package com.zcloud.platform.repository;

import com.zcloud.platform.model.Listing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Listing entity.
 * Anti-patterns: heavy business logic queries, mixing JPQL and native SQL,
 * inconsistent return types (Optional vs raw vs List for single results).
 */
@Repository
public interface ListingRepository extends JpaRepository<Listing, UUID> {

    List<Listing> findByStatus(String status);

    List<Listing> findByAgentId(UUID agentId);

    // Anti-pattern: inconsistent - this one uses Optional while others return raw
    Optional<Listing> findByMlsNumber(String mlsNumber);

    List<Listing> findByListPriceBetween(BigDecimal minPrice, BigDecimal maxPrice);

    // Anti-pattern: massive native query with business logic that belongs in service layer
    @Query(value = "SELECT l.* FROM listings l " +
            "INNER JOIN properties p ON l.property_id = p.id " +
            "WHERE (:status IS NULL OR l.status = :status) " +
            "AND (:city IS NULL OR p.city = :city) " +
            "AND (:state IS NULL OR p.state = :state) " +
            "AND (:minPrice IS NULL OR l.list_price >= :minPrice) " +
            "AND (:maxPrice IS NULL OR l.list_price <= :maxPrice) " +
            "AND (:minBeds IS NULL OR p.beds >= :minBeds) " +
            "AND (:propertyType IS NULL OR p.property_type = :propertyType) " +
            "ORDER BY l.listed_date DESC",
            nativeQuery = true)
    List<Listing> searchListings(@Param("status") String status,
                                  @Param("city") String city,
                                  @Param("state") String state,
                                  @Param("minPrice") BigDecimal minPrice,
                                  @Param("maxPrice") BigDecimal maxPrice,
                                  @Param("minBeds") Integer minBeds,
                                  @Param("propertyType") String propertyType);

    // Anti-pattern: JPQL query with JOIN FETCH - mixing strategies with above native query
    @Query("SELECT l FROM Listing l JOIN FETCH l.agent a JOIN FETCH l.property p " +
            "WHERE l.status = 'ACTIVE' ORDER BY l.listedDate DESC")
    List<Listing> findActiveListingsWithAgentInfo();

    // Anti-pattern: business logic - price reduction calculation in repo
    @Query("SELECT l FROM Listing l WHERE l.originalPrice IS NOT NULL " +
            "AND l.listPrice < l.originalPrice AND l.status = 'ACTIVE'")
    List<Listing> findListingsWithPriceReductions();

    // Anti-pattern: native query when JPQL would suffice, returns raw count
    @Query(value = "SELECT COUNT(*) FROM listings WHERE agent_id = :agentId AND status = 'ACTIVE'",
            nativeQuery = true)
    int countActiveListingsByAgent(@Param("agentId") UUID agentId);

    // Anti-pattern: duplicate of findByStatus but with different query style
    @Query("SELECT l FROM Listing l WHERE l.status = :status AND l.agent.id = :agentId")
    List<Listing> getListingsByStatusAndAgent(@Param("status") String status,
                                              @Param("agentId") UUID agentId);
}
