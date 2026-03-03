package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * MLS listing entity. Doubles as the API response object.
 * Anti-patterns: EAGER loading relationships, mixed date types, entity as DTO.
 */
@Data
@Entity
@Table(name = "listings")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Anti-pattern: EAGER fetch on ManyToOne - loads full property graph
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    // Anti-pattern: EAGER fetch pulls agent + brokerage
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(name = "list_price", precision = 15, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "original_price", precision = 15, scale = 2)
    private BigDecimal originalPrice;

    private String status;  // should be enum: ACTIVE, PENDING, SOLD, EXPIRED, WITHDRAWN

    @Column(name = "mls_number", unique = true)
    private String mlsNumber;

    @Column(name = "listed_date")
    private LocalDate listedDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "sold_date")
    private LocalDate soldDate;

    @Column(name = "sold_price")
    private BigDecimal soldPrice;

    @Column(name = "days_on_market")
    private Integer daysOnMarket;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "property_city")
    private String propertyCity;

    @Column(name = "property_state")
    private String propertyState;

    @Column(name = "property_zip")
    private String propertyZip;

    @Column(name = "property_beds")
    private Integer propertyBeds;

    @Column(name = "property_baths")
    private BigDecimal propertyBaths;

    @Column(name = "property_sqft")
    private Integer propertySqft;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "virtual_tour_url")
    private String virtualTourUrl;

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
