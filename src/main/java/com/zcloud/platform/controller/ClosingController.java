package com.zcloud.platform.controller;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.*;
import com.zcloud.platform.repository.*;
import com.zcloud.platform.service.ClosingService;
import com.zcloud.platform.service.LoanService;
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

/**
 * ClosingController -- handles closing scheduling, escrow, title, documents, and payments.
 *
 * Anti-patterns:
 * - Closing completion triggers commission creation, listing status change, loan status change,
 *   escrow account creation, payment schedule generation — all orchestrated from the controller
 * - Direct repository access for all GET operations (bypasses ClosingService)
 * - Inline business logic for document completeness checking
 * - Status transition validation duplicated from ClosingService
 * - Inline notification sending
 * - Mixed use of service (writes) and repositories (reads)
 * - No authorization checks
 */
@RestController
@RequestMapping("/api/closings")
public class ClosingController {

    private static final Logger log = LoggerFactory.getLogger(ClosingController.class);

    @Autowired
    private ClosingService closingService;

    @Autowired
    private LoanService loanService;

    // Anti-pattern: injects repositories for direct access bypassing service
    @Autowired
    private ClosingDetailRepository closingDetailRepository;

    @Autowired
    private EscrowAccountRepository escrowAccountRepository;

    @Autowired
    private TitleReportRepository titleReportRepository;

    @Autowired
    private ClosingDocumentRepository closingDocumentRepository;

    @Autowired
    private LoanPaymentRepository loanPaymentRepository;

    @Autowired
    private PaymentScheduleRepository paymentScheduleRepository;

    // Anti-pattern: notification helper injected into controller for inline notifications
    @Autowired
    private NotificationHelper notificationHelper;

    // ==================== SCHEDULE CLOSING ====================

