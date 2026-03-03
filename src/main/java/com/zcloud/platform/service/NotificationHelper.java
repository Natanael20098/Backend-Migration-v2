package com.zcloud.platform.service;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.Agent;
import com.zcloud.platform.model.LoanApplication;
import com.zcloud.platform.model.Listing;
import com.zcloud.platform.model.Notification;
import com.zcloud.platform.model.Offer;
import com.zcloud.platform.repository.AgentRepository;
import com.zcloud.platform.repository.LoanApplicationRepository;
import com.zcloud.platform.repository.ListingRepository;
import com.zcloud.platform.repository.NotificationRepository;
import com.zcloud.platform.repository.OfferRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notification helper — handles all outbound notifications and alerts.
 * Anti-patterns:
 * - @Component instead of @Service (inconsistency with rest of codebase)
 * - Static mutable deduplication map that leaks memory
 * - Silently swallows email exceptions
 * - Queries repositories directly instead of going through service layer
 * - No actual email sending — just logs — but has the dependency structure anyway
 * - Deduplication window is a hardcoded magic number
 * - No retry logic, no dead-letter queue, no async processing
 */
@Component
public class NotificationHelper {

    private static final Logger log = LoggerFactory.getLogger(NotificationHelper.class);

    // Anti-pattern: static mutable state for dedup — grows forever, never cleaned
    private static final Map<String, Long> recentNotifications = new ConcurrentHashMap<>();

    // Anti-pattern: magic number — 5 minute dedup window in milliseconds
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000;

    @Autowired
    private NotificationRepository notificationRepository;

    // Anti-pattern: component queries repositories directly instead of going through services
    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private OfferRepository offerRepository;

    /**
     * Core notification method — saves a notification record and attempts email delivery.
     * Anti-pattern: mixes persistence, dedup, and delivery in a single method.
     */
    public Notification sendNotification(UUID userId, String title, String message, String type) {
        if (userId == null || title == null) {
            log.warn("Cannot send notification: userId or title is null");
            return null; // Anti-pattern: returns null instead of throwing
        }

        // Anti-pattern: dedup key is fragile string concatenation
        String dedupKey = userId.toString() + ":" + title + ":" + type;
        Long lastSent = recentNotifications.get(dedupKey);
        if (lastSent != null && (System.currentTimeMillis() - lastSent) < DEDUP_WINDOW_MS) {
            log.debug("Suppressing duplicate notification for user {} — title: {}", userId, title);
            return null; // Anti-pattern: silent suppression, caller has no idea
        }

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setType(type != null ? type : "IN_APP");
        notification.setIsRead(false);

        Notification saved = notificationRepository.save(notification);

        // Anti-pattern: dedup map grows forever — memory leak
        recentNotifications.put(dedupKey, System.currentTimeMillis());

        // Attempt email delivery if type includes email
        if ("EMAIL".equals(type) || "ALL".equals(type)) {
            // Anti-pattern: we don't actually have the user's email here — would need to query
            sendEmailNotification("unknown@homelend-pro.com", title, message);
        }

        log.info("Notification sent to user {}: {}", userId, title);
        return saved;
    }

