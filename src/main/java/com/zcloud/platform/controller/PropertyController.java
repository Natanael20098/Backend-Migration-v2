package com.zcloud.platform.controller;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.AuditLog;
import com.zcloud.platform.model.Property;
import com.zcloud.platform.model.PropertyImage;
import com.zcloud.platform.model.PropertyTaxRecord;
import com.zcloud.platform.repository.PropertyImageRepository;
import com.zcloud.platform.repository.PropertyRepository;
import com.zcloud.platform.repository.PropertyTaxRecordRepository;
import com.zcloud.platform.service.MasterService;
import com.zcloud.platform.util.SecurityUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PropertyController -- handles all property CRUD and related sub-resources.
 *
 * Anti-patterns present:
 * - Bypasses MasterService for multiple operations, going directly to repositories
 * - 30+ lines of inline validation in createProperty
 * - Returns entities directly (no DTOs)
 * - Inconsistent error responses: sometimes Map, sometimes String, sometimes ResponseEntity
 * - Audit logging done inline in the controller
 * - Mixed use of service vs repository for similar operations
 * - No pagination on list endpoints
 */
@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private static final Logger log = LoggerFactory.getLogger(PropertyController.class);

    // Anti-pattern: injects both service AND repositories — bypasses service layer
    @Autowired
    private MasterService masterService;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyImageRepository propertyImageRepository;

    @Autowired
    private PropertyTaxRecordRepository propertyTaxRecordRepository;

    // ==================== LIST / SEARCH ====================

    /**
     * List all properties with optional filters.
     * Anti-pattern: loads ALL properties then filters in Java instead of using
     * database queries. No pagination — could return thousands of records.
     */
    @GetMapping
    public ResponseEntity<?> listProperties(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer minBeds,
            @RequestParam(required = false) Double minBaths,
            @RequestParam(required = false) Integer minSqft,
            @RequestParam(required = false) Integer maxSqft) {

        // Anti-pattern: loads ALL properties then filters in Java — O(n) for every request
        List<Property> allProperties = propertyRepository.findAll();
        log.info("Loaded {} total properties for filtering", allProperties.size());

        // Anti-pattern: chained stream filters instead of database WHERE clause
        List<Property> filtered = allProperties.stream()
                .filter(p -> city == null || city.equalsIgnoreCase(p.getCity()))
                .filter(p -> state == null || state.equalsIgnoreCase(p.getState()))
                .filter(p -> type == null || type.equalsIgnoreCase(p.getPropertyType()))
                .filter(p -> minBeds == null || (p.getBeds() != null && p.getBeds() >= minBeds))
                .filter(p -> minBaths == null || (p.getBaths() != null && p.getBaths().doubleValue() >= minBaths))
                .filter(p -> minSqft == null || (p.getSqft() != null && p.getSqft() >= minSqft))
                .filter(p -> maxSqft == null || (p.getSqft() != null && p.getSqft() <= maxSqft))
                .collect(Collectors.toList());

        // Anti-pattern: returns entity list directly — all fields including images (EAGER loaded)
        return ResponseEntity.ok(filtered);
    }

    // ==================== GET BY ID ====================

    /**
     * Get property by ID with images.
     * Anti-pattern: goes directly to repository instead of through MasterService.
     * Returns entity with all eager-loaded relationships.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getProperty(@PathVariable UUID id) {
        // Anti-pattern: bypasses MasterService.getProperty() — goes directly to repo
        Optional<Property> propertyOpt = propertyRepository.findById(id);

        if (propertyOpt.isEmpty()) {
            // Anti-pattern: inconsistent error format — returns a plain string here
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Property not found: " + id);
        }

        Property property = propertyOpt.get();

        // Anti-pattern: manually loading images even though Property has EAGER fetch
        // This is redundant but was added "just to be safe" by a developer who didn't
        // understand the EAGER fetch annotation on the entity
        List<PropertyImage> images = propertyImageRepository.findByPropertyId(id);
        if (images != null && !images.isEmpty()) {
            property.setImages(images);
        }

        log.debug("Returning property {} with {} images", id, property.getImages().size());

        // Anti-pattern: returns entity directly — no DTO, all fields exposed
        return ResponseEntity.ok(property);
    }

    // ==================== CREATE ====================

    /**
     * Create a new property with extensive inline validation.
     * Anti-pattern: 30+ lines of validation logic that should be in a validator or service.
     * Audit logging done inline in the controller. Mixed use of service and repository.
     */
    @PostMapping
    public ResponseEntity<?> createProperty(@RequestBody Property property) {
        try {
            // Anti-pattern: 30+ lines of inline validation that should be @Valid + validator class
            // or at minimum in the service layer

            if (property.getAddressLine1() == null || property.getAddressLine1().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Address is required",
                        "field", "address"));
            }
            if (property.getAddressLine1().length() < 5) {
                return ResponseEntity.badRequest().body(Map.of("error", "Address must be at least 5 characters",
                        "field", "address"));
            }
            if (property.getAddressLine1().length() > 500) {
                return ResponseEntity.badRequest().body(Map.of("error", "Address must not exceed 500 characters",
                        "field", "address"));
            }

            if (property.getCity() == null || property.getCity().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("City is required"); // Anti-pattern: plain string error
            }

            if (property.getState() == null || property.getState().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "State is required"));
            }
            // Anti-pattern: state validation with hardcoded length check — doesn't validate actual state codes
            if (property.getState().length() != 2) {
                return ResponseEntity.badRequest().body(Map.of("error", "State must be 2-letter code",
                        "field", "state", "value", property.getState()));
            }

            if (property.getZipCode() != null && !property.getZipCode().matches("\\d{5}(-\\d{4})?")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid zip code format"));
            }

            // Anti-pattern: property type validation with hardcoded list
            if (property.getPropertyType() != null) {
                List<String> validTypes = Arrays.asList(
                        Constants.PROP_SINGLE_FAMILY, Constants.PROP_CONDO,
                        Constants.PROP_TOWNHOUSE, Constants.PROP_MULTI_FAMILY,
                        Constants.PROP_COMMERCIAL, Constants.PROP_LAND
                );
                if (!validTypes.contains(property.getPropertyType())) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Invalid property type",
                            "validTypes", validTypes,
                            "received", property.getPropertyType()
                    ));
                }
            } else {
                property.setPropertyType(Constants.PROP_SINGLE_FAMILY); // Anti-pattern: hardcoded default
            }

            // Anti-pattern: numeric validation for beds, baths, sqft
            if (property.getBeds() != null && (property.getBeds() < 0 || property.getBeds() > 50)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Beds must be between 0 and 50"));
            }
            if (property.getBaths() != null && (property.getBaths().doubleValue() < 0 || property.getBaths().doubleValue() > 30)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Baths must be between 0 and 30"));
            }
            if (property.getSqft() != null && (property.getSqft() < 100 || property.getSqft() > 100000)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Square footage must be between 100 and 100,000"));
            }

            // Anti-pattern: year built validation with magic numbers
            if (property.getYearBuilt() != null) {
                int currentYear = java.time.Year.now().getValue();
                if (property.getYearBuilt() < 1600 || property.getYearBuilt() > currentYear + 2) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Year built must be between 1600 and " + (currentYear + 2)));
                }
            }

            // Anti-pattern: lat/lon validation inline
            if (property.getLatitude() != null && (property.getLatitude().doubleValue() < -90 || property.getLatitude().doubleValue() > 90)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Latitude must be between -90 and 90"));
            }
            if (property.getLongitude() != null && (property.getLongitude().doubleValue() < -180 || property.getLongitude().doubleValue() > 180)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Longitude must be between -180 and 180"));
            }

            // Anti-pattern: uppercase state code inline
            property.setState(property.getState().toUpperCase());

            // Anti-pattern: use service for actual save (inconsistent with GET which uses repo)
            Property saved = masterService.createProperty(property);

            // Anti-pattern: DUPLICATE audit logging — MasterService.createProperty already logs audit,
            // but the controller does it again because the developer "wasn't sure" if the service did it
            try {
                AuditLog audit = new AuditLog();
                audit.setAction("CREATE");
                audit.setResourceType("Property");
                audit.setResourceId(saved.getId() != null ? saved.getId().toString() : null);
                audit.setNewValue("Address: " + saved.getAddressLine1() + ", City: " + saved.getCity());
                audit.setIpAddress("0.0.0.0"); // Anti-pattern: hardcoded IP
                // Anti-pattern: can't save audit log because we don't have AuditLogRepository injected
                // This was copy-pasted from another controller and nobody noticed it doesn't work
                log.info("AUDIT: Created property {} at {}", saved.getId(), saved.getAddressLine1());
            } catch (Exception e) {
                log.error("Failed to log audit for property creation: {}", e.getMessage());
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(saved);

        } catch (RuntimeException e) {
            // Anti-pattern: catches RuntimeException and returns 500 with raw exception message
            log.error("Error creating property: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== UPDATE ====================

    /**
     * Update a property.
     * Anti-pattern: uses MasterService for update but uses repository for GET.
     * Inconsistent with create which does inline validation.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateProperty(@PathVariable UUID id, @RequestBody Property updates) {
        try {
            // Anti-pattern: no validation here, unlike create which has 30+ lines
            // "We'll add validation later" — it was never added
            Property updated = masterService.updateProperty(id, updates);
            if (updated == null) {
                // Anti-pattern: returns Map here but GET returns plain string for 404
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Property not found", "id", id.toString()));
            }
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            log.error("Error updating property {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating property: " + e.getMessage()); // Anti-pattern: plain string error
        }
    }

    // ==================== DELETE ====================

    /**
     * Delete a property with cascading deletes for images and tax records.
     * Anti-pattern: cascade logic done in the controller instead of service or JPA cascade.
     * Direct repository access for deletion of related entities.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProperty(@PathVariable UUID id) {
        Optional<Property> propertyOpt = propertyRepository.findById(id);
        if (propertyOpt.isEmpty()) {
            return ResponseEntity.notFound().build(); // Anti-pattern: yet another error format — no body
        }

        try {
            // Anti-pattern: manual cascade deletion in controller — should use JPA CascadeType.REMOVE
            // or handle in service layer
            log.info("Deleting property {} and cascading to images and tax records", id);

            // Delete images first
            List<PropertyImage> images = propertyImageRepository.findByPropertyId(id);
            if (!images.isEmpty()) {
                log.info("Deleting {} images for property {}", images.size(), id);
                propertyImageRepository.deleteAll(images);
            }

            // Delete tax records
            List<PropertyTaxRecord> taxRecords = propertyTaxRecordRepository.findByPropertyId(id);
            if (!taxRecords.isEmpty()) {
                log.info("Deleting {} tax records for property {}", taxRecords.size(), id);
                propertyTaxRecordRepository.deleteAll(taxRecords);
            }

            // Finally delete the property — anti-pattern: directly via repo, not service
            propertyRepository.deleteById(id);

            // Anti-pattern: returns different format from other endpoints
            Map<String, Object> response = new HashMap<>();
            response.put("deleted", true);
            response.put("propertyId", id);
            response.put("imagesDeleted", images.size());
            response.put("taxRecordsDeleted", taxRecords.size());
            response.put("timestamp", LocalDateTime.now().toString());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error deleting property {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete property", "details", e.getMessage()));
        }
    }

    // ==================== IMAGES ====================

    /**
     * Add an image to a property.
     * Anti-pattern: bypasses MasterService, goes directly to PropertyImageRepository.
     * No file upload handling — just accepts a JSON body with a URL.
     */
    @PostMapping("/{id}/images")
    public ResponseEntity<?> addImage(@PathVariable UUID id, @RequestBody PropertyImage image) {
        // Anti-pattern: uses repo directly for property lookup but used service for create
        Optional<Property> propertyOpt = propertyRepository.findById(id);
        if (propertyOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Property not found");
        }

        // Anti-pattern: inline validation
        if (image.getUrl() == null || image.getUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Image URL is required"));
        }

        image.setProperty(propertyOpt.get());

        // Anti-pattern: if this is the first image, auto-set as primary
        List<PropertyImage> existingImages = propertyImageRepository.findByPropertyId(id);
        if (existingImages.isEmpty()) {
            image.setIsPrimary(true);
            image.setDisplayOrder(1);
        } else {
            if (image.getIsPrimary() == null) {
                image.setIsPrimary(false);
            }
            if (image.getDisplayOrder() == null) {
                image.setDisplayOrder(existingImages.size() + 1);
            }
            // Anti-pattern: if new image is set as primary, unset the old primary
            if (Boolean.TRUE.equals(image.getIsPrimary())) {
                for (PropertyImage existing : existingImages) {
                    if (Boolean.TRUE.equals(existing.getIsPrimary())) {
                        existing.setIsPrimary(false);
                        propertyImageRepository.save(existing);
                    }
                }
            }
        }

        // Anti-pattern: saves directly via repo, not through service
        PropertyImage saved = propertyImageRepository.save(image);
        log.info("Added image {} to property {}", saved.getId(), id);

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ==================== TAX RECORDS ====================

    /**
     * Get tax records for a property.
     * Anti-pattern: direct repository access, no authorization check.
     */
    @GetMapping("/{id}/tax-records")
    public ResponseEntity<?> getTaxRecords(@PathVariable UUID id) {
        // Anti-pattern: doesn't even check if property exists before querying tax records
        List<PropertyTaxRecord> records = propertyTaxRecordRepository.findByPropertyId(id);

        if (records.isEmpty()) {
            // Anti-pattern: returns 200 with empty message instead of empty list
            // Some callers expect an array, others expect this message
            return ResponseEntity.ok(Map.of(
                    "propertyId", id,
                    "taxRecords", Collections.emptyList(),
                    "message", "No tax records found for this property"
            ));
        }

        return ResponseEntity.ok(records);
    }

    /**
     * Add a tax record to a property.
     * Anti-pattern: direct repo access, inline validation, inconsistent with other endpoints.
     */
    @PostMapping("/{id}/tax-records")
    public ResponseEntity<?> addTaxRecord(@PathVariable UUID id, @RequestBody PropertyTaxRecord record) {
        Optional<Property> propertyOpt = propertyRepository.findById(id);
        if (propertyOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Property not found", "id", id));
        }

        // Anti-pattern: inline validation
        if (record.getTaxYear() == null) {
            return ResponseEntity.badRequest().body("Tax year is required"); // plain string error
        }
        if (record.getTaxYear() < 1900 || record.getTaxYear() > java.time.Year.now().getValue() + 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid tax year")); // Map error
        }

        record.setProperty(propertyOpt.get());

        // Anti-pattern: check for duplicate year — loads all records then filters
        List<PropertyTaxRecord> existing = propertyTaxRecordRepository.findByPropertyId(id);
        boolean yearExists = existing.stream()
                .anyMatch(r -> record.getTaxYear().equals(r.getTaxYear()));
        if (yearExists) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Tax record for year " + record.getTaxYear() + " already exists"));
        }

        PropertyTaxRecord saved = propertyTaxRecordRepository.save(record);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
