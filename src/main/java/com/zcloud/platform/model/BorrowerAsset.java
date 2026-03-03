package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Borrower asset declarations for loan qualification.
 * Anti-pattern: account number stored in PLAIN TEXT - major security issue.
 */
@Data
@Entity
@Table(name = "borrower_assets")
public class BorrowerAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_application_id", nullable = false)
    @JsonIgnore
    private LoanApplication loanApplication;

    // Anti-pattern: should be enum (CHECKING, SAVINGS, INVESTMENT, RETIREMENT, OTHER)
    @Column(name = "asset_type")
    private String assetType;

    private String institution;

    // Anti-pattern: account number stored as PLAIN TEXT - should be encrypted
    @Column(name = "account_number")
    private String accountNumber;

    @Column(precision = 15, scale = 2)
    private BigDecimal balance;

    @Column(name = "monthly_deposit")
    private BigDecimal monthlyDeposit;

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
