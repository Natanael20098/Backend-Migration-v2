package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Property tax records by year.
 * Anti-pattern: @Data with JPA entity causes equals/hashCode issues.
 */
@Data
@Entity
@Table(name = "property_tax_records")
public class PropertyTaxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    @JsonIgnore
    private Property property;

    @Column(name = "year", nullable = false)
    private Integer taxYear;

    @Column(name = "assessed_value", precision = 15, scale = 2)
    private BigDecimal assessedValue;

    @Column(name = "tax_amount", precision = 12, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    @Column(columnDefinition = "TEXT")
    private String exemptions;

    private Boolean paid;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
