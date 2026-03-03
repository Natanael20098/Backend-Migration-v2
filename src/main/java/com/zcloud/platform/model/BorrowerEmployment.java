package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Borrower employment history for loan qualification.
 * Anti-pattern: verificationStatus as string.
 */
@Data
@Entity
@Table(name = "borrower_employment")
public class BorrowerEmployment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_application_id", nullable = false)
    @JsonIgnore
    private LoanApplication loanApplication;

    @Column(name = "employer_name", nullable = false)
    private String employerName;

    private String position;

    @Column(name = "monthly_income", precision = 12, scale = 2)
    private BigDecimal monthlyIncome;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "is_current")
    private Boolean isCurrent;

    @Column(name = "employer_phone")
    private String employerPhone;

    @Column(name = "employer_address")
    private String employerAddress;

    @Column(name = "years_in_field")
    private Integer yearsInField;

    // Anti-pattern: should be enum (PENDING, VERIFIED, FAILED, NOT_REQUIRED)
    @Column(name = "verification_status")
    private String verificationStatus;

    @Column(name = "borrower_name")
    private String borrowerName;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
