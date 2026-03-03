package com.zcloud.platform.service;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.*;
import com.zcloud.platform.repository.*;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * THE PRIMARY GOD CLASS — MasterService handles User/Client management, Agent management,
 * Lead management, Property management, Listing management, Showing management, and
 * Offer management all in one place.
 *
 * Anti-patterns:
 * - 15+ @Autowired repository injections
 * - Static mutable client cache loaded at startup
 * - @PostConstruct that loads ALL clients into memory
 * - Business logic spanning 4+ domains in single methods
 * - Methods that are 50-100 lines each with inline validation, audit logging, notifications
 * - Direct audit log creation in every mutation method
 * - Inconsistent error handling (throws vs returns null vs wraps in Map)
 * - Password hashing done here instead of a security service
 * - logAudit() helper called from dozens of places
 * - Cache invalidation scattered throughout
 */
@Service
public class MasterService {

    private static final Logger log = LoggerFactory.getLogger(MasterService.class);

    // Anti-pattern: static mutable cache — shared across instances, never bounded, never expires
    private static Map<UUID, Client> clientCache = new ConcurrentHashMap<>();

    // Anti-pattern: 15+ @Autowired repository injections — classic god class
    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private BrokerageRepository brokerageRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private PropertyImageRepository propertyImageRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private OpenHouseRepository openHouseRepository;

    @Autowired
    private ShowingRepository showingRepository;

    @Autowired
    private OfferRepository offerRepository;

    @Autowired
    private CounterOfferRepository counterOfferRepository;

    @Autowired
    private AgentLicenseRepository agentLicenseRepository;

    @Autowired
    private ClientDocumentRepository clientDocumentRepository;

    @Autowired
    private CommissionRepository commissionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PropertyTaxRecordRepository propertyTaxRecordRepository;

    @Autowired
    private NotificationHelper notificationHelper;

    @Autowired
    private CacheManager cacheManager;

    /**
     * Anti-pattern: loads ALL clients into a static in-memory cache on startup.
     * Will OOM on any production dataset. Also, the cache is never invalidated
     * consistently across the class.
     */
    @PostConstruct
    public void initializeClientCache() {
        log.info("Loading all clients into memory cache...");
        List<Client> allClients = clientRepository.findAll();
        for (Client client : allClients) {
            clientCache.put(client.getId(), client);
        }
        log.info("Loaded {} clients into cache", clientCache.size());
    }

    // ==================== CLIENT MANAGEMENT ====================

