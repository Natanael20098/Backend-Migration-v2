package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Appraisal report with valuation.
 * Anti-pattern: EAGER loads full appraisal order chain,
 * reportData as TEXT blob.
 */
@Data
@Entity
@Table(name = "appraisal_reports")
public class AppraisalReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: EAGER cascades into order -> loan -> borrower -> property
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "appraisal_order_id", nullable = false)
    private AppraisalOrder appraisalOrder;

    @Column(name = "appraised_value", precision = 15, scale = 2)
    private BigDecimal appraisedValue;

    // Anti-pattern: should be enum (SALES_COMPARISON, COST, INCOME)
    @Column(name = "approach_used")
    private String approachUsed;

    // Anti-pattern: should be enum (EXCELLENT, GOOD, FAIR, POOR)
    @Column(name = "condition_rating")
    private String conditionRating;

    @Column(name = "quality_rating")
    private String qualityRating;

    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    // Anti-pattern: full report as text blob
    @Column(name = "report_data", columnDefinition = "TEXT")
    @JsonIgnore
    private String reportData;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "property_sqft")
    private Integer propertySqft;

    @Column(name = "property_beds")
    private Integer propertyBeds;

    @Column(name = "property_baths")
    private BigDecimal propertyBaths;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
