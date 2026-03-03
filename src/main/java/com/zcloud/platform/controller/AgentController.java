package com.zcloud.platform.controller;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.Agent;
import com.zcloud.platform.model.AgentLicense;
import com.zcloud.platform.model.Commission;
import com.zcloud.platform.model.Listing;
import com.zcloud.platform.repository.AgentLicenseRepository;
import com.zcloud.platform.repository.AgentRepository;
import com.zcloud.platform.repository.BrokerageRepository;
import com.zcloud.platform.repository.CommissionRepository;
import com.zcloud.platform.service.MasterService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AgentController -- handles agent CRUD and related sub-resources.
 *
 * Anti-patterns:
 * - Uses MasterService for create/update but goes directly to repositories for read operations
 * - License validation logic done inline in controller
 * - No pagination on list endpoints
 * - Returns entities directly (no DTOs)
 * - Inconsistent error handling
 * - Business logic for license expiration checking in controller
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    @Autowired
    private MasterService masterService;

    // Anti-pattern: injects repositories to bypass service for reads
    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AgentLicenseRepository agentLicenseRepository;

    @Autowired
    private CommissionRepository commissionRepository;

    @Autowired
    private BrokerageRepository brokerageRepository;

    // ==================== LIST / SEARCH ====================

    /**
     * List all agents with optional filters.
     * Anti-pattern: bypasses MasterService, goes directly to repository.
     * Uses different repo methods depending on which filter is provided.
     */
    @GetMapping
    public ResponseEntity<?> listAgents(
            @RequestParam(required = false) UUID brokerageId,
            @RequestParam(required = false) Boolean isActive) {

        List<Agent> agents;

        // Anti-pattern: conditional repo method calls — fragile logic
        if (brokerageId != null && isActive != null && isActive) {
            agents = agentRepository.findActiveAgentsByBrokerage(brokerageId);
        } else if (brokerageId != null) {
            agents = agentRepository.findByBrokerageId(brokerageId);
        } else if (isActive != null && isActive) {
            agents = agentRepository.findByIsActiveTrue();
        } else {
            // Anti-pattern: loads ALL agents — unbounded
            agents = agentRepository.findAll();
        }

        // Anti-pattern: post-filter in memory for inactive agents if isActive=false
        // because there's no findByIsActiveFalse in the repo
        if (isActive != null && !isActive) {
            agents = agents.stream()
                    .filter(a -> a.getIsActive() == null || !a.getIsActive())
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(agents);
    }

    // ==================== GET BY ID ====================

    /**
     * Get agent by ID.
     * Anti-pattern: goes directly to repo, bypasses service. Returns entity directly.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getAgent(@PathVariable UUID id) {
        // Anti-pattern: uses repo directly instead of masterService.getAgent(id)
        Optional<Agent> agentOpt = agentRepository.findById(id);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Agent not found", "id", id.toString()));
        }

        Agent agent = agentOpt.get();

        // Anti-pattern: inline computation — count active listings and stuff into transient field
        // This calculates totalSales by querying another repo in the controller
        try {
            List<Commission> commissions = commissionRepository.findByAgentId(id);
            BigDecimal totalSales = commissions.stream()
                    .filter(c -> "PAID".equals(c.getStatus()))
                    .map(Commission::getAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            agent.setTotalSales(totalSales);
        } catch (Exception e) {
            log.error("Failed to calculate total sales for agent {}: {}", id, e.getMessage());
            // Anti-pattern: swallow and continue — agent returned without totalSales
        }

        return ResponseEntity.ok(agent);
    }

    // ==================== CREATE ====================

    /**
     * Create a new agent.
     * Anti-pattern: uses MasterService for create but repos for everything else.
     */
    @PostMapping
    public ResponseEntity<?> createAgent(@RequestBody Agent agent) {
        try {
            // Anti-pattern: inline validation in controller
            if (agent.getFirstName() == null || agent.getFirstName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("First name is required");
            }
            if (agent.getLastName() == null || agent.getLastName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Last name is required");
            }
            if (agent.getEmail() == null || !agent.getEmail().contains("@")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Valid email is required"));
            }

            // Anti-pattern: check for duplicate email — repo access in controller
            Optional<Agent> existing = agentRepository.findByEmail(agent.getEmail());
            if (existing.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Email already in use: " + agent.getEmail()));
            }

            // Anti-pattern: validate brokerage exists by going to repo directly
            if (agent.getBrokerage() != null && agent.getBrokerage().getId() != null) {
                boolean brokerageExists = brokerageRepository.findById(agent.getBrokerage().getId()).isPresent();
                if (!brokerageExists) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Brokerage not found: " + agent.getBrokerage().getId()));
                }
            }

            // Anti-pattern: set defaults in controller
            if (agent.getIsActive() == null) {
                agent.setIsActive(true);
            }
            if (agent.getCommissionRate() == null) {
                agent.setCommissionRate(BigDecimal.valueOf(Constants.DEFAULT_COMMISSION_RATE)); // hardcoded default
            }

            Agent saved = masterService.createAgent(agent);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);

        } catch (RuntimeException e) {
            log.error("Error creating agent: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== UPDATE ====================

    /**
     * Update an agent.
     * Anti-pattern: uses MasterService for update — inconsistent with GET which uses repo.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateAgent(@PathVariable UUID id, @RequestBody Agent updates) {
        try {
            Agent updated = masterService.updateAgent(id, updates);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating agent: " + e.getMessage());
        }
    }

    // ==================== LICENSES ====================

    /**
     * Get agent licenses.
     * Anti-pattern: bypasses service, goes directly to AgentLicenseRepository.
     * Includes inline business logic for license status validation.
     */
    @GetMapping("/{id}/licenses")
    public ResponseEntity<?> getAgentLicenses(@PathVariable UUID id) {
        // Anti-pattern: doesn't check if agent exists first
        List<AgentLicense> licenses = agentLicenseRepository.findByAgentId(id);

        // Anti-pattern: inline business logic — check for expired licenses and update status
        // This belongs in a scheduled job or service layer, not in a GET endpoint
        LocalDate today = LocalDate.now();
        boolean anyUpdated = false;

        for (AgentLicense license : licenses) {
            // Anti-pattern: mutates data during a GET request!
            if (license.getExpiryDate() != null && license.getExpiryDate().isBefore(today)) {
                if (!"EXPIRED".equals(license.getStatus()) && !"REVOKED".equals(license.getStatus())) {
                    log.warn("License {} for agent {} is expired (expired: {}) — updating status inline",
                            license.getId(), id, license.getExpiryDate());
                    license.setStatus("EXPIRED");
                    agentLicenseRepository.save(license); // Anti-pattern: writing during a GET
                    anyUpdated = true;
                }
            }

            // Anti-pattern: additional validation — check if license is expiring within 30 days
            if (license.getExpiryDate() != null
                    && license.getExpiryDate().isAfter(today)
                    && license.getExpiryDate().isBefore(today.plusDays(30))) {
                log.info("License {} for agent {} expires within 30 days: {}",
                        license.getId(), id, license.getExpiryDate());
                // Anti-pattern: no notification sent, just a log — easy to miss
            }
        }

        if (anyUpdated) {
            // Anti-pattern: reload licenses after mutation
            licenses = agentLicenseRepository.findByAgentId(id);
        }

        // Anti-pattern: return a Map with metadata instead of just the list
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", id);
        response.put("licenses", licenses);
        response.put("totalLicenses", licenses.size());
        response.put("activeLicenses", licenses.stream()
                .filter(l -> "ACTIVE".equals(l.getStatus())).count());
        response.put("expiredLicenses", licenses.stream()
                .filter(l -> "EXPIRED".equals(l.getStatus())).count());

        return ResponseEntity.ok(response);
    }

    // ==================== COMMISSIONS ====================

    /**
     * Get agent commissions.
     * Anti-pattern: bypasses service, goes directly to CommissionRepository.
     * Includes inline analytics/aggregation in the controller.
     */
    @GetMapping("/{id}/commissions")
    public ResponseEntity<?> getAgentCommissions(@PathVariable UUID id) {
        // Anti-pattern: doesn't check if agent exists
        List<Commission> commissions = commissionRepository.findByAgentId(id);

        // Anti-pattern: inline aggregation — business/analytics logic in controller
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", id);
        response.put("commissions", commissions);

        BigDecimal totalEarned = commissions.stream()
                .filter(c -> "PAID".equals(c.getStatus()))
                .map(Commission::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPending = commissions.stream()
                .filter(c -> "PENDING".equals(c.getStatus()))
                .map(Commission::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long listingSideCount = commissions.stream()
                .filter(c -> "LISTING".equals(c.getType())).count();
        long buyerSideCount = commissions.stream()
                .filter(c -> "BUYER".equals(c.getType())).count();

        response.put("totalEarned", totalEarned);
        response.put("totalPending", totalPending);
        response.put("totalTransactions", commissions.size());
        response.put("listingSideCount", listingSideCount);
        response.put("buyerSideCount", buyerSideCount);

        return ResponseEntity.ok(response);
    }

    // ==================== AGENT LISTINGS ====================

    /**
     * Get agent's listings.
     * Anti-pattern: uses MasterService for this one but repos for other read endpoints.
     * Inconsistency in where data comes from.
     */
    @GetMapping("/{id}/listings")
    public ResponseEntity<?> getAgentListings(@PathVariable UUID id) {
        // Anti-pattern: first verify agent exists using repo (not service)
        Optional<Agent> agentOpt = agentRepository.findById(id);
        if (agentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Anti-pattern: calls service method that internally just calls repo anyway
        // Could have just called listingRepository.findByAgentId(id) directly like other reads
        Agent agent = masterService.getAgent(id);
        if (agent == null) {
            // Anti-pattern: redundant null check — we already verified via repo above
            return ResponseEntity.notFound().build();
        }

        // Anti-pattern: actually goes to the repo for listings because MasterService
        // doesn't have a getListingsByAgent method, so this controller method
        // pretends to use the service pattern but doesn't
        List<Listing> listings;
        try {
            // Anti-pattern: uses a protected/internal method signature convention from the service
            // that doesn't exist, so falls back to manual retrieval
            listings = new ArrayList<>();
            // Load from repo since service doesn't expose this
            com.zcloud.platform.repository.ListingRepository listingRepo =
                    org.springframework.beans.factory.BeanFactoryUtils
                            .class.getClassLoader() != null ? null : null;
            // The above line is dead code — a developer started to wire this up
            // then gave up and just used the already-injected service

            // Anti-pattern: actually just query all listings and filter — HORRIBLE approach
            // This was supposed to be temporary but became permanent
        } catch (Exception e) {
            log.error("Error loading listings for agent {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to load listings"));
        }

        // Anti-pattern: fall back to returning empty because the proper implementation
        // was never completed — just returns the agent details with a placeholder
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agent", agent);
        response.put("message", "Use /api/listings?agentId=" + id + " for full listing data");
        // Anti-pattern: tells the caller to use a different endpoint instead of actually working

        return ResponseEntity.ok(response);
    }
}
