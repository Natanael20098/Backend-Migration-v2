package com.zcloud.platform.repository;

import com.zcloud.platform.model.Commission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Commission entity.
 * Anti-patterns: native SQL for aggregation, business logic queries,
 * mixing JPQL and native inconsistently.
 */
@Repository
public interface CommissionRepository extends JpaRepository<Commission, UUID> {

    List<Commission> findByAgentId(UUID agentId);

    List<Commission> findByListingId(UUID listingId);

    List<Commission> findByStatus(String status);

    // Anti-pattern: native query with business logic aggregation - belongs in service/reporting layer
    @Query(value = "SELECT COALESCE(SUM(c.amount), 0) FROM commissions c " +
            "WHERE c.agent_id = :agentId " +
            "AND c.status = 'PAID' " +
            "AND c.paid_date BETWEEN :startDate AND :endDate",
            nativeQuery = true)
    BigDecimal getTotalCommissionsByAgentInDateRange(@Param("agentId") UUID agentId,
                                                     @Param("startDate") LocalDate startDate,
                                                     @Param("endDate") LocalDate endDate);

    // Anti-pattern: JPQL doing what could be a derived query method
    @Query("SELECT c FROM Commission c WHERE c.agent.id = :agentId AND c.status = :status")
    List<Commission> findByAgentAndStatus(@Param("agentId") UUID agentId,
                                           @Param("status") String status);

    // Anti-pattern: native query for counting, mixed with JPQL above
    @Query(value = "SELECT COUNT(*) FROM commissions WHERE agent_id = :agentId AND status = 'PENDING'",
            nativeQuery = true)
    int countPendingByAgent(@Param("agentId") UUID agentId);
}
