package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Purchase offer entity. Used directly as API response.
 * Anti-pattern: EAGER fetches everywhere, JSON stored as TEXT,
 * @Transient list that should be a proper relationship.
 */
@Data
@Entity
@Table(name = "offers")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Offer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: EAGER cascade into listing -> property -> images
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    // Anti-pattern: EAGER loads buyer + their agent
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "buyer_client_id", nullable = false)
    private Client buyerClient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_agent_id")
    private Agent buyerAgent;

    @Column(name = "offer_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal offerAmount;

    @Column(name = "earnest_money", precision = 12, scale = 2)
    private BigDecimal earnestMoney;

    // Anti-pattern: JSON blob stored as TEXT instead of proper structure
    @Column(name = "contingencies", columnDefinition = "TEXT")
    private String contingencies;

    // Anti-pattern: should be enum (CONVENTIONAL, FHA, VA, CASH, etc.)
    @Column(name = "financing_type")
    private String financingType;

    @Column(name = "closing_date_requested")
    private LocalDate closingDateRequested;

    // Anti-pattern: should be enum (SUBMITTED, ACCEPTED, REJECTED, COUNTERED, WITHDRAWN, EXPIRED)
    private String status;

    @Column(name = "expiry_date")
    private Timestamp expiryDate;

    @Column(name = "submitted_at")
    private Timestamp submittedAt;

    @Column(name = "responded_at")
    private Timestamp respondedAt;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "list_price")
    private BigDecimal listPrice;

    @Column(name = "buyer_name")
    private String buyerName;

    @Column(name = "buyer_phone")
    private String buyerPhone;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    // Anti-pattern: @Transient list manually populated - should be @OneToMany
    @Transient
    private List<CounterOffer> counterOffers = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        submittedAt = new Timestamp(System.currentTimeMillis());
        createdAt = new Timestamp(System.currentTimeMillis());
        updatedAt = new Timestamp(System.currentTimeMillis());
        if (status == null) {
            status = "SUBMITTED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
