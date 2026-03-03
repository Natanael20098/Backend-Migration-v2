package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Key-value system configuration storage.
 * Anti-pattern: using database as config store, TEXT value for everything,
 * no type safety on values.
 */
@Data
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "setting_key", unique = true, nullable = false)
    private String settingKey;

    // Anti-pattern: all values stored as TEXT regardless of actual type
    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    private String category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_encrypted")
    private Boolean isEncrypted;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
