package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Agent entity - real estate agents and loan officers use the same table.
 * Anti-patterns: EAGER brokerage load, entity used directly as API response, @Data with JPA.
 */
@Data
@Entity
@Table(name = "agents")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(unique = true)
    private String email;

    private String phone;

    @Column(name = "license_number")
    private String licenseNumber;

    // Anti-pattern: EAGER fetch always loads brokerage
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "brokerage_id")
    private Brokerage brokerage;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "commission_rate")
    private BigDecimal commissionRate;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "brokerage_name")
    private String brokerageName;

    @Column(name = "brokerage_phone")
    private String brokeragePhone;

    // Anti-pattern: runtime computed value on entity
    @Transient
    private BigDecimal totalSales;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
        updatedAt = new Timestamp(System.currentTimeMillis());
        if (isActive == null) {
            isActive = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    // Anti-pattern: business logic in entity
    public String getFullName() {
        return firstName + " " + lastName;
    }
}
