package com.zcloud.platform.repository;

import com.zcloud.platform.model.OpenHouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repository for OpenHouse entity.
 * Anti-pattern: zero custom queries - pure derived methods only,
 * stark contrast to other repos that are overloaded with custom queries.
 */
@Repository
public interface OpenHouseRepository extends JpaRepository<OpenHouse, UUID> {

    List<OpenHouse> findByListingId(UUID listingId);

    List<OpenHouse> findByAgentId(UUID agentId);

    List<OpenHouse> findByEventDateBetween(LocalDate startDate, LocalDate endDate);
}
