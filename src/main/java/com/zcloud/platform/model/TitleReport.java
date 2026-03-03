package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Title search report for a closing.
 * Anti-pattern: issues as TEXT blob, inconsistent date types.
 */
@Data
@Entity
@Table(name = "title_reports")
public class TitleReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closing_id", nullable = false)
    @JsonIgnore
    private ClosingDetail closing;

    @Column(name = "title_company")
    private String titleCompany;

    @Column(name = "title_number")
    private String titleNumber;

    // Anti-pattern: should be enum (CLEAR, ISSUES_FOUND, PENDING, RESOLVED)
    private String status;

    // Anti-pattern: issues as TEXT instead of proper related table
    @Column(columnDefinition = "TEXT")
    private String issues;

    @Column(name = "lien_amount")
    private BigDecimal lienAmount;

    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "owner_name")
    private String ownerName;

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