    /**
     * Schedule a closing for a loan application.
     * Anti-pattern: validates loan status in controller (service also validates), adds
     * additional inline processing after service call.
     */
    @PostMapping("/loans/{loanId}/schedule")
    public ResponseEntity<?> scheduleClosing(@PathVariable UUID loanId,
                                              @RequestBody ClosingDetail closing) {
        try {
            // Anti-pattern: validate loan exists and check status in controller
            // (ClosingService.scheduleClosing already does this)
            LoanApplication loan = loanService.getLoanApplication(loanId);
            if (loan == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Loan application not found", "loanId", loanId.toString()));
            }

            // Anti-pattern: duplicated status check from ClosingService.scheduleClosing
            if (!Constants.LOAN_STATUS_CLOSING.equals(loan.getStatus()) &&
                    !Constants.LOAN_STATUS_APPROVED.equals(loan.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Loan must be in CLOSING or APPROVED status to schedule closing",
                        "currentStatus", loan.getStatus()));
            }

            // Anti-pattern: inline validation in controller
            if (closing.getClosingDate() != null && closing.getClosingDate().isBefore(LocalDate.now())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Closing date cannot be in the past"));
            }

            if (closing.getClosingLocation() == null || closing.getClosingLocation().trim().isEmpty()) {
                // Anti-pattern: set a default location instead of requiring it
                closing.setClosingLocation("TBD - Title Company Office");
                log.warn("No closing location specified for loan {} — defaulting to TBD", loanId);
            }

            // Anti-pattern: check if a closing already exists for this loan
            ClosingDetail existing = closingDetailRepository.findByLoanApplicationId(loanId);
            if (existing != null) {
                // Anti-pattern: instead of rejecting, return the existing one with a warning
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("warning", "Closing already exists for this loan");
                response.put("existingClosing", existing);
                response.put("existingClosingId", existing.getId());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }

            ClosingDetail saved = closingService.scheduleClosing(loanId, closing);

            // Anti-pattern: build response with extra data fetched after the save
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("closing", saved);
            response.put("closingDate", saved.getClosingDate());
            response.put("estimatedClosingCosts", saved.getTotalClosingCosts());

            // Anti-pattern: re-fetch loan to get updated status (service may have changed it)
            LoanApplication updatedLoan = loanService.getLoanApplication(loanId);
            response.put("updatedLoanStatus", updatedLoan != null ? updatedLoan.getStatus() : "UNKNOWN");
            response.put("message", "Closing scheduled successfully");

            // Anti-pattern: inline — estimate days until closing
            if (saved.getClosingDate() != null) {
                long daysUntilClosing = java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.now(), saved.getClosingDate());
                response.put("daysUntilClosing", daysUntilClosing);
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            log.error("Error scheduling closing for loan {}: {}", loanId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== GET CLOSING ====================

    /**
     * Get closing details by ID.
     * Anti-pattern: goes directly to repo, bypasses ClosingService.
     * Enriches response with data from multiple other repos.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getClosing(@PathVariable UUID id) {
        Optional<ClosingDetail> closingOpt = closingDetailRepository.findById(id);
        if (closingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Closing not found", "id", id.toString()));
        }

        ClosingDetail closing = closingOpt.get();

        // Anti-pattern: build a massive response map with data from multiple repos
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("closing", closing);

        // Anti-pattern: query escrow directly from repo in the controller
        List<EscrowAccount> escrowAccounts = escrowAccountRepository.findByClosingId(id);
        response.put("escrowAccount", escrowAccounts.isEmpty() ? null : escrowAccounts.get(0));
        response.put("hasEscrow", !escrowAccounts.isEmpty());

        // Anti-pattern: query title report directly from repo
        List<TitleReport> titleReports = titleReportRepository.findByClosingId(id);
        response.put("titleReport", titleReports.isEmpty() ? null : titleReports.get(0));
        response.put("titleStatus", titleReports.isEmpty() ? "NOT_ORDERED" :
                titleReports.get(0).getStatus());

        // Anti-pattern: query documents directly from repo
        List<ClosingDocument> documents = closingDocumentRepository.findByClosingId(id);
        response.put("documentCount", documents.size());
        response.put("unsignedDocuments", documents.stream()
                .filter(d -> d.getSigned() == null || !d.getSigned())
                .count());
        response.put("signedDocuments", documents.stream()
                .filter(d -> Boolean.TRUE.equals(d.getSigned()))
                .count());

        // Anti-pattern: inline document completeness check — duplicated from ClosingService
        List<String> requiredTypes = Arrays.asList(
                "DEED", "MORTGAGE_NOTE", "CLOSING_DISCLOSURE", "TITLE_INSURANCE"
        );
        List<String> presentTypes = new ArrayList<>();
        for (ClosingDocument doc : documents) {
            if (doc.getDocumentType() != null) {
                presentTypes.add(doc.getDocumentType());
            }
        }
        List<String> missingDocTypes = new ArrayList<>();
        for (String required : requiredTypes) {
            if (!presentTypes.contains(required)) {
                missingDocTypes.add(required);
            }
        }
        response.put("missingRequiredDocuments", missingDocTypes);
        response.put("allRequiredDocumentsPresent", missingDocTypes.isEmpty());

        // Anti-pattern: compute net closing costs inline
        if (closing.getTotalClosingCosts() != null) {
            BigDecimal sellerCredits = closing.getSellerCredits() != null ?
                    closing.getSellerCredits() : BigDecimal.ZERO;
            BigDecimal buyerCredits = closing.getBuyerCredits() != null ?
                    closing.getBuyerCredits() : BigDecimal.ZERO;
            BigDecimal netCosts = closing.getTotalClosingCosts()
                    .subtract(sellerCredits)
                    .subtract(buyerCredits);
            response.put("netClosingCosts", netCosts.setScale(2, RoundingMode.HALF_UP));
        }

        return ResponseEntity.ok(response);
    }

    // ==================== UPDATE CLOSING STATUS ====================

    /**
     * Update closing status (SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED).
     *
     * Anti-pattern: when status changes to COMPLETED, the controller orchestrates
     * commission creation, listing status change, loan status change, escrow account
     * creation, and payment schedule generation — all ADDITIONAL to what the service does.
     * The service also does some of this, leading to potential duplication or conflicts.
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateClosingStatus(@PathVariable UUID id,
                                                   @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        if (newStatus == null || newStatus.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Status is required"));
        }

        // Anti-pattern: status validation with hardcoded strings in controller
        List<String> validStatuses = Arrays.asList(
                Constants.CLOSING_SCHEDULED, Constants.CLOSING_IN_PROGRESS,
                Constants.CLOSING_COMPLETED, Constants.CLOSING_CANCELLED
        );
        if (!validStatuses.contains(newStatus)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid closing status",
                    "validStatuses", validStatuses
            ));
        }

        // Anti-pattern: fetch closing from repo directly to do pre-validation
        Optional<ClosingDetail> closingOpt = closingDetailRepository.findById(id);
        if (closingOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ClosingDetail closing = closingOpt.get();
        String oldStatus = closing.getStatus();

        // Anti-pattern: status transition validation in controller — duplicated from service
        if (Constants.CLOSING_COMPLETED.equals(oldStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot change status of a COMPLETED closing"));
        }
        if (Constants.CLOSING_CANCELLED.equals(oldStatus) &&
                !Constants.CLOSING_SCHEDULED.equals(newStatus)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cancelled closings can only be rescheduled (set to SCHEDULED)"));
        }

        // Anti-pattern: pre-check for completion — verify documents are ready
        if (Constants.CLOSING_COMPLETED.equals(newStatus)) {
            int unsignedCount = closingDocumentRepository.countUnsignedDocumentsByClosing(id);
            if (unsignedCount > 0) {
                log.warn("Attempting to complete closing {} with {} unsigned documents", id, unsignedCount);
                // Anti-pattern: warn but don't block — allows completion with unsigned docs
            }

            // Anti-pattern: check title report status inline
            List<TitleReport> titleReports = titleReportRepository.findByClosingId(id);
            if (!titleReports.isEmpty()) {
                TitleReport title = titleReports.get(0);
                if ("ISSUES_FOUND".equals(title.getStatus())) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Cannot complete closing — unresolved title issues",
                            "titleStatus", title.getStatus(),
                            "issues", title.getIssues() != null ? title.getIssues() : "Unknown issues"
                    ));
                }
            }
        }

        try {
            // Call service for the actual status update (which has its own side effects)
            ClosingDetail updated = closingService.updateClosingStatus(id, newStatus);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("closing", updated);
            response.put("previousStatus", oldStatus);
            response.put("newStatus", newStatus);

            // Anti-pattern: ADDITIONAL controller-level processing when closing completes
            // The ClosingService.updateClosingStatus -> handleClosingCompleted already does
            // commission creation, escrow setup, payment schedule, etc.
            // But the controller ALSO does some of this, creating confusion and potential duplication
            if (Constants.CLOSING_COMPLETED.equals(newStatus)) {
                log.info("Closing {} completed — controller performing additional post-completion processing", id);

                // Anti-pattern: re-fetch data that the service already created, to stuff into response
                LoanApplication loan = closing.getLoanApplication();
                if (loan != null) {
                    // Re-fetch to get updated status
                    LoanApplication updatedLoan = loanService.getLoanApplication(loan.getId());
                    response.put("loanStatus", updatedLoan != null ? updatedLoan.getStatus() : "UNKNOWN");
                    response.put("loanId", loan.getId());

                    // Anti-pattern: check if payment schedule was generated
                    List<PaymentSchedule> schedule = paymentScheduleRepository
                            .findByLoanApplicationId(loan.getId());
                    response.put("paymentScheduleGenerated", !schedule.isEmpty());
                    response.put("totalPayments", schedule.size());

                    if (!schedule.isEmpty()) {
                        // Anti-pattern: grab first and last payment for summary
                        PaymentSchedule firstPayment = schedule.get(0);
                        PaymentSchedule lastPayment = schedule.get(schedule.size() - 1);
                        response.put("firstPaymentDate", firstPayment.getDueDate());
                        response.put("lastPaymentDate", lastPayment.getDueDate());
                        response.put("monthlyPayment", firstPayment.getTotal());
                    }
                }

                // Anti-pattern: check if escrow was created
                List<EscrowAccount> escrowAccounts = escrowAccountRepository.findByClosingId(id);
                response.put("escrowCreated", !escrowAccounts.isEmpty());
                if (!escrowAccounts.isEmpty()) {
                    EscrowAccount escrow = escrowAccounts.get(0);
                    response.put("escrowAccountNumber", escrow.getAccountNumber());
                    response.put("escrowBalance", escrow.getBalance());
                    response.put("monthlyEscrow", escrow.getMonthlyPayment());
                }

                response.put("message", "Closing completed successfully. Loan funded, " +
                        "payment schedule generated, escrow account created.");

                // Anti-pattern: inline notification from controller (service already sent notifications)
                if (loan != null && loan.getLoanOfficer() != null) {
                    notificationHelper.notifyAgent(loan.getLoanOfficer().getId(),
                            "Closing " + id + " has been COMPLETED. Loan " + loan.getId() + " funded.");
                }
            } else if (Constants.CLOSING_CANCELLED.equals(newStatus)) {
                response.put("message", "Closing cancelled. Loan status may need to be updated separately.");

                // Anti-pattern: inline notification for cancellation
                LoanApplication loan = closing.getLoanApplication();
                if (loan != null && loan.getBorrower() != null) {
                    notificationHelper.sendNotification(loan.getBorrower().getId(),
                            "Closing Cancelled",
                            "Your scheduled closing has been cancelled. " +
                                    "Your loan officer will contact you with next steps.",
                            "EMAIL");
                }
            } else {
                response.put("message", "Closing status updated to " + newStatus);
            }

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("Error updating closing status for {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== ESCROW ====================

    /**
     * Get escrow account for a closing.
     * Anti-pattern: bypasses ClosingService, goes directly to EscrowAccountRepository.
     * Inline calculation of escrow adequacy analysis.
     */
    @GetMapping("/{id}/escrow")
    public ResponseEntity<?> getEscrow(@PathVariable UUID id) {
        // Anti-pattern: doesn't verify closing exists before querying escrow
        List<EscrowAccount> escrowAccounts = escrowAccountRepository.findByClosingId(id);

        if (escrowAccounts.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "closingId", id,
                    "escrowAccount", Collections.emptyMap(),
                    "message", "No escrow account found for this closing"
            ));
        }

        // Anti-pattern: takes first from list (assumes single result)
        EscrowAccount escrow = escrowAccounts.get(0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("closingId", id);
        response.put("escrowAccount", escrow);
        response.put("accountNumber", escrow.getAccountNumber());
        response.put("currentBalance", escrow.getBalance());
        response.put("monthlyPayment", escrow.getMonthlyPayment());

        // Anti-pattern: inline escrow adequacy analysis — business logic in controller
        if (escrow.getMonthlyPayment() != null && escrow.getBalance() != null
                && escrow.getMonthlyPayment().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal monthsCovered = escrow.getBalance()
                    .divide(escrow.getMonthlyPayment(), 1, RoundingMode.HALF_UP);
            response.put("monthsCoveredByReserve", monthsCovered);
            response.put("reserveAdequate", monthsCovered.compareTo(
                    BigDecimal.valueOf(Constants.ESCROW_RESERVE_MONTHS)) >= 0);
        }

        // Anti-pattern: breakdown of escrow components
        response.put("propertyTaxReserve", escrow.getPropertyTaxReserve());
        response.put("insuranceReserve", escrow.getInsuranceReserve());

        return ResponseEntity.ok(response);
    }

    // ==================== TITLE ====================

    /**
     * Get title report for a closing.
     * Anti-pattern: bypasses ClosingService.getTitleByClosing, goes directly to repo.
     */
    @GetMapping("/{id}/title")
    public ResponseEntity<?> getTitleReport(@PathVariable UUID id) {
        // Anti-pattern: uses repo directly instead of closingService.getTitleByClosing(id)
        List<TitleReport> reports = titleReportRepository.findByClosingId(id);

        if (reports.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "closingId", id,
                    "titleReport", Collections.emptyMap(),
                    "message", "No title report found for this closing"
            ));
        }

