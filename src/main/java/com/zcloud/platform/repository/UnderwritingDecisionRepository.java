package com.zcloud.platform.repository;

import com.zcloud.platform.model.UnderwritingDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for UnderwritingDecision entity.
 * Anti-patterns: inconsistent naming (uses "underwriterId" for agent FK),
 * JPQL business logic query for approval rate calculation.
 */
@Repository
public interface UnderwritingDecisionRepository extends JpaRepository<UnderwritingDecision, UUID> {

    List<UnderwritingDecision> findByLoanApplicationId(UUID loanApplicationId);

    // Anti-pattern: "underwriterId" references Agent table - confusing naming
    List<UnderwritingDecision> findByUnderwriterId(UUID underwriterId);

    List<UnderwritingDecision> findByDecision(String decision);

    // Anti-pattern: business logic in repo - approval rate calculation belongs in service
    @Query(value = "SELECT " +
            "COUNT(CASE WHEN decision = 'APPROVED' THEN 1 END) * 100.0 / COUNT(*) as approval_rate " +
            "FROM underwriting_decisions " +
            "WHERE underwriter_id = :underwriterId",
            nativeQuery = true)
    Double getApprovalRateByUnderwriter(@Param("underwriterId") UUID underwriterId);
}
