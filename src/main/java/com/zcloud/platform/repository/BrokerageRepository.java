package com.zcloud.platform.repository;

import com.zcloud.platform.model.Brokerage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Brokerage entity.
 * Anti-pattern: extremely sparse - only 2 methods, no custom queries at all,
 * inconsistent with other repos that are overloaded. Also inconsistent return types.
 */
@Repository
public interface BrokerageRepository extends JpaRepository<Brokerage, UUID> {

    // Anti-pattern: returns raw object instead of Optional - could return null
    Brokerage findByName(String name);

    // Anti-pattern: inconsistent - Optional here but raw above for findByName
    Optional<Brokerage> findByLicenseNumber(String licenseNumber);
}
