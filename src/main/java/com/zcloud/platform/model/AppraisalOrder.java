package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Appraisal order tracking.
 * Anti-pattern: appraiser info stored as strings instead of separate entity,
 * mixed date types.
 */
@Data
@Entity
@Table(name = "appraisal_orders")
public class AppraisalOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    // Anti-pattern: appraiser should be its own entity
    @Column(name = "appraiser_name")
    private String appraiserName;

    @Column(name = "appraiser_license")
    private String appraiserLicense;

    @Column(name = "appraiser_company")
    private String appraiserCompany;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "completed_date")
    private LocalDate completedDate;

    // Anti-pattern: should be enum (ORDERED, SCHEDULED, INSPECTED, COMPLETED, CANCELLED)
    private String status;

    @Column(precision = 10, scale = 2)
    private BigDecimal fee;

    @Column(name = "rush_fee")
    private BigDecimal rushFee;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "property_type")
    private String propertyType;

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
