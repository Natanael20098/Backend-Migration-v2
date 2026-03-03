package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Credit bureau report data.
 * Anti-pattern: entire credit report dumped as TEXT blob,
 * sensitive data on entity.
 */
@Data
@Entity
@Table(name = "credit_reports")
public class CreditReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_application_id", nullable = false)
    @JsonIgnore
    private LoanApplication loanApplication;

    // Anti-pattern: should be enum (EQUIFAX, EXPERIAN, TRANSUNION)
    private String bureau;

    private Integer score;

    @Column(name = "report_date")
    private LocalDate reportDate;

    // Anti-pattern: full credit report stored as text blob
    @Column(name = "report_data", columnDefinition = "TEXT")
    @JsonIgnore
    private String reportData;

    @Column(name = "pulled_by")
    private UUID pulledBy;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "borrower_name")
    private String borrowerName;

    @Column(name = "borrower_ssn_last4")
    private String borrowerSsnLast4;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
