package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Amortization schedule entries.
 * Anti-pattern: duplicates fields from LoanPayment with slightly different names
 * (principal vs principalAmount).
 */
@Data
@Entity
@Table(name = "payment_schedules")
public class PaymentSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @Column(name = "payment_number")
    private Integer paymentNumber;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(precision = 12, scale = 2)
    private BigDecimal principal;

    @Column(precision = 12, scale = 2)
    private BigDecimal interest;

    @Column(precision = 12, scale = 2)
    private BigDecimal escrow;

    @Column(precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "remaining_balance", precision = 15, scale = 2)
    private BigDecimal remainingBalance;

    @Column(name = "cumulative_interest")
    private BigDecimal cumulativeInterest;

    @Column(name = "cumulative_principal")
    private BigDecimal cumulativePrincipal;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
