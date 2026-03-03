package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Client entity - buyers and sellers. Also serves as API response DTO.
 * Anti-pattern: SSN stored (even encrypted) on the entity, entity as DTO,
 * mixed date types, @Data with JPA.
 */
@Data
@Entity
@Table(name = "clients")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Client {

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

    // Anti-pattern: sensitive data on entity, relying on @JsonIgnore for security
    @Column(name = "ssn_encrypted")
    @JsonIgnore
    private String ssnEncrypted;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    private String city;

    @Column(name = "state", length = 2)
    private String state;

    @Column(name = "zip_code", length = 10)
    private String zipCode;

    // Anti-pattern: should be enum BUYER, SELLER, BOTH
    @Column(name = "client_type")
    private String clientType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_agent_id")
    private Agent assignedAgent;

    @Column(name = "agent_name")
    private String agentName;

    @Column(name = "agent_email")
    private String agentEmail;

    @Column(name = "agent_phone")
    private String agentPhone;

    @Column(name = "preferred_contact_method")
    private String preferredContactMethod;

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

    // Anti-pattern: business logic in entity
    public String getFullName() {
        return firstName + " " + lastName;
    }
}
