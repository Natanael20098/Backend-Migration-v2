package com.zcloud.platform.repository;

import com.zcloud.platform.model.AgentLicense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repository for AgentLicense entity.
 * Anti-pattern: no custom queries at all despite being a domain with expiration logic,
 * findByExpiryDateBefore is a business-logic query embedded at the data layer.
 */
@Repository
public interface AgentLicenseRepository extends JpaRepository<AgentLicense, UUID> {

    List<AgentLicense> findByAgentId(UUID agentId);

    List<AgentLicense> findByStatus(String status);

    // Anti-pattern: business logic in repository - expiration checking belongs in service
    List<AgentLicense> findByExpiryDateBefore(LocalDate date);
}
