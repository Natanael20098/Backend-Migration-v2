package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Counter-offer entity linked to an original offer.
 * Anti-pattern: createdBy is a UUID not a FK, no audit trail.
 */
@Data
@Entity
@Table(name = "counteroffers")
public class CounterOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id", nullable = false)
    private Offer offer;

    @Column(name = "counter_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal counterAmount;

    @Column(columnDefinition = "TEXT")
    private String terms;

    // Anti-pattern: should be enum (PENDING, ACCEPTED, REJECTED, EXPIRED)
    private String status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @Column(name = "response_date")
    private Timestamp responseDate;

    @Column(name = "expiry_date")
    private Timestamp expiryDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
        updatedAt = new Timestamp(System.currentTimeMillis());
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
