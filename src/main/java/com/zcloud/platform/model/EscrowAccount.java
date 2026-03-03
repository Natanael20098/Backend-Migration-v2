package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Escrow account for property taxes and insurance.
 * Anti-pattern: account number stored as plain text, mixed date types.
 */
@Data
@Entity
@Table(name = "escrow_accounts")
public class EscrowAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closing_id", nullable = false)
    private ClosingDetail closing;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(precision = 15, scale = 2)
    private BigDecimal balance;

    @Column(name = "monthly_payment", precision = 10, scale = 2)
    private BigDecimal monthlyPayment;

    @Column(name = "property_tax_reserve", precision = 12, scale = 2)
    private BigDecimal propertyTaxReserve;

    @Column(name = "insurance_reserve", precision = 12, scale = 2)
    private BigDecimal insuranceReserve;

    @Column(name = "pmi_reserve")
    private BigDecimal pmiReserve;

    @Column(name = "cushion_months")
    private Integer cushionMonths;

    private String status;

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
