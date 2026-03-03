package com.zcloud.platform.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Open house events for listings.
 * Anti-pattern: start/end time as Strings instead of LocalTime.
 */
@Data
@Entity
@Table(name = "open_houses")
public class OpenHouse {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(name = "date", nullable = false)
    private LocalDate eventDate;

    // Anti-pattern: times stored as strings instead of LocalTime
    @Column(name = "start_time")
    private String startTime;

    @Column(name = "end_time")
    private String endTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "attendee_count")
    private Integer attendeeCount;

    @Column(name = "property_address")
    private String propertyAddress;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = new Timestamp(System.currentTimeMillis());
    }
}
