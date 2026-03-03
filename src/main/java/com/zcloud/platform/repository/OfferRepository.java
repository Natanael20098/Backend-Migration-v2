package com.zcloud.platform.repository;

import com.zcloud.platform.model.Offer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Offer entity.
 * Anti-patterns: native query joining multiple tables, business logic for offer analysis,
 * inconsistent return types.
 */
@Repository
public interface OfferRepository extends JpaRepository<Offer, UUID> {

    List<Offer> findByListingId(UUID listingId);

    List<Offer> findByBuyerClientId(UUID buyerClientId);

    List<Offer> findByStatus(String status);

    // Anti-pattern: massive native query with joins - business logic in repo layer,
    // selects all offer columns plus listing details via join
    @Query(value = "SELECT o.*, l.mls_number, l.list_price, l.status as listing_status, " +
            "p.address_line1, p.city, p.state, p.zip_code " +
            "FROM offers o " +
            "INNER JOIN listings l ON o.listing_id = l.id " +
            "INNER JOIN properties p ON l.property_id = p.id " +
            "WHERE o.buyer_client_id = :buyerClientId " +
            "ORDER BY o.submitted_at DESC",
            nativeQuery = true)
    List<Object[]> findOffersWithListingDetails(@Param("buyerClientId") UUID buyerClientId);

    // Anti-pattern: business logic - highest offer analysis belongs in service
    @Query("SELECT o FROM Offer o WHERE o.listing.id = :listingId " +
            "AND o.status = 'SUBMITTED' ORDER BY o.offerAmount DESC")
    List<Offer> findActiveOffersByListingOrderByAmount(@Param("listingId") UUID listingId);

    // Anti-pattern: native query for counting when derived query would work
    @Query(value = "SELECT COUNT(*) FROM offers WHERE listing_id = :listingId AND status IN ('SUBMITTED', 'COUNTERED')",
            nativeQuery = true)
    int countPendingOffersByListing(@Param("listingId") UUID listingId);
}
