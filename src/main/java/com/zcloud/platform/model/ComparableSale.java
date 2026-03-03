package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Comparable sales used in appraisal valuation.
 * Anti-pattern: adjustments stored as TEXT blob (should be structured).
 */
@Data
@Entity
@Table(name = "comparable_sales")
public class ComparableSale {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appraisal_report_id", nullable = false)
    private AppraisalReport appraisalReport;

    @Column(nullable = false)
    private String address;

    @Column(name = "sale_price", precision = 15, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "sale_date")
    private LocalDate saleDate;

    private Integer sqft;

    private Integer beds;

    private BigDecimal baths;

    @Column(name = "lot_size")
    private BigDecimal lotSize;

    @Column(name = "year_built")
    private Integer yearBuilt;

    @Column(name = "distance_miles")
    private BigDecimal distanceMiles;

    // Anti-pattern: adjustments as TEXT blob instead of structured data
    @Column(columnDefinition = "TEXT")
    private String adjustments;

    @Column(name = "adjusted_price", precision = 15, scale = 2)
    private BigDecimal adjustedPrice;

    @Column(name = "data_source")
    private String dataSource;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
