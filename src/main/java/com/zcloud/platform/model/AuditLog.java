package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Audit log for tracking all system changes.
 * Anti-pattern: userId and resourceId have NO foreign key constraints,
 * old/new values stored as TEXT blobs, no index on timestamp.
 */
@Data
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: UUID reference with NO FK constraint - could reference any table or nothing
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "user_name")
    private String userName;

    @Column(nullable = false)
    private String action;  // should be enum (CREATE, UPDATE, DELETE, VIEW, LOGIN, LOGOUT)

    @Column(name = "resource_type")
    private String resourceType;

    // resource_id is varchar in the DB, NOT UUID
    @Column(name = "resource_id")
    private String resourceId;

    // Anti-pattern: entire old/new state as TEXT - huge storage waste
    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "timestamp", nullable = false)
    private Timestamp timestamp;

    @PrePersist
    protected void onCreate() {
        timestamp = new Timestamp(System.currentTimeMillis());
    }
}
