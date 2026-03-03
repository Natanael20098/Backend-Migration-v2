package com.zcloud.platform.repository;

import com.zcloud.platform.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for AuditLog entity.
 * Anti-patterns: excessive custom queries for a simple audit table,
 * native query for user activity report, business logic in repo.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByUserId(UUID userId);

    List<AuditLog> findByResourceType(String resourceType);

    List<AuditLog> findByResourceId(String resourceId);

    List<AuditLog> findByTimestampBetween(Timestamp startTime, Timestamp endTime);

    List<AuditLog> findByAction(String action);

    // Anti-pattern: business logic query - activity reporting belongs in analytics service
    @Query(value = "SELECT user_id, action, COUNT(*) as action_count " +
            "FROM audit_logs " +
            "WHERE timestamp BETWEEN :startTime AND :endTime " +
            "GROUP BY user_id, action " +
            "ORDER BY action_count DESC",
            nativeQuery = true)
    List<Object[]> getUserActivityReport(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    // Anti-pattern: JPQL mixed with native above
    @Query("SELECT a FROM AuditLog a WHERE a.resourceType = :resourceType " +
            "AND a.resourceId = :resourceId ORDER BY a.timestamp DESC")
    List<AuditLog> findResourceHistory(@Param("resourceType") String resourceType,
                                        @Param("resourceId") String resourceId);

    // Anti-pattern: native query for deletion - audit logs should never be deleted
    @Query(value = "DELETE FROM audit_logs WHERE timestamp < :cutoffDate", nativeQuery = true)
    void purgeOldLogs(@Param("cutoffDate") LocalDateTime cutoffDate);
}
