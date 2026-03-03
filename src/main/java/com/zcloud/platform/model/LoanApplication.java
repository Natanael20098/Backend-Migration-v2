package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mortgage loan application - the heart of the lending module.
 * Anti-pattern: massive entity with EAGER fetches,
 * references Agent table for both loan officer and processor roles,
 * entity used directly as API response.
 */
@Data
@Entity
@Table(name = "loan_applications")
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: EAGER loads full client with SSN
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "borrower_id", nullable = false)
    private Client borrower;

    @Column(name = "co_borrower_id")
    private UUID coBorrowerId;

    // Anti-pattern: EAGER loads property with all images
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "property_id")
    private Property property;

    // Anti-pattern: should be enum (CONVENTIONAL, FHA, VA, USDA, JUMBO)
    @Column(name = "loan_type")
    private String loanType;

    @Column(name = "loan_purpose")
    private String loanPurpose;

    @Column(name = "loan_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal loanAmount;

    @Column(name = "interest_rate", precision = 6, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "loan_term_months")
    private Integer loanTermMonths;

    @Column(name = "down_payment", precision = 15, scale = 2)
    private BigDecimal downPayment;

    @Column(name = "down_payment_percent")
    private BigDecimal downPaymentPercent;

    // Anti-pattern: Agent entity reused for loan officer role
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_officer_id")
    private Agent loanOfficer;

    // Anti-pattern: Agent entity reused for processor role
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processor_id")
    private Agent processor;

    // Anti-pattern: should be enum (DRAFT, SUBMITTED, PROCESSING, UNDERWRITING, APPROVED, DENIED, CLOSED, WITHDRAWN)
    private String status;

    @Column(name = "application_date")
    private LocalDate applicationDate;

    @Column(name = "estimated_closing_date")
    private LocalDate estimatedClosingDate;

    @Column(name = "actual_closing_date")
    private LocalDate actualClosingDate;

    @Column(name = "borrower_name")
    private String borrowerName;

    @Column(name = "borrower_email")
    private String borrowerEmail;

    @Column(name = "borrower_phone")
    private String borrowerPhone;

    @Column(name = "borrower_ssn_encrypted")
    private String borrowerSsnEncrypted;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "property_value")
    private BigDecimal propertyValue;

    @Column(name = "monthly_payment")
    private BigDecimal monthlyPayment;

    @Column(name = "total_interest")
    private BigDecimal totalInterest;

    private BigDecimal apr;

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
            status = "DRAFT";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
