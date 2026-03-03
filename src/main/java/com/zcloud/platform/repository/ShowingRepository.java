package com.zcloud.platform.repository;

import com.zcloud.platform.model.Showing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Showing entity.
 * Anti-patterns: mixed query styles, business logic queries for scheduling conflicts.
 */
@Repository
public interface ShowingRepository extends JpaRepository<Showing, UUID> {

    List<Showing> findByListingId(UUID listingId);

    List<Showing> findByClientId(UUID clientId);

    List<Showing> findByAgentId(UUID agentId);

    List<Showing> findByScheduledDateBetween(Timestamp startDate, Timestamp endDate);

    List<Showing> findByStatus(String status);

    // Anti-pattern: business logic in repo - scheduling conflict detection belongs in service
    @Query("SELECT s FROM Showing s WHERE s.agent.id = :agentId " +
            "AND s.scheduledDate BETWEEN :start AND :end " +
            "AND s.status = 'SCHEDULED'")
    List<Showing> findConflictingShowings(@Param("agentId") UUID agentId,
                                           @Param("start") Timestamp start,
                                           @Param("end") Timestamp end);

    // Anti-pattern: native query for average rating - analytics in repo layer
    @Query(value = "SELECT AVG(rating) FROM showings WHERE listing_id = :listingId AND rating IS NOT NULL",
            nativeQuery = true)
    Double getAverageRatingForListing(@Param("listingId") UUID listingId);
}
