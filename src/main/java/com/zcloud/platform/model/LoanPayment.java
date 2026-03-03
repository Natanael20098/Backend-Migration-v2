package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Actual loan payment records.
 * Anti-pattern: status as string, mixed date types (LocalDate and Timestamp).
 */
@Data
@Entity
@Table(name = "loan_payments")
public class LoanPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @Column(name = "payment_number")
    private Integer paymentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "principal_amount", precision = 12, scale = 2)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", precision = 12, scale = 2)
    private BigDecimal interestAmount;

    @Column(name = "escrow_amount", precision = 12, scale = 2)
    private BigDecimal escrowAmount;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "additional_principal")
    private BigDecimal additionalPrincipal;

    // Anti-pattern: should be enum (PENDING, PAID, LATE, MISSED, PARTIAL)
    private String status;

    @Column(name = "late_fee", precision = 10, scale = 2)
    private BigDecimal lateFee;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "confirmation_number")
    private String confirmationNumber;

    @Column(name = "borrower_name")
    private String borrowerName;

    @Column(name = "loan_amount")
    private BigDecimal loanAmount;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
