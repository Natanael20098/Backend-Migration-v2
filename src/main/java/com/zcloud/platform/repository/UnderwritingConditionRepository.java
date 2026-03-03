package com.zcloud.platform.repository;

import com.zcloud.platform.model.UnderwritingCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for UnderwritingCondition entity.
 * Anti-pattern: sparse with no custom queries, inconsistent with sibling
 * UnderwritingDecisionRepository which has analytics queries.
 */
@Repository
public interface UnderwritingConditionRepository extends JpaRepository<UnderwritingCondition, UUID> {

    List<UnderwritingCondition> findByDecisionId(UUID decisionId);

    List<UnderwritingCondition> findByStatus(String status);
}