    /**
     * Create a new client with inline validation, password hashing, audit logging,
     * cache warming, and notification — all in one method.
     */
    @Transactional
    public Client createClient(Client client) {
        // Anti-pattern: inline validation that should be in a validator
        if (client.getFirstName() == null || client.getFirstName().trim().isEmpty()) {
            throw new RuntimeException("First name is required");
        }
        if (client.getLastName() == null || client.getLastName().trim().isEmpty()) {
            throw new RuntimeException("Last name is required");
        }
        if (client.getEmail() == null || !client.getEmail().contains("@")) {
            throw new RuntimeException("Valid email is required");
        }

        // Anti-pattern: check for duplicate email — should be a unique constraint + proper error handling
        Optional<Client> existing = clientRepository.findByEmail(client.getEmail());
        if (existing.isPresent()) {
            throw new RuntimeException("Email already in use: " + client.getEmail());
        }

        // Anti-pattern: password hashing done in the god service
        if (client.getSsnEncrypted() != null) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(client.getSsnEncrypted().getBytes());
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                client.setSsnEncrypted(hexString.toString());
            } catch (Exception e) {
                log.error("Failed to hash SSN for client {}: {}", client.getEmail(), e.getMessage());
                // Anti-pattern: swallow the error and store plaintext
            }
        }

        // Anti-pattern: default values set in service instead of entity or builder
        if (client.getClientType() == null) {
            client.setClientType("BUYER");
        }

        Client saved = clientRepository.save(client);

        // Anti-pattern: cache warming right after save — duplicated logic
        clientCache.put(saved.getId(), saved);
        cacheManager.put("client:" + saved.getId(), saved);

        // Anti-pattern: audit log creation inline
        logAudit(null, "CREATE", "Client", saved.getId(), null, saved.toString());

        // Anti-pattern: notification sending inline in a CRUD method
        if (saved.getAssignedAgent() != null) {
            notificationHelper.notifyAgent(saved.getAssignedAgent().getId(),
                    "New client assigned: " + saved.getFullName());
        }

        log.info("Created client: {} ({})", saved.getFullName(), saved.getId());
        return saved;
    }

    @Transactional
    public Client updateClient(UUID clientId, Client updates) {
        Client existing = clientRepository.findById(clientId).orElse(null);
        if (existing == null) {
            // Anti-pattern: returns null instead of throwing NotFoundException — inconsistent with createClient
            return null;
        }

        String oldValue = existing.toString();

        // Anti-pattern: manual field-by-field copy — no use of BeanUtils or MapStruct
        if (updates.getFirstName() != null) existing.setFirstName(updates.getFirstName());
        if (updates.getLastName() != null) existing.setLastName(updates.getLastName());
        if (updates.getEmail() != null) {
            // Anti-pattern: inline duplicate email check on update too
            Optional<Client> duplicate = clientRepository.findByEmail(updates.getEmail());
            if (duplicate.isPresent() && !duplicate.get().getId().equals(clientId)) {
                throw new RuntimeException("Email already in use by another client");
            }
            existing.setEmail(updates.getEmail());
        }
        if (updates.getPhone() != null) existing.setPhone(updates.getPhone());
        if (updates.getAddressLine1() != null) existing.setAddressLine1(updates.getAddressLine1());
        if (updates.getCity() != null) existing.setCity(updates.getCity());
        if (updates.getState() != null) existing.setState(updates.getState());
        if (updates.getZipCode() != null) existing.setZipCode(updates.getZipCode());
        if (updates.getClientType() != null) existing.setClientType(updates.getClientType());
        if (updates.getNotes() != null) existing.setNotes(updates.getNotes());
        if (updates.getDateOfBirth() != null) existing.setDateOfBirth(updates.getDateOfBirth());

        Client saved = clientRepository.save(existing);

        // Anti-pattern: scattered cache invalidation
        clientCache.put(saved.getId(), saved);
        cacheManager.put("client:" + saved.getId(), saved);

        logAudit(null, "UPDATE", "Client", saved.getId(), oldValue, saved.toString());

        return saved;
    }

    public Client getClient(UUID clientId) {
        // Anti-pattern: checks static cache first, repo second — stale data risk
        Client cached = clientCache.get(clientId);
        if (cached != null) {
            log.debug("Client {} served from cache", clientId);
            return cached;
        }

        Client client = clientRepository.findById(clientId).orElse(null);
        if (client != null) {
            clientCache.put(client.getId(), client);
        }
        return client;  // Anti-pattern: returns null, not Optional
    }

    public List<Client> getAllClients() {
        // Anti-pattern: loads all clients from DB every time, ignoring the cache
        return clientRepository.findAll();
    }

    public List<Client> searchClients(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // Anti-pattern: searches by name via repo native SQL, no pagination
        return clientRepository.searchByName(query.trim());
    }

    @Transactional
    public boolean deleteClient(UUID clientId) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return false;  // Anti-pattern: returns boolean instead of throwing, inconsistent with create
        }

        // Anti-pattern: business logic — should we cascade delete leads, documents, etc.?
        // Just delete the client and hope for the best
        logAudit(null, "DELETE", "Client", clientId, client.toString(), null);

        clientRepository.delete(client);

        // Anti-pattern: manual cache cleanup
        clientCache.remove(clientId);
        cacheManager.remove("client:" + clientId);

        log.warn("Deleted client: {} ({})", client.getFullName(), clientId);
        return true;
    }

    // ==================== AGENT MANAGEMENT ====================

    @Transactional
    public Agent createAgent(Agent agent) {
        // Anti-pattern: inline validation
        if (agent.getFirstName() == null || agent.getLastName() == null) {
            throw new RuntimeException("Agent first name and last name are required");
        }
        if (agent.getEmail() == null) {
            throw new RuntimeException("Agent email is required");
        }
        Optional<Agent> existing = agentRepository.findByEmail(agent.getEmail());
        if (existing.isPresent()) {
            throw new RuntimeException("Agent email already exists: " + agent.getEmail());
        }

        // Anti-pattern: default commission rate hardcoded from Constants
        if (agent.getCommissionRate() == null) {
            agent.setCommissionRate(BigDecimal.valueOf(Constants.DEFAULT_COMMISSION_RATE));
        }

        Agent saved = agentRepository.save(agent);

        // Anti-pattern: if the agent has a brokerage, send notification to the brokerage
        // But brokerage doesn't have a userId so we just log it
        if (saved.getBrokerage() != null) {
            log.info("New agent {} added to brokerage {}", saved.getFullName(), saved.getBrokerage().getName());
        }

        logAudit(null, "CREATE", "Agent", saved.getId(), null, saved.toString());
        cacheManager.put("agent:" + saved.getId(), saved);

        return saved;
    }

    @Transactional
    public Agent updateAgent(UUID agentId, Agent updates) {
        Agent existing = agentRepository.findById(agentId).orElse(null);
        if (existing == null) {
            throw new RuntimeException("Agent not found: " + agentId);
            // Anti-pattern: throws here, but updateClient returns null — pick one!
        }

        String oldValue = existing.toString();

        if (updates.getFirstName() != null) existing.setFirstName(updates.getFirstName());
        if (updates.getLastName() != null) existing.setLastName(updates.getLastName());
        if (updates.getEmail() != null) existing.setEmail(updates.getEmail());
        if (updates.getPhone() != null) existing.setPhone(updates.getPhone());
        if (updates.getLicenseNumber() != null) existing.setLicenseNumber(updates.getLicenseNumber());
        if (updates.getIsActive() != null) existing.setIsActive(updates.getIsActive());
        if (updates.getCommissionRate() != null) existing.setCommissionRate(updates.getCommissionRate());
        if (updates.getBio() != null) existing.setBio(updates.getBio());
        if (updates.getPhotoUrl() != null) existing.setPhotoUrl(updates.getPhotoUrl());

        // Anti-pattern: brokerage assignment inline — should validate the brokerage exists
        if (updates.getBrokerage() != null) {
            Brokerage brokerage = brokerageRepository.findById(updates.getBrokerage().getId()).orElse(null);
            if (brokerage != null) {
                existing.setBrokerage(brokerage);
            } else {
                log.warn("Brokerage {} not found during agent update — ignoring", updates.getBrokerage().getId());
            }
        }

        Agent saved = agentRepository.save(existing);
        logAudit(null, "UPDATE", "Agent", saved.getId(), oldValue, saved.toString());
        cacheManager.invalidate("agent:");

        return saved;
    }

    public Agent getAgent(UUID agentId) {
        // Anti-pattern: tries hand-rolled cache first
        Agent cached = cacheManager.getTyped("agent:" + agentId, Agent.class);
        if (cached != null) {
            return cached;
        }
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent != null) {
            cacheManager.put("agent:" + agentId, agent);
        }
        return agent;
    }

    public List<Agent> getAllAgents() {
        return agentRepository.findAll();
    }

    public List<Agent> getAgentsByBrokerage(UUID brokerageId) {
        return agentRepository.findByBrokerageId(brokerageId);
    }

    // ==================== LEAD MANAGEMENT ====================

    @Transactional
    public Lead createLead(Lead lead) {
        if (lead.getSource() == null || lead.getSource().trim().isEmpty()) {
            lead.setSource("WEBSITE");  // Anti-pattern: silently defaults instead of requiring
        }

        // Anti-pattern: if client is provided, verify they exist by loading from our cache
        if (lead.getClient() != null && lead.getClient().getId() != null) {
            Client client = getClient(lead.getClient().getId());
            if (client == null) {
                throw new RuntimeException("Client not found for lead: " + lead.getClient().getId());
            }
            lead.setClient(client);  // Anti-pattern: replaces the proxy with cached object
        }

        Lead saved = leadRepository.save(lead);

        logAudit(null, "CREATE", "Lead", saved.getId(), null,
                "Source: " + saved.getSource() + ", Status: " + saved.getStatus());

        // Anti-pattern: auto-assignment logic embedded in create method
        if (saved.getAssignedAgent() == null) {
            autoAssignLead(saved);
        }

        return saved;
    }

    /**
     * Auto-assign a lead to the agent with the fewest active leads.
     * Anti-pattern: N+1 queries to count leads per agent, embedded business rule.
     */
    private void autoAssignLead(Lead lead) {
        List<Agent> activeAgents = agentRepository.findByIsActiveTrue();
        if (activeAgents.isEmpty()) {
            log.warn("No active agents available for lead auto-assignment");
            return;
        }

        // Anti-pattern: N+1 — queries leads for each agent to find the one with fewest
        Agent bestAgent = null;
        int minLeads = Integer.MAX_VALUE;
        for (Agent agent : activeAgents) {
            List<Lead> agentLeads = leadRepository.findByAssignedAgentId(agent.getId());
            long activeLeadCount = agentLeads.stream()
                    .filter(l -> "NEW".equals(l.getStatus()) || "CONTACTED".equals(l.getStatus()))
                    .count();
            if (activeLeadCount < minLeads) {
                minLeads = (int) activeLeadCount;
                bestAgent = agent;
            }
        }

        if (bestAgent != null) {
            lead.setAssignedAgent(bestAgent);
            leadRepository.save(lead);
            notificationHelper.notifyAgent(bestAgent.getId(),
                    "New lead auto-assigned to you: " + (lead.getClient() != null ? lead.getClient().getFullName() : "Unknown"));
            log.info("Auto-assigned lead {} to agent {}", lead.getId(), bestAgent.getFullName());
        }
    }

    @Transactional
    public Lead updateLeadStatus(UUID leadId, String newStatus) {
        Lead lead = leadRepository.findById(leadId).orElse(null);
        if (lead == null) {
            return null;  // Anti-pattern: returns null
        }

        String oldStatus = lead.getStatus();

        // Anti-pattern: no state machine — just set whatever status is passed in
        // with a few hardcoded special cases
        lead.setStatus(newStatus);

        // Anti-pattern: status change side effects tracked via notes since entity lacks dedicated fields
        if ("CONTACTED".equals(newStatus) || "QUALIFIED".equals(newStatus) || "LOST".equals(newStatus)) {
            String existingNotes = lead.getNotes() != null ? lead.getNotes() : "";
            lead.setNotes(existingNotes + "\n[" + newStatus + " at " + LocalDateTime.now() + "]");
        }

        Lead saved = leadRepository.save(lead);
        logAudit(null, "UPDATE", "Lead", leadId, "status:" + oldStatus, "status:" + newStatus);

        // Notify assigned agent of status change
        if (saved.getAssignedAgent() != null) {
            notificationHelper.notifyAgent(saved.getAssignedAgent().getId(),
                    "Lead status changed to " + newStatus);
        }

        return saved;
    }

    @Transactional
    public Lead assignLead(UUID leadId, UUID agentId) {
        Lead lead = leadRepository.findById(leadId).orElse(null);
        Agent agent = agentRepository.findById(agentId).orElse(null);

        if (lead == null || agent == null) {
            throw new RuntimeException("Lead or Agent not found");
            // Anti-pattern: unclear which one is missing
        }

        lead.setAssignedAgent(agent);
        Lead saved = leadRepository.save(lead);

        logAudit(null, "UPDATE", "Lead", leadId, null, "Assigned to agent: " + agent.getFullName());
        notificationHelper.notifyAgent(agentId, "Lead assigned to you: " +
                (lead.getClient() != null ? lead.getClient().getFullName() : "Unknown client"));

        return saved;
    }

    public List<Lead> getLeadsByAgent(UUID agentId) {
        return leadRepository.findByAssignedAgentId(agentId);
    }

    // ==================== PROPERTY MANAGEMENT ====================

    @Transactional
    public Property createProperty(Property property) {
        // Anti-pattern: verbose inline validation
        if (property.getAddressLine1() == null || property.getAddressLine1().trim().isEmpty()) {
            throw new RuntimeException("Property address is required");
        }
        if (property.getCity() == null) {
            throw new RuntimeException("Property city is required");
        }
        if (property.getState() == null || property.getState().length() != 2) {
            throw new RuntimeException("Valid 2-letter state code is required");
        }

        // Anti-pattern: property type validation with string constants
        if (property.getPropertyType() != null) {
            List<String> validTypes = Arrays.asList(
                    Constants.PROP_SINGLE_FAMILY, Constants.PROP_CONDO, Constants.PROP_TOWNHOUSE,
                    Constants.PROP_MULTI_FAMILY, Constants.PROP_COMMERCIAL, Constants.PROP_LAND
            );
            if (!validTypes.contains(property.getPropertyType())) {
                log.warn("Invalid property type: {} — defaulting to SINGLE_FAMILY", property.getPropertyType());
                property.setPropertyType(Constants.PROP_SINGLE_FAMILY);
                // Anti-pattern: silently changes invalid input instead of rejecting
            }
        }

        Property saved = propertyRepository.save(property);

        logAudit(null, "CREATE", "Property", saved.getId(), null,
                saved.getAddressLine1() + ", " + saved.getCity() + ", " + saved.getState());

        cacheManager.put("property:" + saved.getId(), saved);

        return saved;
    }

    @Transactional
    public Property updateProperty(UUID propertyId, Property updates) {
        Property existing = propertyRepository.findById(propertyId).orElse(null);
        if (existing == null) {
            return null;  // Anti-pattern: returns null, inconsistent with createProperty
        }

        // Anti-pattern: massive field-by-field copy
        if (updates.getAddressLine1() != null) existing.setAddressLine1(updates.getAddressLine1());
        if (updates.getCity() != null) existing.setCity(updates.getCity());
        if (updates.getState() != null) existing.setState(updates.getState());
        if (updates.getZipCode() != null) existing.setZipCode(updates.getZipCode());
        if (updates.getCounty() != null) existing.setCounty(updates.getCounty());
        if (updates.getLatitude() != null) existing.setLatitude(updates.getLatitude());
        if (updates.getLongitude() != null) existing.setLongitude(updates.getLongitude());
        if (updates.getBeds() != null) existing.setBeds(updates.getBeds());
        if (updates.getBaths() != null) existing.setBaths(updates.getBaths());
        if (updates.getSqft() != null) existing.setSqft(updates.getSqft());
        if (updates.getLotSize() != null) existing.setLotSize(updates.getLotSize());
        if (updates.getYearBuilt() != null) existing.setYearBuilt(updates.getYearBuilt());
        if (updates.getPropertyType() != null) existing.setPropertyType(updates.getPropertyType());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getParkingSpaces() != null) existing.setParkingSpaces(updates.getParkingSpaces());

        Property saved = propertyRepository.save(existing);
        logAudit(null, "UPDATE", "Property", saved.getId(), null, saved.getAddressLine1());
        cacheManager.invalidate("property:");

        return saved;
    }

    public Property getProperty(UUID propertyId) {
        Property cached = cacheManager.getTyped("property:" + propertyId, Property.class);
        if (cached != null) return cached;

        Property property = propertyRepository.findById(propertyId).orElse(null);
        if (property != null) {
            cacheManager.put("property:" + propertyId, property);
        }
        return property;
    }

    public List<Property> searchProperties(String city, String state, Integer minBeds,
                                            Double minBaths, BigDecimal maxPrice) {
        // Anti-pattern: complex search logic inlined — tries different search strategies
        if (city != null && minBeds != null && minBaths != null && maxPrice != null) {
            return propertyRepository.findAvailablePropertiesByCriteria(city, minBeds, minBaths, maxPrice);
        } else if (city != null) {
            return propertyRepository.findByCity(city);
        } else if (state != null) {
            return propertyRepository.findByState(state);
        } else if (minBeds != null) {
            return propertyRepository.findByBedsGreaterThanEqual(minBeds);
        } else {
            // Anti-pattern: returns all properties with no pagination
            return propertyRepository.findAll();
        }
    }

    // ==================== LISTING MANAGEMENT ====================

    @Transactional
    public Listing createListing(Listing listing) {
        // Anti-pattern: validate property exists by loading it
        if (listing.getProperty() == null || listing.getProperty().getId() == null) {
            throw new RuntimeException("Property is required for a listing");
        }
        Property property = getProperty(listing.getProperty().getId());
        if (property == null) {
            throw new RuntimeException("Property not found: " + listing.getProperty().getId());
        }
        listing.setProperty(property);

        // Anti-pattern: validate agent exists
        if (listing.getAgent() == null || listing.getAgent().getId() == null) {
            throw new RuntimeException("Agent is required for a listing");
        }
        Agent agent = getAgent(listing.getAgent().getId());
        if (agent == null) {
            throw new RuntimeException("Agent not found: " + listing.getAgent().getId());
        }
        listing.setAgent(agent);

        // Anti-pattern: inline business rule — agent must be active
        if (agent.getIsActive() == null || !agent.getIsActive()) {
            throw new RuntimeException("Cannot create listing with inactive agent");
        }

        // Anti-pattern: default status and prices set here
        if (listing.getStatus() == null) {
            listing.setStatus(Constants.LISTING_ACTIVE);
        }
        if (listing.getOriginalPrice() == null) {
            listing.setOriginalPrice(listing.getListPrice());
        }
        if (listing.getListedDate() == null) {
            listing.setListedDate(LocalDate.now());
        }

        // Anti-pattern: generate MLS number inline with a terrible algorithm
        if (listing.getMlsNumber() == null) {
            listing.setMlsNumber("MLS-" + System.currentTimeMillis() % 1000000 +
                    "-" + property.getState());
        }

        Listing saved = listingRepository.save(listing);

        logAudit(null, "CREATE", "Listing", saved.getId(), null,
                "MLS: " + saved.getMlsNumber() + ", Price: $" + saved.getListPrice());

        // Anti-pattern: notification to all agents about new listing
        notificationHelper.notifyAllAgents("New listing: " + property.getAddressLine1() +
                " at $" + listing.getListPrice());

        cacheManager.put("listing:" + saved.getId(), saved);

        return saved;
    }

    @Transactional
    public Listing updateListing(UUID listingId, Listing updates) {
        Listing existing = listingRepository.findById(listingId).orElse(null);
        if (existing == null) {
            return null;
        }

        String oldValue = "Price: " + existing.getListPrice() + ", Status: " + existing.getStatus();

        if (updates.getListPrice() != null) existing.setListPrice(updates.getListPrice());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getVirtualTourUrl() != null) existing.setVirtualTourUrl(updates.getVirtualTourUrl());
        if (updates.getExpiryDate() != null) existing.setExpiryDate(updates.getExpiryDate());

        Listing saved = listingRepository.save(existing);
        logAudit(null, "UPDATE", "Listing", saved.getId(), oldValue,
                "Price: " + saved.getListPrice() + ", Status: " + saved.getStatus());
        cacheManager.invalidate("listing:");

        return saved;
    }

    @Transactional
    public Listing changeListingStatus(UUID listingId, String newStatus) {
        Listing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null) {
            throw new RuntimeException("Listing not found: " + listingId);
        }

        String oldStatus = listing.getStatus();

        // Anti-pattern: status transition validation via hardcoded if/else blocks
        if (Constants.LISTING_SOLD.equals(newStatus)) {
            if (!Constants.LISTING_PENDING.equals(oldStatus)) {
                throw new RuntimeException("Listing must be PENDING before marking as SOLD");
            }
        }
        if (Constants.LISTING_WITHDRAWN.equals(newStatus)) {
            if (Constants.LISTING_SOLD.equals(oldStatus)) {
                throw new RuntimeException("Cannot withdraw a SOLD listing");
            }
        }

        listing.setStatus(newStatus);
        Listing saved = listingRepository.save(listing);

        logAudit(null, "STATUS_CHANGE", "Listing", listingId,
                "status:" + oldStatus, "status:" + newStatus);

        // Anti-pattern: cross-domain notification logic embedded here
        notificationHelper.notifyAgent(listing.getAgent().getId(),
                "Listing " + listing.getMlsNumber() + " status changed to " + newStatus);

        // Anti-pattern: if SOLD, also reject all pending offers inline
        if (Constants.LISTING_SOLD.equals(newStatus)) {
            List<Offer> pendingOffers = offerRepository.findByListingId(listingId);
            for (Offer offer : pendingOffers) {
                if ("SUBMITTED".equals(offer.getStatus()) || "COUNTERED".equals(offer.getStatus())) {
                    offer.setStatus("REJECTED");
                    offerRepository.save(offer);
                    // Notify buyer about rejection
                    if (offer.getBuyerClient() != null) {
                        notificationHelper.sendNotification(offer.getBuyerClient().getId(),
                                "Offer Rejected", "Your offer on " + listing.getProperty().getAddressLine1() +
                                        " was rejected — property has been sold.", "EMAIL");
                    }
                }
            }
        }

        cacheManager.invalidate("listing:");
        return saved;
    }

    public List<Listing> getActiveListings() {
        return listingRepository.findByStatus(Constants.LISTING_ACTIVE);
    }

    public List<Listing> getListingsByAgent(UUID agentId) {
        return listingRepository.findByAgentId(agentId);
    }

    // ==================== SHOWING MANAGEMENT ====================

    @Transactional
    public Showing scheduleShowing(Showing showing) {
        // Anti-pattern: validate listing
        if (showing.getListing() == null || showing.getListing().getId() == null) {
            throw new RuntimeException("Listing is required for showing");
        }
        Listing listing = listingRepository.findById(showing.getListing().getId()).orElse(null);
        if (listing == null) {
            throw new RuntimeException("Listing not found");
        }
        // Anti-pattern: inline business rule — only allow showings on active listings
        if (!Constants.LISTING_ACTIVE.equals(listing.getStatus())) {
            throw new RuntimeException("Cannot schedule showing for non-active listing");
        }
        showing.setListing(listing);

        // Anti-pattern: validate client
        if (showing.getClient() == null || showing.getClient().getId() == null) {
            throw new RuntimeException("Client is required for showing");
        }
        Client client = getClient(showing.getClient().getId());
        if (client == null) {
            throw new RuntimeException("Client not found");
        }
        showing.setClient(client);

        // Anti-pattern: conflict check by loading all showings for the agent
        if (showing.getAgent() != null && showing.getAgent().getId() != null) {
            Agent agent = getAgent(showing.getAgent().getId());
            showing.setAgent(agent);

            if (showing.getScheduledDate() != null && agent != null) {
                Timestamp start = new Timestamp(showing.getScheduledDate().getTime() - 15 * 60 * 1000);
                int duration = showing.getDurationMinutes() != null ? showing.getDurationMinutes() + 15 : 45;
                Timestamp end = new Timestamp(showing.getScheduledDate().getTime() + duration * 60 * 1000L);
                List<Showing> conflicts = showingRepository.findConflictingShowings(
                        agent.getId(), start, end);
                if (!conflicts.isEmpty()) {
                    // Anti-pattern: returns the conflict data in the exception message — information leak
                    throw new RuntimeException("Agent has conflicting showing at " +
                            conflicts.get(0).getScheduledDate() + " for listing " +
                            conflicts.get(0).getListing().getMlsNumber());
                }
            }
        }

        Showing saved = showingRepository.save(showing);

        logAudit(null, "CREATE", "Showing", saved.getId(), null,
                "Listing: " + listing.getMlsNumber() + ", Client: " + client.getFullName() +
                        ", Date: " + saved.getScheduledDate());

        // Anti-pattern: inline notification to listing agent, buyer agent, and client
        notificationHelper.notifyAgent(listing.getAgent().getId(),
                "Showing scheduled for " + listing.getProperty().getAddressLine1() + " on " + saved.getScheduledDate());

        return saved;
    }

    @Transactional
    public Showing updateShowingStatus(UUID showingId, String newStatus, String feedback, Integer rating) {
        Showing showing = showingRepository.findById(showingId).orElse(null);
        if (showing == null) {
            return null;
        }

        showing.setStatus(newStatus);
        if (feedback != null) showing.setFeedback(feedback);
        if (rating != null) {
            // Anti-pattern: inline validation of rating range
            if (rating < 1 || rating > 5) {
                throw new RuntimeException("Rating must be between 1 and 5");
            }
            showing.setRating(rating);
        }

        Showing saved = showingRepository.save(showing);
        logAudit(null, "UPDATE", "Showing", showingId, null, "Status: " + newStatus);

        return saved;
    }

    public List<Showing> getShowingsByListing(UUID listingId) {
        return showingRepository.findByListingId(listingId);
    }

    // ==================== OFFER MANAGEMENT ====================

    @Transactional
    public Offer submitOffer(Offer offer) {
        // Anti-pattern: 80-line method with inline validation, cross-domain logic, and notifications
        if (offer.getListing() == null || offer.getListing().getId() == null) {
            throw new RuntimeException("Listing is required for offer");
        }
        Listing listing = listingRepository.findById(offer.getListing().getId()).orElse(null);
        if (listing == null) {
            throw new RuntimeException("Listing not found: " + offer.getListing().getId());
        }
        if (!Constants.LISTING_ACTIVE.equals(listing.getStatus()) &&
                !Constants.LISTING_COMING_SOON.equals(listing.getStatus())) {
            throw new RuntimeException("Cannot submit offer on listing with status: " + listing.getStatus());
        }
        offer.setListing(listing);

        if (offer.getBuyerClient() == null || offer.getBuyerClient().getId() == null) {
            throw new RuntimeException("Buyer client is required");
        }
        Client buyer = getClient(offer.getBuyerClient().getId());
        if (buyer == null) {
            throw new RuntimeException("Buyer client not found");
        }
        offer.setBuyerClient(buyer);

        // Anti-pattern: inline business rules about offer amounts
        if (offer.getOfferAmount() == null || offer.getOfferAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Offer amount must be positive");
        }
        if (offer.getEarnestMoney() == null) {
            // Anti-pattern: default earnest money to 1% of offer — hardcoded business rule
            offer.setEarnestMoney(offer.getOfferAmount().multiply(BigDecimal.valueOf(0.01)));
        }

        // Anti-pattern: check if this buyer already has an active offer on this listing
        List<Offer> existingOffers = offerRepository.findByBuyerClientId(buyer.getId());
        for (Offer existingOffer : existingOffers) {
            if (existingOffer.getListing().getId().equals(listing.getId()) &&
                    ("SUBMITTED".equals(existingOffer.getStatus()) || "COUNTERED".equals(existingOffer.getStatus()))) {
                throw new RuntimeException("Buyer already has an active offer on this listing");
            }
        }

        // Anti-pattern: default expiry 3 days from now — hardcoded
        if (offer.getExpiryDate() == null) {
            offer.setExpiryDate(Timestamp.valueOf(LocalDateTime.now().plusDays(3)));
        }

        Offer saved = offerRepository.save(offer);

        logAudit(buyer.getId(), "CREATE", "Offer", saved.getId(), null,
                "Amount: $" + saved.getOfferAmount() + ", Listing: " + listing.getMlsNumber());

        // Anti-pattern: cross-domain notification
        notificationHelper.notifyOfferReceived(listing.getId(), saved.getId());

        return saved;
    }

    @Transactional
    public Offer updateOfferStatus(UUID offerId, String newStatus) {
        Offer offer = offerRepository.findById(offerId).orElse(null);
        if (offer == null) {
            // Anti-pattern: returns a Map instead of throwing — inconsistent with everything else
            return null;
        }

        String oldStatus = offer.getStatus();
        offer.setStatus(newStatus);

        // Anti-pattern: inline side effects for ACCEPTED status
        if (Constants.OFFER_ACCEPTED.equals(newStatus)) {
            // Change listing to PENDING
            Listing listing = offer.getListing();
            listing.setStatus(Constants.LISTING_PENDING);
            listingRepository.save(listing);

            // Reject all other offers on this listing
            List<Offer> otherOffers = offerRepository.findByListingId(listing.getId());
            for (Offer other : otherOffers) {
                if (!other.getId().equals(offerId) && "SUBMITTED".equals(other.getStatus())) {
                    other.setStatus("REJECTED");
                    offerRepository.save(other);
                    if (other.getBuyerClient() != null) {
                        notificationHelper.sendNotification(other.getBuyerClient().getId(),
                                "Offer Rejected",
                                "Another offer was accepted on " + listing.getProperty().getAddressLine1(),
                                "EMAIL");
                    }
                }
            }

            // Anti-pattern: notify buyer of acceptance inline
            if (offer.getBuyerClient() != null) {
                notificationHelper.sendNotification(offer.getBuyerClient().getId(),
                        "Offer Accepted!",
                        "Your offer of $" + offer.getOfferAmount() + " on " +
                                listing.getProperty().getAddressLine1() + " has been accepted!",
                        "EMAIL");
            }
        }

        Offer saved = offerRepository.save(offer);
        logAudit(null, "STATUS_CHANGE", "Offer", offerId,
                "status:" + oldStatus, "status:" + newStatus);

        return saved;
    }

    @Transactional
    public CounterOffer counterOffer(UUID offerId, BigDecimal counterAmount, String terms) {
        Offer offer = offerRepository.findById(offerId).orElse(null);
        if (offer == null) {
            throw new RuntimeException("Offer not found: " + offerId);
        }

        // Anti-pattern: inline validation
        if (counterAmount == null || counterAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Counter amount must be positive");
        }

        // Update original offer status
        offer.setStatus(Constants.OFFER_COUNTERED);
        offerRepository.save(offer);

        // Create counter offer
        CounterOffer counter = new CounterOffer();
        counter.setOffer(offer);
        counter.setCounterAmount(counterAmount);
        counter.setTerms(terms);
        counter.setStatus("PENDING");
        counter.setCreatedBy(offer.getListing().getAgent().getId());

        CounterOffer saved = counterOfferRepository.save(counter);

        logAudit(null, "CREATE", "CounterOffer", saved.getId(), null,
                "Original: $" + offer.getOfferAmount() + " -> Counter: $" + counterAmount);

        // Anti-pattern: notification to buyer
        if (offer.getBuyerClient() != null) {
            notificationHelper.sendNotification(offer.getBuyerClient().getId(),
                    "Counter Offer Received",
                    "The seller has countered your offer of $" + offer.getOfferAmount() +
                            " with $" + counterAmount + ". " +
                            (terms != null ? "Terms: " + terms : ""),
                    "EMAIL");
        }

        return saved;
    }

    public List<Offer> getOffersByListing(UUID listingId) {
        // Anti-pattern: loads offers then manually populates transient counter offers
        List<Offer> offers = offerRepository.findByListingId(listingId);
        for (Offer offer : offers) {
            // Anti-pattern: N+1 query to populate transient list
            List<CounterOffer> counters = counterOfferRepository.findByOfferId(offer.getId());
            offer.setCounterOffers(counters);
        }
        return offers;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Creates an audit log entry. Called from dozens of places throughout this god class.
     * Anti-pattern: this helper belongs in a dedicated AuditService, not in MasterService.
     * Also takes nullable userId which makes tracking impossible.
     */
    private void logAudit(UUID userId, String action, String resourceType, UUID resourceId,
                          String oldValue, String newValue) {
        try {
            AuditLog audit = new AuditLog();
            audit.setUserId(userId);  // Often null — anti-pattern
            audit.setAction(action);
            audit.setResourceType(resourceType);
            audit.setResourceId(resourceId != null ? resourceId.toString() : null);
            audit.setOldValue(oldValue);
            audit.setNewValue(newValue);
            audit.setIpAddress("0.0.0.0");  // Anti-pattern: hardcoded IP, no request context
            auditLogRepository.save(audit);
        } catch (Exception e) {
            // Anti-pattern: swallow audit failures so they don't break the calling method
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }

    /**
     * Convenience method for other services to access clients.
     * Anti-pattern: exposes internal cache directly.
     */
    public Map<UUID, Client> getClientCache() {
        return clientCache;
    }

    /**
     * Force refresh the client cache from the database.
     * Anti-pattern: reloads all clients — expensive and blocks the calling thread.
     */
    public void refreshClientCache() {
        clientCache.clear();
        initializeClientCache();
        log.info("Client cache forcefully refreshed");
    }
}
