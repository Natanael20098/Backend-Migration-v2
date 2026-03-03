package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * User notification entity.
 * Anti-pattern: userId is a UUID with NO FK constraint,
 * type is a string instead of enum, no read timestamp.
 */
@Data
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: UUID reference with NO FK constraint - no user table relationship
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    // Anti-pattern: should be enum (EMAIL, SMS, PUSH, IN_APP, SYSTEM)
    private String type;

    @Column(name = "is_read")
    private Boolean isRead;

    @Column(name = "read_at")
    private Timestamp readAt;

    private String link;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "user_email")
    private String userEmail;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
        if (isRead == null) {
            isRead = false;
        }
    }
}
