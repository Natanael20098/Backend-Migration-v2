package com.zcloud.platform.controller;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.Client;
import com.zcloud.platform.model.ClientDocument;
import com.zcloud.platform.model.Lead;
import com.zcloud.platform.model.Showing;
import com.zcloud.platform.repository.ClientDocumentRepository;
import com.zcloud.platform.repository.ClientRepository;
import com.zcloud.platform.repository.LeadRepository;
import com.zcloud.platform.repository.ShowingRepository;
import com.zcloud.platform.service.MasterService;
import com.zcloud.platform.util.SecurityUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ClientController -- handles client CRUD, leads, and document management.
 *
 * Anti-patterns:
 * - SSN encryption done inline in controller using SecurityUtils.encryptSsn
 * - Lead creation bypasses MasterService
 * - Password hashing for client portal login done inline
 * - Client entity returned with all fields (relies on @JsonIgnore for SSN protection)
 * - Inconsistent error handling across endpoints
 * - No authorization checks
 */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private static final Logger log = LoggerFactory.getLogger(ClientController.class);

    @Autowired
    private MasterService masterService;

    // Anti-pattern: injects repositories for bypassing service layer
    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ClientDocumentRepository clientDocumentRepository;

    @Autowired
    private ShowingRepository showingRepository;

    // ==================== LIST / SEARCH ====================

    /**
     * List all clients with optional filters.
     * Anti-pattern: goes directly to repo, loads all then filters in memory for some params.
     */
    @GetMapping
    public ResponseEntity<?> listClients(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID assignedAgentId) {

        List<Client> clients;

        if (type != null && assignedAgentId != null) {
            // Anti-pattern: no combined query in repo — load one set and filter for the other
            clients = clientRepository.findByClientType(type);
            clients = clients.stream()
                    .filter(c -> c.getAssignedAgent() != null &&
                            assignedAgentId.equals(c.getAssignedAgent().getId()))
                    .collect(Collectors.toList());
        } else if (type != null) {
            clients = clientRepository.findByClientType(type);
        } else if (assignedAgentId != null) {
            clients = clientRepository.findByAssignedAgentId(assignedAgentId);
        } else {
            // Anti-pattern: loads ALL clients — could be tens of thousands
            clients = clientRepository.findAll();
        }

        // Anti-pattern: returns entity list directly — SSN is protected by @JsonIgnore but
        // all other PII (address, phone, DOB, pre-approval amount) is fully exposed
        return ResponseEntity.ok(clients);
    }

    // ==================== GET BY ID ====================

    /**
     * Get client by ID.
     * Anti-pattern: goes directly to repo. Returns entity with all fields.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getClient(@PathVariable UUID id) {
        Optional<Client> clientOpt = clientRepository.findById(id);
        if (clientOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Client not found: " + id); // Anti-pattern: plain string error
        }

        Client client = clientOpt.get();

        // Anti-pattern: inline — load additional data and stuff into response map
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client", client);

        // Anti-pattern: query related data in the controller
        List<Showing> showings = showingRepository.findByClientId(id);
        response.put("upcomingShowings", showings.stream()
                .filter(s -> "SCHEDULED".equals(s.getStatus()))
                .count());
        response.put("totalShowings", showings.size());

        List<ClientDocument> documents = clientDocumentRepository.findByClientId(id);
        response.put("documentCount", documents.size());
        response.put("unverifiedDocuments", documents.stream()
                .filter(d -> d.getVerified() == null || !d.getVerified())
                .count());

        return ResponseEntity.ok(response);
    }

    // ==================== CREATE ====================

    /**
     * Create a new client with SSN encryption and portal password hashing inline.
     *
     * Anti-patterns:
     * - SSN handling (encryption) done in the controller layer
     * - Password hashing for client portal login done inline
     * - Business logic scattered between controller and service
     * - Sensitive data (SSN) accepted as plain text in the request body
     */
    @PostMapping
    public ResponseEntity<?> createClient(@RequestBody Client client) {
        try {
            // Anti-pattern: inline validation
            if (client.getFirstName() == null || client.getFirstName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "First name is required"));
            }
            if (client.getLastName() == null || client.getLastName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Last name is required"));
            }
            if (client.getEmail() == null || !client.getEmail().contains("@")) {
                return ResponseEntity.badRequest().body("Valid email is required"); // plain string
            }

            // Anti-pattern: SSN encryption done in the CONTROLLER — should be in service or
            // handled by a dedicated security service. The SSN arrives as plain text in the
            // HTTP request body, which means it's logged in access logs, visible in the
            // request body, and only "encrypted" (Base64 encoded) right before save.
            if (client.getSsnEncrypted() != null && !client.getSsnEncrypted().isEmpty()) {
                String rawSsn = client.getSsnEncrypted();

                // Anti-pattern: SSN validation done inline in controller
                if (!SecurityUtils.isValidSsn(rawSsn)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Invalid SSN format. Expected: XXX-XX-XXXX or XXXXXXXXX"));
                }

                // Anti-pattern: "encrypts" (Base64 encodes) the SSN right here in the controller
                String encryptedSsn = SecurityUtils.encryptSsn(rawSsn);
                client.setSsnEncrypted(encryptedSsn);

                log.info("SSN encrypted for client {} {}", client.getFirstName(), client.getLastName());
                // Anti-pattern: logging that SSN was processed — in a real system this log entry
                // combined with request logging would create an audit trail that leaks PII
            }

            // Anti-pattern: password hashing for client portal login done inline in controller
            // The "password" field doesn't even exist on the Client entity — this code sets
            // a default password and stores its hash in the notes field as a workaround
            // because nobody wanted to add a password column to the clients table
            String portalPassword = Constants.DEFAULT_PASSWORD; // Anti-pattern: default password
            String hashedPassword = SecurityUtils.hashPassword(portalPassword);
            String existingNotes = client.getNotes() != null ? client.getNotes() : "";
            client.setNotes(existingNotes + "\n[PORTAL_PWD_HASH:" + hashedPassword + "]");
            // Anti-pattern: storing password hash in the notes field — TERRIBLE practice

            log.info("Set default portal password for new client: {} {}",
                    client.getFirstName(), client.getLastName());

            // Anti-pattern: set defaults in controller
            if (client.getClientType() == null) {
                client.setClientType("BUYER");
            }

            // Anti-pattern: uses MasterService for create (which does its own SSN handling,
            // potentially double-encoding the SSN)
            Client saved = masterService.createClient(client);

            // Anti-pattern: returns full entity — relies on @JsonIgnore for SSN protection
            // If someone adds @JsonProperty to ssnEncrypted, all SSNs are exposed via API
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);

        } catch (RuntimeException e) {
            log.error("Error creating client: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== UPDATE ====================

    /**
     * Update a client.
     * Anti-pattern: uses MasterService but with no SSN handling for updates.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateClient(@PathVariable UUID id, @RequestBody Client updates) {
        try {
            // Anti-pattern: if SSN is being updated, re-encrypt it here
            // but use a DIFFERENT approach than create — create uses SecurityUtils,
            // while MasterService.createClient uses MessageDigest SHA-256
            // So updates and creates produce DIFFERENT encrypted values for the same SSN
            if (updates.getSsnEncrypted() != null && !updates.getSsnEncrypted().isEmpty()) {
                String rawSsn = updates.getSsnEncrypted();
                if (SecurityUtils.isValidSsn(rawSsn)) {
                    updates.setSsnEncrypted(SecurityUtils.encryptSsn(rawSsn));
                }
                // Anti-pattern: if SSN is invalid, silently passes it through unencrypted
            }

            Client updated = masterService.updateClient(id, updates);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }

    // ==================== LEADS ====================

    /**
     * Create a lead for a client.
     * Anti-pattern: bypasses MasterService entirely — goes directly to LeadRepository.
     * MasterService.createLead exists and has audit logging, validation, etc.
     * This controller endpoint duplicates some of that logic poorly.
     */
    @PostMapping("/{id}/leads")
    public ResponseEntity<?> createLead(@PathVariable UUID id, @RequestBody Lead lead) {
        // Anti-pattern: uses repo directly instead of service
        Optional<Client> clientOpt = clientRepository.findById(id);
        if (clientOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Client not found"));
        }

        Client client = clientOpt.get();
        lead.setClient(client);

        // Anti-pattern: inline validation (minimal compared to MasterService.createLead)
        if (lead.getSource() == null || lead.getSource().trim().isEmpty()) {
            lead.setSource("WEBSITE"); // Anti-pattern: hardcoded default
        }
        if (lead.getStatus() == null) {
            lead.setStatus("NEW");
        }

        // Anti-pattern: auto-assign agent from client's assigned agent — duplicated from service
        if (lead.getAssignedAgent() == null && client.getAssignedAgent() != null) {
            lead.setAssignedAgent(client.getAssignedAgent());
        }

        // Anti-pattern: saves directly via repo — skips MasterService.createLead which
        // does audit logging, cache invalidation, and notifications
        Lead saved = leadRepository.save(lead);

        log.info("Created lead {} for client {} (bypassed MasterService)", saved.getId(), id);

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // ==================== DOCUMENTS ====================

    /**
     * Get client documents.
     * Anti-pattern: direct repo access, no authorization check.
     */
    @GetMapping("/{id}/documents")
    public ResponseEntity<?> getDocuments(@PathVariable UUID id) {
        // Anti-pattern: doesn't check if client exists
        List<ClientDocument> documents = clientDocumentRepository.findByClientId(id);

        // Anti-pattern: returns metadata wrapper instead of just the list
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientId", id);
        response.put("documents", documents);
        response.put("totalDocuments", documents.size());
        response.put("verifiedCount", documents.stream()
                .filter(d -> Boolean.TRUE.equals(d.getVerified())).count());
        response.put("unverifiedCount", documents.stream()
                .filter(d -> d.getVerified() == null || !d.getVerified()).count());

        return ResponseEntity.ok(response);
    }

    /**
     * Upload a client document.
     * Anti-pattern: direct repo access, file path generated inline,
     * no actual file upload handling — just accepts JSON metadata.
     */
    @PostMapping("/{id}/documents")
    public ResponseEntity<?> uploadDocument(@PathVariable UUID id, @RequestBody ClientDocument document) {
        Optional<Client> clientOpt = clientRepository.findById(id);
        if (clientOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Client not found");
        }

        // Anti-pattern: validation in controller
        if (document.getFileName() == null || document.getFileName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File name is required"));
        }
        if (document.getDocumentType() == null || document.getDocumentType().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Document type is required"));
        }

        // Anti-pattern: validate document type with hardcoded list
        List<String> validTypes = Arrays.asList(
                "TAX_RETURN", "PAY_STUB", "BANK_STATEMENT", "ID", "W2",
                "PROOF_OF_INSURANCE", "GIFT_LETTER", "OTHER"
        );
        if (!validTypes.contains(document.getDocumentType())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid document type",
                    "validTypes", validTypes
            ));
        }

        document.setClient(clientOpt.get());

        // Anti-pattern: generate file path inline — should use a storage service
        if (document.getFilePath() == null) {
            document.setFilePath("/uploads/clients/" + id + "/" +
                    System.currentTimeMillis() + "_" + document.getFileName());
        }

        // Anti-pattern: saves directly via repo
        ClientDocument saved = clientDocumentRepository.save(document);
        log.info("Uploaded document {} for client {}", saved.getId(), id);

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
