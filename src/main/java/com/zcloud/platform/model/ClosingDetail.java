package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Closing / settlement details for a transaction.
 * Anti-pattern: EAGER loads full loan application graph,
 * entity used as API response, mixed date types.
 */
@Data
@Entity
@Table(name = "closing_details")
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClosingDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: EAGER cascades into loan -> borrower -> property -> images
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    private Listing listing;

    @Column(name = "closing_date")
    private LocalDate closingDate;

    @Column(name = "closing_time")
    private String closingTime;

    @Column(name = "closing_location")
    private String closingLocation;

    // Anti-pattern: should be FK to agent or separate entity
    @Column(name = "closing_agent_name")
    private String closingAgentName;

    @Column(name = "closing_agent_email")
    private String closingAgentEmail;

    // Anti-pattern: should be enum (SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED)
    private String status;

    @Column(name = "total_closing_costs", precision = 12, scale = 2)
    private BigDecimal totalClosingCosts;

    @Column(name = "seller_credits", precision = 12, scale = 2)
    private BigDecimal sellerCredits;

    @Column(name = "buyer_credits", precision = 12, scale = 2)
    private BigDecimal buyerCredits;

    @Column(name = "proration_date")
    private LocalDate prorationDate;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "buyer_name")
    private String buyerName;

    @Column(name = "seller_name")
    private String sellerName;

    @Column(name = "loan_amount")
    private BigDecimal loanAmount;

    @Column(name = "sale_price")
    private BigDecimal salePrice;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
