package com.zcloud.platform.repository;

import com.zcloud.platform.model.CounterOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for CounterOffer entity.
 * Anti-pattern: extremely sparse with zero custom queries,
 * contrasts heavily with the bloated OfferRepository above.
 */
@Repository
public interface CounterOfferRepository extends JpaRepository<CounterOffer, UUID> {

    List<CounterOffer> findByOfferId(UUID offerId);

    List<CounterOffer> findByStatus(String status);
}
