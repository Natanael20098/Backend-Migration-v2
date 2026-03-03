package com.zcloud.platform.repository;

import com.zcloud.platform.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Agent entity.
 * Anti-patterns: inconsistent return types (Optional for email, raw for licenseNumber),
 * heavy native SQL with business logic, mixed JPQL and native.
 */
@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    // Anti-pattern: inconsistent - Optional here but raw object below
    Optional<Agent> findByEmail(String email);

    // Anti-pattern: returns raw object (null if not found) while findByEmail returns Optional
    Agent findByLicenseNumber(String licenseNumber);

    List<Agent> findByBrokerageId(UUID brokerageId);

    List<Agent> findByIsActiveTrue();

    // Anti-pattern: heavy business logic native query - joins across 3 tables, aggregation,
    // this is analytics logic that belongs in a service or reporting module
    @Query(value = "SELECT a.*, COALESCE(SUM(l.list_price), 0) as total_volume " +
            "FROM agents a " +
            "LEFT JOIN listings l ON a.id = l.agent_id AND l.status = 'SOLD' " +
            "LEFT JOIN commissions c ON a.id = c.agent_id AND c.status = 'PAID' " +
            "WHERE a.is_active = true " +
            "GROUP BY a.id " +
            "ORDER BY total_volume DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Agent> findTopAgentsBySalesVolume(@Param("limit") int limit);

    // Anti-pattern: JPQL mixed in same repo as native query
    @Query("SELECT a FROM Agent a WHERE a.brokerage.id = :brokerageId AND a.isActive = true")
    List<Agent> findActiveAgentsByBrokerage(@Param("brokerageId") UUID brokerageId);

    // Anti-pattern: native query for simple name search, could use derived query
    @Query(value = "SELECT * FROM agents WHERE LOWER(first_name) LIKE LOWER(CONCAT('%', :name, '%')) " +
            "OR LOWER(last_name) LIKE LOWER(CONCAT('%', :name, '%'))",
            nativeQuery = true)
    List<Agent> searchByName(@Param("name") String name);
}
