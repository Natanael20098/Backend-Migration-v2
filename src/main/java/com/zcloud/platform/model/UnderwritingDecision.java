package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Underwriting decision for a loan application.
 * Anti-pattern: EAGER loads full loan application graph,
 * conditions as TEXT blob, Agent reused for underwriter role.
 */
@Data
@Entity
@Table(name = "underwriting_decisions")
public class UnderwritingDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: EAGER cascades into loan -> borrower -> property -> images
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    // Anti-pattern: Agent entity reused for underwriter role
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "underwriter_id")
    private Agent underwriter;

    // Anti-pattern: should be enum (APPROVED, DENIED, SUSPENDED, COUNTER_OFFER)
    private String decision;

    // Anti-pattern: conditions stored as text blob instead of related table
    @Column(columnDefinition = "TEXT")
    private String conditions;

    @Column(name = "dti_ratio")
    private BigDecimal dtiRatio;

    @Column(name = "ltv_ratio")
    private BigDecimal ltvRatio;

    @Column(name = "risk_score")
    private BigDecimal riskScore;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "decision_date")
    private Timestamp decisionDate;

    @Column(name = "loan_amount")
    private BigDecimal loanAmount;

    @Column(name = "loan_type")
    private String loanType;

    @Column(name = "borrower_name")
    private String borrowerName;

    @Column(name = "property_address")
    private String propertyAddress;

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
