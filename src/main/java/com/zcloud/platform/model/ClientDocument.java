package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Documents uploaded by/for clients (tax returns, pay stubs, etc.).
 * Anti-pattern: filePath stored in DB.
 */
@Data
@Entity
@Table(name = "client_documents")
public class ClientDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    // Anti-pattern: should be enum (TAX_RETURN, PAY_STUB, BANK_STATEMENT, ID, etc.)
    @Column(name = "document_type")
    private String documentType;

    @Column(name = "file_name")
    private String fileName;

    // Anti-pattern: file system path stored in DB
    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "uploaded_at")
    private Timestamp uploadedAt;

    private Boolean verified;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Timestamp verifiedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    protected void onCreate() {
        uploadedAt = new Timestamp(System.currentTimeMillis());
        if (verified == null) {
            verified = false;
        }
    }
}
