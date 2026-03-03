package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Property showing / tour scheduling.
 * Anti-pattern: three EAGER fetches in one entity, entity as DTO.
 */
@Data
@Entity
@Table(name = "showings")
public class Showing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: EAGER fetch cascades into property, property images, etc.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    // Anti-pattern: EAGER loads full client record
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    @Column(name = "scheduled_date", nullable = false)
    private Timestamp scheduledDate;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    // Anti-pattern: should be enum (SCHEDULED, COMPLETED, CANCELLED, NO_SHOW)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    // 1-5 rating, no validation
    private Integer rating;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "list_price")
    private BigDecimal listPrice;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "client_phone")
    private String clientPhone;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
        updatedAt = new Timestamp(System.currentTimeMillis());
        if (status == null) {
            status = "SCHEDULED";
        }
        if (durationMinutes == null) {
            durationMinutes = 30;  // default 30 minutes - magic number
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
