package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Brokerage / real estate firm entity.
 * Anti-pattern: no relationship back to agents, inconsistent column annotations.
 */
@Data
@Entity
@Table(name = "brokerages")
public class Brokerage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(name = "city")
    private String city;

    private String state;

    @Column(name = "zip_code", length = 10)
    private String zipCode;

    private String phone;

    @Column(unique = true)
    private String email;

    @Column(name = "license_number")
    private String licenseNumber;

    private String website;

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
