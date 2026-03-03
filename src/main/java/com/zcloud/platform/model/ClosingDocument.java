package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Documents associated with a closing (deed, mortgage note, disclosures, etc.).
 * Anti-pattern: filePath in DB, inconsistent naming with ClientDocument.
 */
@Data
@Entity
@Table(name = "closing_documents")
public class ClosingDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closing_id", nullable = false)
    @JsonIgnore
    private ClosingDetail closing;

    // Anti-pattern: should be enum (DEED, MORTGAGE_NOTE, CLOSING_DISCLOSURE, TITLE_INSURANCE, etc.)
    @Column(name = "document_type")
    private String documentType;

    @Column(name = "file_name")
    private String fileName;

    // Anti-pattern: filesystem path in database
    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    private Boolean signed;

    @Column(name = "signed_date")
    private Timestamp signedDate;

    @Column(name = "signed_by")
    private String signedBy;

    private Boolean notarized;

    @Column(name = "notary_name")
    private String notaryName;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
        if (signed == null) {
            signed = false;
        }
        if (notarized == null) {
            notarized = false;
        }
    }
}