    /**
     * Convenience method — notify a specific agent by their ID.
     * Anti-pattern: queries agent repo directly (should go through AgentService/MasterService).
     */
    public void notifyAgent(UUID agentId, String message) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            log.warn("Cannot notify agent {} — not found", agentId);
            return; // Anti-pattern: silently fails
        }

        sendNotification(agentId, "Agent Notification", message, "IN_APP");

        // Anti-pattern: also try to send email — but silently swallow errors
        if (agent.getEmail() != null) {
            sendEmailNotification(agent.getEmail(), "HomeLend Pro Alert", message);
        }
    }

    /**
     * Blast a message to every active agent in the system.
     * Anti-pattern: N+1 notification creation, no batching, no async.
     * Could be devastating at scale with thousands of agents.
     */
    public void notifyAllAgents(String message) {
        // Anti-pattern: queries all agents directly from repo
        List<Agent> allAgents = agentRepository.findAll();
        int count = 0;

        for (Agent agent : allAgents) {
            // Anti-pattern: checking isActive inline instead of using a findByIsActive query
            if (agent.getIsActive() != null && agent.getIsActive()) {
                sendNotification(agent.getId(), "System-wide Alert", message, "IN_APP");
                count++;
            }
        }

        log.info("Sent system notification to {} active agents", count);
    }

    /**
     * "Sends" an email notification. Does not actually send anything — just logs.
     * Anti-pattern: silently swallows ALL exceptions, including ones that should propagate.
     * The try/catch is here for a JavaMail dependency that was never actually wired up.
     */
    public void sendEmailNotification(String to, String subject, String body) {
        try {
            // Anti-pattern: pretend email sending — would use JavaMail but it's not configured
            log.info("EMAIL [TO: {}] [SUBJECT: {}] [BODY LENGTH: {} chars]", to, subject,
                    body != null ? body.length() : 0);

            // Simulate email delay
            // Anti-pattern: Thread.sleep in a service method
            // Thread.sleep(100); // commented out but left in codebase — classic

            if (to == null || to.isEmpty()) {
                log.warn("Email recipient is null or empty — skipping");
                return;
            }

            // Anti-pattern: building email content inline
            String emailContent = "<html><body>"
                    + "<h2>" + subject + "</h2>"
                    + "<p>" + body + "</p>"
                    + "<hr/>"
                    + "<p style='font-size:10px;color:gray;'>HomeLend Pro — " + Constants.EMAIL_FROM + "</p>"
                    + "</body></html>";

            log.debug("Would send email: {}", emailContent);
            // In production, this would use JavaMailSender.send(mimeMessage)
            // But nobody ever set that up, so we just log

        } catch (Exception e) {
            // Anti-pattern: catches ALL exceptions and swallows them silently
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            // Don't rethrow — notifications should never break the calling flow
            // (This philosophy is wrong — it hides real configuration issues)
        }
    }

    /**
     * Notify relevant parties when a loan status changes.
     * Anti-pattern: queries loan app directly, hardcoded notification text,
     * business logic about who to notify embedded in helper.
     */
    public void notifyLoanStatusChange(UUID loanId, String oldStatus, String newStatus) {
        // Anti-pattern: queries repo directly in helper class
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            log.warn("Cannot send loan status notification — loan {} not found", loanId);
            return;
        }

        String borrowerMessage = String.format(
                "Your loan application has moved from %s to %s. Loan amount: $%s",
                oldStatus, newStatus, loan.getLoanAmount());

        // Notify borrower
        if (loan.getBorrower() != null) {
            sendNotification(loan.getBorrower().getId(), "Loan Status Update", borrowerMessage, "EMAIL");
        }

        // Notify loan officer
        if (loan.getLoanOfficer() != null) {
            String officerMessage = String.format(
                    "Loan %s for %s has moved from %s to %s",
                    loanId, loan.getBorrower() != null ? loan.getBorrower().getFullName() : "Unknown",
                    oldStatus, newStatus);
            sendNotification(loan.getLoanOfficer().getId(), "Loan Pipeline Update", officerMessage, "IN_APP");
        }

        // Anti-pattern: hardcoded special-case logic — if denied, also notify processor
        if ("DENIED".equals(newStatus) && loan.getProcessor() != null) {
            sendNotification(loan.getProcessor().getId(),
                    "Loan Denied",
                    "Loan " + loanId + " has been denied. Please archive the file.",
                    "IN_APP");
        }

        // Anti-pattern: hardcoded special case — if approved, also send SMS (but we can't send SMS)
        if ("APPROVED".equals(newStatus)) {
            log.info("Would send SMS for loan approval {} but SMS gateway not configured", loanId);
        }
    }

    /**
     * Notify listing agent and seller when an offer is received.
     * Anti-pattern: queries multiple repos, builds business logic about who to notify.
     */
    public void notifyOfferReceived(UUID listingId, UUID offerId) {
        // Anti-pattern: queries repos directly
        Listing listing = listingRepository.findById(listingId).orElse(null);
        Offer offer = offerRepository.findById(offerId).orElse(null);

        if (listing == null || offer == null) {
            log.warn("Cannot send offer notification — listing {} or offer {} not found", listingId, offerId);
            return;
        }

        // Notify listing agent
        if (listing.getAgent() != null) {
            String agentMessage = String.format(
                    "New offer received on %s for $%s from %s. Financing: %s. Requested close: %s",
                    listing.getProperty() != null ? listing.getProperty().getAddressLine1() : "Unknown Property",
                    offer.getOfferAmount(),
                    offer.getBuyerClient() != null ? offer.getBuyerClient().getFullName() : "Unknown Buyer",
                    offer.getFinancingType(),
                    offer.getClosingDateRequested());

            sendNotification(listing.getAgent().getId(), "New Offer Received!", agentMessage, "EMAIL");
        }

        // Anti-pattern: also blast the brokerage — no way to opt out
        if (listing.getAgent() != null && listing.getAgent().getBrokerage() != null) {
            log.info("Would notify brokerage {} about offer on listing {}",
                    listing.getAgent().getBrokerage().getName(), listingId);
        }
    }

    /**
     * Get count of unread notifications for a user.
     * Anti-pattern: this is a query method on a notification "helper" — doesn't belong here.
     */
    public long getUnreadCount(UUID userId) {
        // Anti-pattern: loads ALL notifications then filters in memory
        List<Notification> all = notificationRepository.findAll();
        return all.stream()
                .filter(n -> userId.equals(n.getUserId()))
                .filter(n -> n.getIsRead() != null && !n.getIsRead())
                .count();
    }

    /**
     * Clean up old dedup entries. Called manually — nobody actually calls this regularly.
     * Anti-pattern: manual cleanup that nobody schedules via @Scheduled.
     */
    public void cleanupDedupMap() {
        long now = System.currentTimeMillis();
        int before = recentNotifications.size();
        recentNotifications.entrySet().removeIf(e -> (now - e.getValue()) > DEDUP_WINDOW_MS * 2);
        int after = recentNotifications.size();
        log.info("Dedup cleanup: removed {} stale entries, {} remaining", before - after, after);
    }
}
