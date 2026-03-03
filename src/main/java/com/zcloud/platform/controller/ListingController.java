package com.zcloud.platform.controller;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.*;
import com.zcloud.platform.repository.ListingRepository;
import com.zcloud.platform.repository.OfferRepository;
import com.zcloud.platform.repository.OpenHouseRepository;
import com.zcloud.platform.repository.ShowingRepository;
import com.zcloud.platform.service.MasterService;
import com.zcloud.platform.service.NotificationHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ListingController -- handles MLS listing management, open houses, showings, and offers.
 *
 * Anti-patterns:
 * - Status change triggers inline notification sending and commission calculation
 * - Open houses created through direct repository call (bypasses service)
 * - Showing and offer queries bypass MasterService
 * - Mixed use of service and repository for similar operations
 * - Business logic for commission calculation done inline when status=SOLD
 * - No pagination on any list endpoint
 */
@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private static final Logger log = LoggerFactory.getLogger(ListingController.class);

    @Autowired
    private MasterService masterService;

    // Anti-pattern: injects repositories to bypass service layer
    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private OpenHouseRepository openHouseRepository;

    @Autowired
    private ShowingRepository showingRepository;

    @Autowired
    private OfferRepository offerRepository;

    // Anti-pattern: notification helper injected into controller for inline notifications
    @Autowired
    private NotificationHelper notificationHelper;

    // ==================== LIST / SEARCH ====================

    /**
     * List all listings with optional filters.
     * Anti-pattern: loads all then filters in memory for some params, uses repo query for others.
     * No consistent approach to filtering.
     */
    @GetMapping
    public ResponseEntity<List<Listing>> listListings(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) UUID agentId) {

        List<Listing> results;

        // Anti-pattern: inconsistent filtering — uses repo method for price range but
        // in-memory filtering for other params
        if (minPrice != null && maxPrice != null) {
            results = listingRepository.findByListPriceBetween(minPrice, maxPrice);
        } else if (status != null) {
            results = listingRepository.findByStatus(status);
        } else if (agentId != null) {
            results = listingRepository.findByAgentId(agentId);
        } else {
            // Anti-pattern: loads ALL listings — could be thousands
            results = listingRepository.findAll();
        }

        // Anti-pattern: post-filter in Java for params that weren't handled above
        if (city != null && !city.isEmpty()) {
            results = results.stream()
                    .filter(l -> l.getProperty() != null && city.equalsIgnoreCase(l.getProperty().getCity()))
                    .collect(Collectors.toList());
        }
        if (status != null && minPrice != null) {
            // Anti-pattern: filters status AGAIN because the price branch didn't filter by status
            results = results.stream()
                    .filter(l -> status.equals(l.getStatus()))
                    .collect(Collectors.toList());
        }
        if (agentId != null && status != null) {
            results = results.stream()
                    .filter(l -> status.equals(l.getStatus()))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(results);
    }

    // ==================== GET BY ID ====================

    /**
     * Get listing by ID with property details.
     * Anti-pattern: goes to repo directly, returns full entity graph.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getListing(@PathVariable UUID id) {
        Optional<Listing> listingOpt = listingRepository.findById(id);
        if (listingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Listing not found", "id", id.toString()));
        }

        Listing listing = listingOpt.get();

        // Anti-pattern: compute days on market in controller (duplicated from entity @PostLoad)
        if (listing.getListedDate() != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(listing.getListedDate(), LocalDate.now());
            listing.setDaysOnMarket((int) days);
        }

        // Anti-pattern: manually count offers and showings and stuff them into a Map response
        // instead of returning a proper DTO
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("listing", listing);
        response.put("offerCount", offerRepository.countPendingOffersByListing(id));
        response.put("showingCount", showingRepository.findByListingId(id).size());
        response.put("openHouseCount", openHouseRepository.findByListingId(id).size());

        return ResponseEntity.ok(response);
    }

    // ==================== CREATE ====================

    /**
     * Create a new listing.
     * Anti-pattern: uses MasterService for create but repos for reads. No consistency.
     */
    @PostMapping
    public ResponseEntity<?> createListing(@RequestBody Listing listing) {
        try {
            // Anti-pattern: minimal validation compared to PropertyController's 30+ lines
            if (listing.getProperty() == null || listing.getProperty().getId() == null) {
                return ResponseEntity.badRequest().body("Property is required");
            }
            if (listing.getAgent() == null || listing.getAgent().getId() == null) {
                return ResponseEntity.badRequest().body("Agent is required");
            }
            if (listing.getListPrice() == null || listing.getListPrice().compareTo(BigDecimal.ZERO) <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "List price must be positive"));
            }

            Listing saved = masterService.createListing(listing);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);

        } catch (RuntimeException e) {
            log.error("Error creating listing: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== UPDATE ====================

    /**
     * Update listing details.
     * Anti-pattern: uses MasterService for update — at least this is consistent with create.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateListing(@PathVariable UUID id, @RequestBody Listing updates) {
        try {
            Listing updated = masterService.updateListing(id, updates);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    // ==================== STATUS CHANGE ====================

    /**
     * Change listing status (ACTIVE, PENDING, SOLD, WITHDRAWN).
     *
     * Anti-patterns:
     * - Status transition validation done as a giant if/else in the controller
     * - When status=SOLD, calculates commissions inline in the controller
     * - Sends notifications inline
     * - Business logic that belongs in service layer
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> changeListingStatus(@PathVariable UUID id,
                                                  @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        if (newStatus == null || newStatus.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Status is required"));
        }

        // Anti-pattern: status validation with hardcoded strings in controller
        List<String> validStatuses = Arrays.asList(
                Constants.LISTING_ACTIVE, Constants.LISTING_PENDING,
                Constants.LISTING_SOLD, Constants.LISTING_WITHDRAWN
        );
        if (!validStatuses.contains(newStatus)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid status",
                    "validStatuses", validStatuses
            ));
        }

        Optional<Listing> listingOpt = listingRepository.findById(id);
        if (listingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Listing listing = listingOpt.get();
        String oldStatus = listing.getStatus();

        // Anti-pattern: status transition validation — business logic in controller
        if (Constants.LISTING_SOLD.equals(oldStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot change status of a SOLD listing"));
        }
        if (Constants.LISTING_WITHDRAWN.equals(oldStatus) && !Constants.LISTING_ACTIVE.equals(newStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Withdrawn listings can only be re-activated"));
        }

        listing.setStatus(newStatus);
        Listing saved = listingRepository.save(listing);

        // Anti-pattern: inline notification sending — business logic in controller
        if (listing.getAgent() != null) {
            notificationHelper.notifyAgent(listing.getAgent().getId(),
                    "Listing " + listing.getMlsNumber() + " status changed from " +
                            oldStatus + " to " + newStatus);
        }

        // Anti-pattern: when status changes to SOLD, calculate commissions INLINE in the controller
        // This is massive business logic that belongs in a CommissionService
        if (Constants.LISTING_SOLD.equals(newStatus)) {
            try {
                log.info("Listing {} marked as SOLD — calculating commissions inline in controller", id);

                BigDecimal salePrice = listing.getListPrice();
                if (salePrice == null) {
                    salePrice = listing.getOriginalPrice(); // Anti-pattern: fallback to original price
                }

                if (salePrice != null && listing.getAgent() != null) {
                    BigDecimal totalCommission = salePrice
                            .multiply(BigDecimal.valueOf(Constants.DEFAULT_COMMISSION_RATE))
                            .setScale(2, RoundingMode.HALF_UP);

                    BigDecimal listingAgentCommission = totalCommission
                            .multiply(BigDecimal.valueOf(Constants.LISTING_AGENT_SPLIT))
                            .setScale(2, RoundingMode.HALF_UP);

                    log.info("Listing {} SOLD — Total commission: ${}, Listing agent: ${}",
                            id, totalCommission, listingAgentCommission);

                    // Anti-pattern: notify about commission — PII/financial data in notification
                    notificationHelper.notifyAgent(listing.getAgent().getId(),
                            "Congratulations! Listing " + listing.getMlsNumber() +
                                    " has SOLD! Your estimated commission: $" + listingAgentCommission);
                }

                // Anti-pattern: also notify all active offers that the listing is sold
                List<Offer> pendingOffers = offerRepository.findByListingId(id);
                for (Offer offer : pendingOffers) {
                    if ("SUBMITTED".equals(offer.getStatus()) || "COUNTERED".equals(offer.getStatus())) {
                        offer.setStatus("EXPIRED");
                        // Anti-pattern: can't save the offer because we'd need to go through
                        // MasterService.updateOfferStatus, but we're doing this inline
                        // This was left as a TODO that nobody completed
                        log.warn("Should expire offer {} for sold listing {} but save not implemented here", offer.getId(), id);
                    }
                }

            } catch (Exception e) {
                // Anti-pattern: swallow commission calculation errors — the status change succeeds
                // but commissions may not be created
                log.error("Error calculating commissions for sold listing {}: {}", id, e.getMessage());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("listing", saved);
        response.put("previousStatus", oldStatus);
        response.put("newStatus", newStatus);
        return ResponseEntity.ok(response);
    }

    // ==================== OPEN HOUSES ====================

    /**
     * Schedule an open house for a listing.
     * Anti-pattern: goes directly to OpenHouseRepository, bypasses MasterService entirely.
     */
    @PostMapping("/{id}/open-houses")
    public ResponseEntity<?> scheduleOpenHouse(@PathVariable UUID id, @RequestBody OpenHouse openHouse) {
        Optional<Listing> listingOpt = listingRepository.findById(id);
        if (listingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Listing not found");
        }

        Listing listing = listingOpt.get();

        // Anti-pattern: validation in controller
        if (!Constants.LISTING_ACTIVE.equals(listing.getStatus())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Can only schedule open houses for ACTIVE listings"));
        }

        if (openHouse.getEventDate() == null) {
            return ResponseEntity.badRequest().body("Event date is required");
        }
        if (openHouse.getEventDate().isBefore(LocalDate.now())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot schedule open house in the past"));
        }

        openHouse.setListing(listing);

        // Anti-pattern: default the agent to the listing agent if not specified
        if (openHouse.getAgent() == null) {
            openHouse.setAgent(listing.getAgent());
        }

        // Anti-pattern: default times — business logic in controller
        if (openHouse.getStartTime() == null) {
            openHouse.setStartTime("10:00 AM");
        }
        if (openHouse.getEndTime() == null) {
            openHouse.setEndTime("2:00 PM");
        }

        // Anti-pattern: saves directly via repo — bypasses any service-layer logic
        OpenHouse saved = openHouseRepository.save(openHouse);

        // Anti-pattern: inline notification
        if (listing.getAgent() != null) {
            notificationHelper.notifyAgent(listing.getAgent().getId(),
                    "Open house scheduled for " + listing.getMlsNumber() + " on " + saved.getEventDate());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ==================== SHOWINGS ====================

    /**
     * Get all showings for a listing.
     * Anti-pattern: bypasses MasterService, goes directly to ShowingRepository.
     */
    @GetMapping("/{id}/showings")
    public ResponseEntity<?> getShowings(@PathVariable UUID id) {
        // Anti-pattern: doesn't verify listing exists before querying showings
        List<Showing> showings = showingRepository.findByListingId(id);
        return ResponseEntity.ok(showings); // Anti-pattern: returns entities directly
    }

    // ==================== OFFERS ====================

    /**
     * Get all offers for a listing.
     * Anti-pattern: bypasses MasterService, goes directly to OfferRepository.
     * Returns entities with full graph (buyer client, buyer agent, etc.)
     */
    @GetMapping("/{id}/offers")
    public ResponseEntity<?> getOffers(@PathVariable UUID id) {
        // Anti-pattern: doesn't verify listing exists
        List<Offer> offers = offerRepository.findByListingId(id);

        // Anti-pattern: inline sorting instead of ORDER BY in the repo query
        offers.sort((a, b) -> {
            if (a.getOfferAmount() == null) return 1;
            if (b.getOfferAmount() == null) return -1;
            return b.getOfferAmount().compareTo(a.getOfferAmount()); // highest first
        });

        // Anti-pattern: build a summary Map instead of returning a typed DTO
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("listingId", id);
        response.put("totalOffers", offers.size());
        response.put("offers", offers);

        // Anti-pattern: inline analytics in the controller
        if (!offers.isEmpty()) {
            BigDecimal highestOffer = offers.stream()
                    .map(Offer::getOfferAmount)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            BigDecimal lowestOffer = offers.stream()
                    .map(Offer::getOfferAmount)
                    .filter(Objects::nonNull)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            BigDecimal avgOffer = offers.stream()
                    .map(Offer::getOfferAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(offers.size()), 2, RoundingMode.HALF_UP);

            response.put("highestOffer", highestOffer);
            response.put("lowestOffer", lowestOffer);
            response.put("averageOffer", avgOffer);

            long pendingCount = offers.stream()
                    .filter(o -> "SUBMITTED".equals(o.getStatus()))
                    .count();
            response.put("pendingOffers", pendingCount);
        }

        return ResponseEntity.ok(response);
    }
}
