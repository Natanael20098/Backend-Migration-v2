package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Individual underwriting conditions that must be satisfied.
 * Anti-pattern: documentId is a loose UUID FK with no enforced relationship.
 */
@Data
@Entity
@Table(name = "underwriting_conditions")
public class UnderwritingCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decision_id", nullable = false)
    private UnderwritingDecision decision;

    // Anti-pattern: should be enum (PRIOR_TO_DOCS, PRIOR_TO_FUNDING, PRIOR_TO_CLOSING)
    @Column(name = "condition_type")
    private String conditionType;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Anti-pattern: should be enum (PENDING, RECEIVED, WAIVED, SATISFIED)
    private String status;

    @Column(name = "satisfied_date")
    private LocalDate satisfiedDate;

    // Anti-pattern: loose FK - UUID reference to client_documents but no @ManyToOne
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "due_date")
    private LocalDate dueDate;

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
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
