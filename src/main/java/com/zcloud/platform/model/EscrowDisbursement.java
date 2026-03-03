package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Escrow disbursement records (tax payments, insurance premiums).
 * Anti-pattern: payee as string instead of FK, mixed date types.
 */
@Data
@Entity
@Table(name = "escrow_disbursements")
public class EscrowDisbursement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escrow_account_id", nullable = false)
    private EscrowAccount escrowAccount;

    // Anti-pattern: should be enum (PROPERTY_TAX, HOMEOWNERS_INSURANCE, MORTGAGE_INSURANCE, FLOOD_INSURANCE)
    @Column(name = "disbursement_type")
    private String disbursementType;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    // Anti-pattern: payee as free-text string
    private String payee;

    @Column(name = "payee_account")
    private String payeeAccount;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "period_covered")
    private String periodCovered;

    @Column(name = "check_number")
    private String checkNumber;

    private String confirmation;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "borrower_name")
    private String borrowerName;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
