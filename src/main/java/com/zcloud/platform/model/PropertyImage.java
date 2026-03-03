package com.zcloud.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Images associated with a property listing.
 */
@Data
@Entity
@Table(name = "property_images")
public class PropertyImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    @JsonIgnore  // avoid circular ref but hides useful data
    private Property property;

    @Column(name = "url", nullable = false)
    private String url;

    private String caption;

    @Column(name = "is_primary")
    private Boolean isPrimary;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "uploaded_at")
    private Timestamp uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = new Timestamp(System.currentTimeMillis());
    }
}