        TitleReport report = reports.get(0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("closingId", id);
        response.put("titleReport", report);
        response.put("titleCompany", report.getTitleCompany());
        response.put("titleNumber", report.getTitleNumber());
        response.put("status", report.getStatus());

        // Anti-pattern: inline status interpretation — business logic in controller
        if ("CLEAR".equals(report.getStatus())) {
            response.put("readyForClosing", true);
            response.put("statusMessage", "Title is clear. No issues found.");
        } else if ("ISSUES_FOUND".equals(report.getStatus())) {
            response.put("readyForClosing", false);
            response.put("statusMessage", "Title issues must be resolved before closing.");
            response.put("issues", report.getIssues());
        } else if ("PENDING".equals(report.getStatus())) {
            response.put("readyForClosing", false);
            response.put("statusMessage", "Title search is still in progress.");
        } else {
            response.put("readyForClosing", false);
            response.put("statusMessage", "Title status: " + report.getStatus());
        }

        return ResponseEntity.ok(response);
    }

    // ==================== DOCUMENTS ====================

    /**
     * Add a closing document.
     * Anti-pattern: uses ClosingService for create but adds inline validation and
     * document completeness checking in the controller.
     */
    @PostMapping("/{id}/documents")
    public ResponseEntity<?> addClosingDocument(@PathVariable UUID id,
                                                 @RequestBody ClosingDocument document) {
        try {
            // Anti-pattern: validate document type with hardcoded list in controller
            if (document.getDocumentType() == null || document.getDocumentType().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Document type is required"));
            }

            List<String> validDocTypes = Arrays.asList(
                    "DEED", "MORTGAGE_NOTE", "CLOSING_DISCLOSURE", "TITLE_INSURANCE",
                    "PROPERTY_DEED", "SURVEY", "FLOOD_CERT", "PEST_INSPECTION",
                    "HOME_WARRANTY", "SETTLEMENT_STATEMENT", "TAX_PRORATION", "OTHER"
            );
            if (!validDocTypes.contains(document.getDocumentType())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid document type",
                        "validTypes", validDocTypes
                ));
            }

