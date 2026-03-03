package com.zcloud.platform.repository;

import com.zcloud.platform.model.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Lead entity.
 * Anti-patterns: uses java.sql.Timestamp in method signature (matching the entity's
 * inconsistent date type), JPQL with business logic.
 */
@Repository
public interface LeadRepository extends JpaRepository<Lead, UUID> {

    List<Lead> findByStatus(String status);

    List<Lead> findBySource(String source);

    List<Lead> findByAssignedAgentId(UUID agentId);

    // Anti-pattern: using java.sql.Timestamp because entity uses it for createdAt
    List<Lead> findByCreatedAtBetween(Timestamp startDate, Timestamp endDate);

    // Anti-pattern: business logic query - lead conversion analysis belongs in service
    @Query("SELECT l FROM Lead l WHERE l.status = 'NEW' " +
            "AND l.assignedAgent IS NULL")
    List<Lead> findUnassignedNewLeads();

    // Anti-pattern: native query mixed with JPQL above
    @Query(value = "SELECT source, COUNT(*) as cnt FROM leads " +
            "GROUP BY source ORDER BY cnt DESC",
            nativeQuery = true)
    List<Object[]> getLeadCountBySource();
}
