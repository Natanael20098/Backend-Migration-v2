package com.zcloud.platform.repository;

import com.zcloud.platform.model.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Client entity.
 * Anti-patterns: native query for simple name search, inconsistent return types,
 * mixing Optional and raw returns.
 */
@Repository
public interface ClientRepository extends JpaRepository<Client, UUID> {

    Optional<Client> findByEmail(String email);

    List<Client> findByClientType(String clientType);

    List<Client> findByAssignedAgentId(UUID agentId);

    // Anti-pattern: returns raw object, null if not found (inconsistent with findByEmail)
    Client findByPhone(String phone);

    // Anti-pattern: native SQL for a simple name search, LIKE queries with concatenation
    @Query(value = "SELECT * FROM clients " +
            "WHERE LOWER(first_name) LIKE LOWER(CONCAT('%', :name, '%')) " +
            "OR LOWER(last_name) LIKE LOWER(CONCAT('%', :name, '%')) " +
            "OR LOWER(CONCAT(first_name, ' ', last_name)) LIKE LOWER(CONCAT('%', :name, '%'))",
            nativeQuery = true)
    List<Client> searchByName(@Param("name") String name);

    // Anti-pattern: business logic in repo - find clients with no agent assigned
    @Query("SELECT c FROM Client c WHERE c.clientType = 'BUYER' " +
            "AND c.assignedAgent IS NULL")
    List<Client> findBuyersWithNoAgent();
}