            if (document.getFileName() == null || document.getFileName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "File name is required"));
            }

            ClosingDocument saved = closingService.addClosingDocument(id, document);

            // Anti-pattern: inline document completeness check after adding a document
            List<ClosingDocument> allDocs = closingDocumentRepository.findByClosingId(id);
            List<String> requiredTypes = Arrays.asList(
                    "DEED", "MORTGAGE_NOTE", "CLOSING_DISCLOSURE", "TITLE_INSURANCE"
            );
            List<String> presentTypes = new ArrayList<>();
            for (ClosingDocument doc : allDocs) {
                if (doc.getDocumentType() != null) {
                    presentTypes.add(doc.getDocumentType());
                }
            }
            List<String> missingTypes = new ArrayList<>();
            for (String required : requiredTypes) {
                if (!presentTypes.contains(required)) {
                    missingTypes.add(required);
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("document", saved);
            response.put("totalDocuments", allDocs.size());
            response.put("missingRequiredDocuments", missingTypes);
            response.put("allRequiredDocumentsPresent", missingTypes.isEmpty());
            response.put("message", "Closing document added successfully");

            // Anti-pattern: if all required docs are now present, notify
            if (missingTypes.isEmpty()) {
                log.info("All required closing documents are present for closing {}", id);
                Optional<ClosingDetail> closingOpt = closingDetailRepository.findById(id);
                if (closingOpt.isPresent()) {
                    ClosingDetail closing = closingOpt.get();
                    LoanApplication loan = closing.getLoanApplication();
                    if (loan != null && loan.getLoanOfficer() != null) {
                        notificationHelper.notifyAgent(loan.getLoanOfficer().getId(),
                                "All required closing documents are present for closing " + id +
                                        ". Ready for final review.");
                    }
                }
                response.put("readyForReview", true);
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            log.error("Error adding closing document for closing {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get all closing documents.
     * Anti-pattern: bypasses ClosingService.getClosingDocuments, goes directly to repo.
     * Includes inline signature tracking analytics.
     */
    @GetMapping("/{id}/documents")
    public ResponseEntity<?> getClosingDocuments(@PathVariable UUID id) {
        // Anti-pattern: doesn't verify closing exists before querying documents
        List<ClosingDocument> documents = closingDocumentRepository.findByClosingId(id);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("closingId", id);
        response.put("documents", documents);
        response.put("totalDocuments", documents.size());

        // Anti-pattern: inline signature tracking analytics in controller
        long signedCount = documents.stream()
                .filter(d -> Boolean.TRUE.equals(d.getSigned()))
                .count();
        long unsignedCount = documents.stream()
                .filter(d -> d.getSigned() == null || !d.getSigned())
                .count();

        response.put("signedCount", signedCount);
        response.put("unsignedCount", unsignedCount);
        response.put("signatureProgress",
                documents.isEmpty() ? "N/A" :
                        String.format("%.0f%%", (double) signedCount / documents.size() * 100));

        // Anti-pattern: list unsigned documents specifically for action tracking
        List<Map<String, Object>> pendingSignatures = new ArrayList<>();
        for (ClosingDocument doc : documents) {
            if (doc.getSigned() == null || !doc.getSigned()) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("documentId", doc.getId());
                pending.put("documentType", doc.getDocumentType());
                pending.put("fileName", doc.getFileName());
                pendingSignatures.add(pending);
            }
        }
        response.put("pendingSignatures", pendingSignatures);

        return ResponseEntity.ok(response);
    }

    // ==================== PAYMENTS ====================

    /**
     * Get payment history and schedule for a loan's closing.
     * Anti-pattern: bypasses service, queries multiple repos, inline payment analytics.
     * Combines payment history with amortization schedule in one massive response.
     */
    @GetMapping("/loans/{loanId}/payments")
    public ResponseEntity<?> getLoanPayments(@PathVariable UUID loanId) {
        // Anti-pattern: validate loan exists in controller
        LoanApplication loan = loanService.getLoanApplication(loanId);
        if (loan == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Loan application not found", "loanId", loanId.toString()));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loanId", loanId);

        // Anti-pattern: direct repo access for payment history
        List<LoanPayment> payments = loanPaymentRepository.findByLoanApplicationId(loanId);
        response.put("payments", payments);
        response.put("totalPaymentsMade", payments.size());

        // Anti-pattern: inline payment analytics — business logic in controller
        long paidCount = payments.stream()
                .filter(p -> "PAID".equals(p.getStatus()) || Constants.PAYMENT_ON_TIME.equals(p.getStatus()))
                .count();
        long lateCount = payments.stream()
                .filter(p -> Constants.PAYMENT_LATE.equals(p.getStatus()))
                .count();
        long missedCount = payments.stream()
                .filter(p -> Constants.PAYMENT_MISSED.equals(p.getStatus()))
                .count();

        response.put("paidOnTime", paidCount);
        response.put("latePayments", lateCount);
        response.put("missedPayments", missedCount);

        // Anti-pattern: calculate total paid and outstanding inline
        BigDecimal totalPaid = payments.stream()
                .filter(p -> "PAID".equals(p.getStatus()) || Constants.PAYMENT_ON_TIME.equals(p.getStatus()))
                .map(LoanPayment::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.put("totalAmountPaid", totalPaid);

        BigDecimal totalLateFees = payments.stream()
                .map(LoanPayment::getLateFee)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.put("totalLateFees", totalLateFees);

        // Anti-pattern: direct repo access for payment schedule (amortization)
        List<PaymentSchedule> schedule = paymentScheduleRepository.findByLoanApplicationId(loanId);
        response.put("scheduleGenerated", !schedule.isEmpty());
        response.put("totalScheduledPayments", schedule.size());

        if (!schedule.isEmpty()) {
            // Anti-pattern: find the next due payment by comparing against payments made
            int nextPaymentNumber = payments.size() + 1;
            PaymentSchedule nextPayment = schedule.stream()
                    .filter(s -> s.getPaymentNumber() != null && s.getPaymentNumber() == nextPaymentNumber)
                    .findFirst()
                    .orElse(null);

            if (nextPayment != null) {
                response.put("nextPaymentDue", nextPayment.getDueDate());
                response.put("nextPaymentAmount", nextPayment.getTotal());
                response.put("nextPaymentPrincipal", nextPayment.getPrincipal());
                response.put("nextPaymentInterest", nextPayment.getInterest());
                response.put("nextPaymentEscrow", nextPayment.getEscrow());
            }

            // Anti-pattern: calculate remaining balance from schedule
            PaymentSchedule lastScheduleEntry = schedule.get(schedule.size() - 1);
            response.put("finalPaymentDate", lastScheduleEntry.getDueDate());

            // Anti-pattern: try to find current remaining balance
            if (nextPayment != null) {
                response.put("estimatedRemainingBalance", nextPayment.getRemainingBalance());
            } else if (!schedule.isEmpty()) {
                response.put("estimatedRemainingBalance", schedule.get(0).getRemainingBalance());
            }

            // Anti-pattern: calculate total interest over life of loan inline
            BigDecimal totalInterest = schedule.stream()
                    .map(PaymentSchedule::getInterest)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            response.put("totalInterestOverLifeOfLoan", totalInterest);
        }

        // Anti-pattern: check for overdue payments inline
        List<LoanPayment> overdue = loanPaymentRepository.findOverduePayments(LocalDate.now());
        long overdueForThisLoan = overdue.stream()
                .filter(p -> p.getLoanApplication() != null &&
                        loanId.equals(p.getLoanApplication().getId()))
                .count();
        response.put("currentOverduePayments", overdueForThisLoan);

        if (overdueForThisLoan > 0) {
            response.put("delinquencyWarning",
                    "This loan has " + overdueForThisLoan + " overdue payment(s). " +
                            "Please contact the borrower.");
        }

        return ResponseEntity.ok(response);
    }
}
