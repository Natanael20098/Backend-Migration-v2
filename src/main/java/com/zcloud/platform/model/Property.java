package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core property entity. Also used directly in API responses (no DTO).
 * NOTE: images are EAGER loaded which will cause N+1 issues at scale.
 */
@Data
@Entity
@Table(name = "properties")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(name = "city")
    private String city;

    private String state;

    @Column(name = "zip_code", length = 10)
    private String zipCode;

    private String county;

    @Column(name = "latitude")
    private BigDecimal latitude;

    @Column(name = "longitude")
    private BigDecimal longitude;

    private Integer beds;

    private BigDecimal baths;

    @Column(name = "sqft")
    private Integer sqft;

    @Column(name = "lot_size")
    private BigDecimal lotSize;

    @Column(name = "year_built")
    private Integer yearBuilt;

    @Column(name = "property_type")
    private String propertyType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "parking_spaces")
    private Integer parkingSpaces;

    @Column(name = "garage_type")
    private String garageType;

    @Column(name = "hoa_fee")
    private BigDecimal hoaFee;

    private String zoning;

    @Column(name = "parcel_number")
    private String parcelNumber;

    @Column(name = "last_sold_price")
    private BigDecimal lastSoldPrice;

    @Column(name = "last_sold_date")
    private LocalDate lastSoldDate;

    @Column(name = "current_tax_amount")
    private BigDecimal currentTaxAmount;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    // Anti-pattern: transient runtime state on entity
    @Transient
    private Double estimatedValue;

    // Anti-pattern: EAGER loading a collection - causes N+1
    @OneToMany(mappedBy = "property", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<PropertyImage> images = new ArrayList<>();

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
