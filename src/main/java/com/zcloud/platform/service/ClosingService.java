package com.zcloud.platform.service;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.*;
import com.zcloud.platform.repository.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ClosingService — handles closing, escrow, title, documents, and servicing setup.
 *
 * Anti-patterns:
 * - Injects LoanService, MasterService, UnderwritingService (dependency on everything)
 * - generatePaymentSchedule() builds 360 records inline (30-year mortgage)
 * - Amortization calculation duplicated from LoanService
 * - Commission calculation and creation done here (should be in MasterService or separate)
 * - Directly creates Commission records when closing completes
 * - Fourth copy of logAudit helper
 */
@Service
public class ClosingService {

    private static final Logger log = LoggerFactory.getLogger(ClosingService.class);

    // Anti-pattern: depends on ALL other god classes
    @Autowired
    private LoanService loanService;

    @Autowired
    private MasterService masterService;

    @Autowired
    private UnderwritingService underwritingService;

    @Autowired
    private ClosingDetailRepository closingDetailRepository;

    @Autowired
    private EscrowAccountRepository escrowAccountRepository;

    @Autowired
    private TitleReportRepository titleReportRepository;

    @Autowired
    private ClosingDocumentRepository closingDocumentRepository;

    @Autowired
    private PaymentScheduleRepository paymentScheduleRepository;

    @Autowired
    private LoanPaymentRepository loanPaymentRepository;

    @Autowired
    private EscrowDisbursementRepository escrowDisbursementRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private CommissionRepository commissionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationHelper notificationHelper;

    @Autowired
    private CacheManager cacheManager;

    // ==================== CLOSING ====================

    @Transactional
    public ClosingDetail scheduleClosing(UUID loanId, ClosingDetail closing) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        // Anti-pattern: should validate loan is in CLOSING status via LoanService
        if (!Constants.LOAN_STATUS_CLOSING.equals(loan.getStatus()) &&
                !Constants.LOAN_STATUS_APPROVED.equals(loan.getStatus())) {
            throw new RuntimeException("Loan must be in CLOSING or APPROVED status to schedule closing. Current: " + loan.getStatus());
        }

        closing.setLoanApplication(loan);

        // Anti-pattern: resolve listing by looking up all listings for the property
        if (closing.getListing() == null && loan.getProperty() != null) {
            List<Listing> listings = listingRepository.findByStatus(Constants.LISTING_PENDING);
            for (Listing listing : listings) {
                if (listing.getProperty() != null &&
                        listing.getProperty().getId().equals(loan.getProperty().getId())) {
                    closing.setListing(listing);
                    break;
                }
            }
            if (closing.getListing() == null) {
                // Anti-pattern: also check SOLD listings
                List<Listing> soldListings = listingRepository.findByStatus(Constants.LISTING_SOLD);
                for (Listing listing : soldListings) {
                    if (listing.getProperty() != null &&
                            listing.getProperty().getId().equals(loan.getProperty().getId())) {
                        closing.setListing(listing);
                        break;
                    }
                }
            }
        }

        if (closing.getClosingDate() == null) {
            closing.setClosingDate(LocalDate.now().plusDays(30)); // Anti-pattern: magic number
        }
        if (closing.getStatus() == null) {
            closing.setStatus(Constants.CLOSING_SCHEDULED);
        }

        // Anti-pattern: estimate closing costs inline with hardcoded percentages
        if (closing.getTotalClosingCosts() == null && loan.getLoanAmount() != null) {
            // Anti-pattern: closing costs as 3% of loan amount — hardcoded business rule
            BigDecimal estimatedCosts = loan.getLoanAmount()
                    .multiply(BigDecimal.valueOf(0.03))
                    .setScale(2, RoundingMode.HALF_UP);
            closing.setTotalClosingCosts(estimatedCosts);
            log.info("Estimated closing costs for loan {}: ${}", loanId, estimatedCosts);
        }
        if (closing.getSellerCredits() == null) {
            closing.setSellerCredits(BigDecimal.ZERO);
        }
        if (closing.getBuyerCredits() == null) {
            closing.setBuyerCredits(BigDecimal.ZERO);
        }

        // Anti-pattern: move loan to CLOSING status if it isn't already
        if (!Constants.LOAN_STATUS_CLOSING.equals(loan.getStatus())) {
            loan.setStatus(Constants.LOAN_STATUS_CLOSING);
            loanApplicationRepository.save(loan);
        }

        ClosingDetail saved = closingDetailRepository.save(closing);

        logAudit(null, "CREATE", "ClosingDetail", saved.getId(), null,
                "Closing date: " + saved.getClosingDate() + ", Loan: " + loanId);

        // Anti-pattern: inline notification
        if (loan.getBorrower() != null) {
            notificationHelper.sendNotification(loan.getBorrower().getId(),
                    "Closing Scheduled",
                    "Your closing is scheduled for " + saved.getClosingDate() +
                            " at " + (saved.getClosingLocation() != null ? saved.getClosingLocation() : "TBD"),
                    "EMAIL");
        }
        if (loan.getLoanOfficer() != null) {
            notificationHelper.notifyAgent(loan.getLoanOfficer().getId(),
                    "Closing scheduled for loan " + loanId + " on " + saved.getClosingDate());
        }

        return saved;
    }

    @Transactional
    public ClosingDetail updateClosingStatus(UUID closingId, String newStatus) {
        ClosingDetail closing = closingDetailRepository.findById(closingId).orElse(null);
        if (closing == null) {
            throw new RuntimeException("Closing not found: " + closingId);
        }

        String oldStatus = closing.getStatus();
        closing.setStatus(newStatus);

        ClosingDetail saved = closingDetailRepository.save(closing);

        logAudit(null, "STATUS_CHANGE", "ClosingDetail", closingId,
                "status:" + oldStatus, "status:" + newStatus);

        // Anti-pattern: massive side effects when closing completes
        if (Constants.CLOSING_COMPLETED.equals(newStatus)) {
            handleClosingCompleted(saved);
        }

        return saved;
    }

    /**
     * Handle all the side effects of a completed closing.
     * Anti-pattern: orchestrates across 5+ domains in a single method —
     * updates loan status, creates commissions, sets up escrow, generates payment schedule.
     */
    private void handleClosingCompleted(ClosingDetail closing) {
        LoanApplication loan = closing.getLoanApplication();

        // 1. Update loan status to FUNDED
        if (loan != null) {
            String oldStatus = loan.getStatus();
            loan.setStatus(Constants.LOAN_STATUS_FUNDED);
            loanApplicationRepository.save(loan);

            notificationHelper.notifyLoanStatusChange(loan.getId(), oldStatus, Constants.LOAN_STATUS_FUNDED);

            logAudit(null, "STATUS_CHANGE", "LoanApplication", loan.getId(),
                    "status:" + oldStatus, "status:FUNDED");
        }

        // 2. Anti-pattern: commission creation done here — should be in a CommissionService or MasterService
        if (closing.getListing() != null) {
            createCommissionsForClosing(closing);
        }

        // 3. Create escrow account
        if (loan != null) {
            createEscrowAccount(closing.getId());
        }

        // 4. Generate payment schedule
        if (loan != null) {
            generatePaymentSchedule(loan.getId());
        }

        // 5. Update listing status to SOLD
        if (closing.getListing() != null) {
            Listing listing = closing.getListing();
            listing.setStatus(Constants.LISTING_SOLD);
            listingRepository.save(listing);

            logAudit(null, "STATUS_CHANGE", "Listing", listing.getId(),
                    "status:PENDING", "status:SOLD");
        }

        // 6. Send celebration notification
        if (loan != null && loan.getBorrower() != null) {
            notificationHelper.sendNotification(loan.getBorrower().getId(),
                    "Congratulations! Your Loan Has Closed!",
                    "Your mortgage loan for $" + loan.getLoanAmount() +
                            " has been funded. Welcome to your new home!",
                    "EMAIL");
        }

        log.info("Closing {} completed — all side effects executed", closing.getId());
    }

    /**
     * Create commission records for listing agent and buyer agent.
     * Anti-pattern: commission logic embedded in ClosingService, uses hardcoded Constants rates.
     */
    private void createCommissionsForClosing(ClosingDetail closing) {
        Listing listing = closing.getListing();
        if (listing == null || listing.getListPrice() == null) {
            log.warn("Cannot create commissions — no listing or list price for closing {}", closing.getId());
            return;
        }

        BigDecimal salePrice = listing.getListPrice(); // Anti-pattern: uses list price, not actual sale price
        BigDecimal totalCommission = salePrice.multiply(BigDecimal.valueOf(Constants.DEFAULT_COMMISSION_RATE))
                .setScale(2, RoundingMode.HALF_UP);

        // Listing agent commission
        if (listing.getAgent() != null) {
            Commission listingCommission = new Commission();
            listingCommission.setAgent(listing.getAgent());
            listingCommission.setListing(listing);
            listingCommission.setTransactionId("TXN-" + closing.getId().toString().substring(0, 8));
            listingCommission.setCommissionRate(BigDecimal.valueOf(Constants.DEFAULT_COMMISSION_RATE * Constants.LISTING_AGENT_SPLIT));
            listingCommission.setAmount(totalCommission.multiply(BigDecimal.valueOf(Constants.LISTING_AGENT_SPLIT))
                    .setScale(2, RoundingMode.HALF_UP));
            listingCommission.setType("LISTING");
            listingCommission.setStatus("PENDING");

            commissionRepository.save(listingCommission);

            logAudit(null, "CREATE", "Commission", listingCommission.getId(), null,
                    "Listing agent commission: $" + listingCommission.getAmount());

            // Anti-pattern: notify agent about commission inline
            notificationHelper.notifyAgent(listing.getAgent().getId(),
                    "Commission of $" + listingCommission.getAmount() + " created for closing on " +
                            listing.getProperty().getAddressLine1());
        }

        // Buyer agent commission — anti-pattern: need to find buyer agent from the accepted offer
        LoanApplication loan = closing.getLoanApplication();
        if (loan != null && loan.getBorrower() != null) {
            // Anti-pattern: look up buyer agent from client's assigned agent
            Client buyer = loan.getBorrower();
            if (buyer.getAssignedAgent() != null) {
                Commission buyerCommission = new Commission();
                buyerCommission.setAgent(buyer.getAssignedAgent());
                buyerCommission.setListing(listing);
                buyerCommission.setTransactionId("TXN-" + closing.getId().toString().substring(0, 8));
                buyerCommission.setCommissionRate(BigDecimal.valueOf(Constants.DEFAULT_COMMISSION_RATE * Constants.BUYER_AGENT_SPLIT));
                buyerCommission.setAmount(totalCommission.multiply(BigDecimal.valueOf(Constants.BUYER_AGENT_SPLIT))
                        .setScale(2, RoundingMode.HALF_UP));
                buyerCommission.setType("BUYER");
                buyerCommission.setStatus("PENDING");

                commissionRepository.save(buyerCommission);

                logAudit(null, "CREATE", "Commission", buyerCommission.getId(), null,
                        "Buyer agent commission: $" + buyerCommission.getAmount());

                notificationHelper.notifyAgent(buyer.getAssignedAgent().getId(),
                        "Commission of $" + buyerCommission.getAmount() + " created for closing");
            }
        }
    }

    public ClosingDetail getClosingByLoan(UUID loanId) {
        // Anti-pattern: uses a method that returns a raw object (not Optional) — can return null
        return closingDetailRepository.findByLoanApplicationId(loanId);
    }

    // ==================== ESCROW ====================

    @Transactional
    public EscrowAccount createEscrowAccount(UUID closingId) {
        ClosingDetail closing = closingDetailRepository.findById(closingId).orElse(null);
        if (closing == null) {
            throw new RuntimeException("Closing not found: " + closingId);
        }

        // Anti-pattern: check if escrow already exists by loading all for this closing
        List<EscrowAccount> existing = escrowAccountRepository.findByClosingId(closingId);
        if (!existing.isEmpty()) {
            log.warn("Escrow account already exists for closing {} — returning existing", closingId);
            return existing.get(0); // Anti-pattern: returns first from list, assumes single result
        }

        EscrowAccount escrow = new EscrowAccount();
        escrow.setClosing(closing);

        // Anti-pattern: generate account number with timestamp — not unique enough
        escrow.setAccountNumber("ESC-" + System.currentTimeMillis() + "-" +
                closingId.toString().substring(0, 4).toUpperCase());

        // Anti-pattern: calculate escrow payment inline with hardcoded assumptions
        LoanApplication loan = closing.getLoanApplication();
        if (loan != null && loan.getProperty() != null) {
            // Assume annual property tax is 1.25% of loan amount — anti-pattern: hardcoded
            BigDecimal annualPropertyTax = loan.getLoanAmount()
                    .multiply(BigDecimal.valueOf(0.0125))
                    .setScale(2, RoundingMode.HALF_UP);
            // Assume annual insurance is 0.5% of loan amount — anti-pattern: hardcoded
            BigDecimal annualInsurance = loan.getLoanAmount()
                    .multiply(BigDecimal.valueOf(0.005))
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal monthlyEscrow = annualPropertyTax.add(annualInsurance)
                    .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);

            escrow.setMonthlyPayment(monthlyEscrow);

            // Anti-pattern: initial reserve = ESCROW_RESERVE_MONTHS * monthlyEscrow — hardcoded from Constants
            BigDecimal initialReserve = monthlyEscrow.multiply(BigDecimal.valueOf(Constants.ESCROW_RESERVE_MONTHS))
                    .setScale(2, RoundingMode.HALF_UP);
            escrow.setBalance(initialReserve);

            escrow.setPropertyTaxReserve(annualPropertyTax.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(Constants.ESCROW_RESERVE_MONTHS))
                    .setScale(2, RoundingMode.HALF_UP));
            escrow.setInsuranceReserve(annualInsurance.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(Constants.ESCROW_RESERVE_MONTHS))
                    .setScale(2, RoundingMode.HALF_UP));
        } else {
            escrow.setBalance(BigDecimal.ZERO);
            escrow.setMonthlyPayment(BigDecimal.ZERO);
            escrow.setPropertyTaxReserve(BigDecimal.ZERO);
            escrow.setInsuranceReserve(BigDecimal.ZERO);
        }

        EscrowAccount saved = escrowAccountRepository.save(escrow);

        logAudit(null, "CREATE", "EscrowAccount", saved.getId(), null,
                "Account: " + saved.getAccountNumber() + ", Balance: $" + saved.getBalance());

        return saved;
    }

    /**
     * Calculate total escrow payment for a closing.
     * Anti-pattern: duplicates calculation logic from createEscrowAccount.
     */
    public BigDecimal calculateEscrowPayment(UUID closingId) {
        ClosingDetail closing = closingDetailRepository.findById(closingId).orElse(null);
        if (closing == null) {
            return BigDecimal.ZERO;
        }

        LoanApplication loan = closing.getLoanApplication();
        if (loan == null || loan.getLoanAmount() == null) {
            return BigDecimal.ZERO;
        }

        // Anti-pattern: exact same hardcoded rates as createEscrowAccount
        BigDecimal annualTax = loan.getLoanAmount().multiply(BigDecimal.valueOf(0.0125));
        BigDecimal annualInsurance = loan.getLoanAmount().multiply(BigDecimal.valueOf(0.005));
        return annualTax.add(annualInsurance)
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal getEscrowBalance(UUID escrowId) {
        EscrowAccount escrow = escrowAccountRepository.findById(escrowId).orElse(null);
        if (escrow == null) {
            return BigDecimal.ZERO;
        }
        return escrow.getBalance();
    }

    // ==================== TITLE ====================

    @Transactional
    public TitleReport orderTitleReport(UUID closingId, TitleReport titleReport) {
        ClosingDetail closing = closingDetailRepository.findById(closingId).orElse(null);
        if (closing == null) {
            throw new RuntimeException("Closing not found: " + closingId);
        }

        titleReport.setClosing(closing);
        if (titleReport.getStatus() == null) {
            titleReport.setStatus("PENDING");
        }
        if (titleReport.getReportDate() == null) {
            titleReport.setReportDate(LocalDate.now());
        }

        TitleReport saved = titleReportRepository.save(titleReport);

        logAudit(null, "CREATE", "TitleReport", saved.getId(), null,
                "Company: " + saved.getTitleCompany() + ", Closing: " + closingId);

        return saved;
    }

    @Transactional
    public TitleReport updateTitleStatus(UUID titleId, String newStatus, String issues) {
        TitleReport title = titleReportRepository.findById(titleId).orElse(null);
        if (title == null) {
            return null; // Anti-pattern: returns null
        }

        title.setStatus(newStatus);
        if (issues != null) {
            title.setIssues(issues);
        }

        // Anti-pattern: if issues found, notify loan officer
        if ("ISSUES_FOUND".equals(newStatus) && title.getClosing() != null) {
            LoanApplication loan = title.getClosing().getLoanApplication();
            if (loan != null && loan.getLoanOfficer() != null) {
                notificationHelper.notifyAgent(loan.getLoanOfficer().getId(),
                        "Title issues found for closing " + title.getClosing().getId() +
                                ": " + issues);
            }
        }

        TitleReport saved = titleReportRepository.save(title);
        logAudit(null, "UPDATE", "TitleReport", titleId, null, "Status: " + newStatus);

        return saved;
    }

    public TitleReport getTitleByClosing(UUID closingId) {
        // Anti-pattern: returns List, takes first — should be Optional or single result
        List<TitleReport> reports = titleReportRepository.findByClosingId(closingId);
        return reports.isEmpty() ? null : reports.get(0);
    }

    // ==================== DOCUMENTS ====================

    @Transactional
    public ClosingDocument addClosingDocument(UUID closingId, ClosingDocument document) {
        ClosingDetail closing = closingDetailRepository.findById(closingId).orElse(null);
        if (closing == null) {
            throw new RuntimeException("Closing not found: " + closingId);
        }

        document.setClosing(closing);

        // Anti-pattern: generate file path inline
        if (document.getFilePath() == null && document.getFileName() != null) {
            document.setFilePath("/closings/" + closingId + "/docs/" + document.getFileName());
        }

        ClosingDocument saved = closingDocumentRepository.save(document);

        logAudit(null, "CREATE", "ClosingDocument", saved.getId(), null,
                "Type: " + saved.getDocumentType() + ", File: " + saved.getFileName());

        // Anti-pattern: check if all required docs are signed whenever a doc is added
        checkDocumentCompleteness(closingId);

        return saved;
    }

    /**
     * Anti-pattern: hardcoded list of required document types checked inline.
     */
    private void checkDocumentCompleteness(UUID closingId) {
        List<ClosingDocument> docs = closingDocumentRepository.findByClosingId(closingId);
        List<String> requiredTypes = Arrays.asList(
                "DEED", "MORTGAGE_NOTE", "CLOSING_DISCLOSURE", "TITLE_INSURANCE"
        );

        List<String> presentTypes = new ArrayList<>();
        for (ClosingDocument doc : docs) {
            if (doc.getDocumentType() != null) {
                presentTypes.add(doc.getDocumentType());
            }
        }

        List<String> missing = new ArrayList<>();
        for (String required : requiredTypes) {
            if (!presentTypes.contains(required)) {
                missing.add(required);
            }
        }

        if (missing.isEmpty()) {
            log.info("All required closing documents present for closing {}", closingId);
        } else {
            log.info("Missing closing documents for closing {}: {}", closingId, String.join(", ", missing));
        }
    }

    public List<ClosingDocument> getClosingDocuments(UUID closingId) {
        return closingDocumentRepository.findByClosingId(closingId);
    }

    // ==================== SERVICING SETUP ====================

    /**
     * Generate a full amortization schedule (360 records for a 30-year mortgage).
     * Anti-pattern: builds hundreds of records inline, amortization formula duplicated from LoanService,
     * no batching of inserts, no consideration for performance.
     */
    @Transactional
    public List<PaymentSchedule> generatePaymentSchedule(UUID loanId) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        // Anti-pattern: check if schedule already exists
        List<PaymentSchedule> existing = paymentScheduleRepository.findByLoanApplicationId(loanId);
        if (!existing.isEmpty()) {
            log.warn("Payment schedule already exists for loan {} ({} entries) — deleting and regenerating",
                    loanId, existing.size());
            // Anti-pattern: delete all and regenerate instead of updating
            paymentScheduleRepository.deleteAll(existing);
        }

        BigDecimal loanAmount = loan.getLoanAmount();
        BigDecimal interestRate = loan.getInterestRate();
        int termMonths = loan.getLoanTermMonths() != null ? loan.getLoanTermMonths() : 360;

        if (loanAmount == null || interestRate == null) {
            throw new RuntimeException("Loan amount and interest rate required to generate schedule");
        }

        // Anti-pattern: amortization calculation duplicated from LoanService
        double monthlyRate = interestRate.doubleValue() / 100.0 / 12.0;
        double monthlyPayment = loanAmount.doubleValue() *
                (monthlyRate * Math.pow(1 + monthlyRate, termMonths)) /
                (Math.pow(1 + monthlyRate, termMonths) - 1);

        // Anti-pattern: calculate escrow portion inline with hardcoded rates
        BigDecimal monthlyEscrow = loanAmount.multiply(BigDecimal.valueOf(0.0125 + 0.005))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);

        BigDecimal remainingBalance = loanAmount;
        List<PaymentSchedule> schedule = new ArrayList<>();
        LocalDate firstPaymentDate = loan.getEstimatedClosingDate() != null
                ? loan.getEstimatedClosingDate().plusMonths(1).withDayOfMonth(1)
                : LocalDate.now().plusMonths(2).withDayOfMonth(1);

        // Anti-pattern: builds 360 records in a loop — one DB insert per record
        for (int i = 1; i <= termMonths; i++) {
            PaymentSchedule entry = new PaymentSchedule();
            entry.setLoanApplication(loan);
            entry.setPaymentNumber(i);
            entry.setDueDate(firstPaymentDate.plusMonths(i - 1));

            // Calculate interest and principal for this period
            BigDecimal interestPortion = remainingBalance.multiply(BigDecimal.valueOf(monthlyRate))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalPortion = BigDecimal.valueOf(monthlyPayment)
                    .subtract(interestPortion)
                    .setScale(2, RoundingMode.HALF_UP);

            // Anti-pattern: handle rounding on last payment
            if (i == termMonths) {
                principalPortion = remainingBalance;
                interestPortion = BigDecimal.valueOf(monthlyPayment).subtract(principalPortion)
                        .max(BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP);
            }

            entry.setPrincipal(principalPortion);
            entry.setInterest(interestPortion);
            entry.setEscrow(monthlyEscrow);
            entry.setTotal(principalPortion.add(interestPortion).add(monthlyEscrow)
                    .setScale(2, RoundingMode.HALF_UP));

            remainingBalance = remainingBalance.subtract(principalPortion)
                    .max(BigDecimal.ZERO);
            entry.setRemainingBalance(remainingBalance.setScale(2, RoundingMode.HALF_UP));

            // Anti-pattern: saving each record individually — should batch insert
            PaymentSchedule saved = paymentScheduleRepository.save(entry);
            schedule.add(saved);
        }

        log.info("Generated {} payment schedule entries for loan {} — monthly P&I: ${:.2f}, escrow: ${}",
                schedule.size(), loanId, monthlyPayment, monthlyEscrow);

        logAudit(null, "CREATE", "PaymentSchedule", loanId, null,
                "Generated " + schedule.size() + " payment entries");

        return schedule;
    }

    /**
     * Calculate amortization details for a given period.
     * Anti-pattern: duplicated from LoanService.calculateMonthlyPayment AND from generatePaymentSchedule.
     * Third copy of the same formula in the codebase.
     */
    public Map<String, BigDecimal> calculateAmortization(BigDecimal loanAmount, BigDecimal interestRate,
                                                          int termMonths, int paymentNumber) {
        // Anti-pattern: returns Map<String, BigDecimal> instead of a typed DTO
        Map<String, BigDecimal> result = new LinkedHashMap<>();

        if (loanAmount == null || interestRate == null || termMonths <= 0) {
            result.put("error", BigDecimal.valueOf(-1));
            return result;
        }

        double monthlyRate = interestRate.doubleValue() / 100.0 / 12.0;
        double payment = loanAmount.doubleValue() *
                (monthlyRate * Math.pow(1 + monthlyRate, termMonths)) /
                (Math.pow(1 + monthlyRate, termMonths) - 1);

        // Calculate remaining balance at given payment number
        BigDecimal balance = loanAmount;
        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal totalPrincipal = BigDecimal.ZERO;

        for (int i = 1; i <= paymentNumber && i <= termMonths; i++) {
            BigDecimal interest = balance.multiply(BigDecimal.valueOf(monthlyRate))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal principal = BigDecimal.valueOf(payment).subtract(interest)
                    .setScale(2, RoundingMode.HALF_UP);

            totalInterest = totalInterest.add(interest);
            totalPrincipal = totalPrincipal.add(principal);
            balance = balance.subtract(principal).max(BigDecimal.ZERO);
        }

        result.put("monthlyPayment", BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP));
        result.put("remainingBalance", balance.setScale(2, RoundingMode.HALF_UP));
        result.put("totalInterestPaid", totalInterest.setScale(2, RoundingMode.HALF_UP));
        result.put("totalPrincipalPaid", totalPrincipal.setScale(2, RoundingMode.HALF_UP));
        result.put("equityPercent", totalPrincipal.divide(loanAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)));

        return result;
    }

    // ==================== HELPER ====================

    /**
     * Anti-pattern: FOURTH copy of the logAudit helper method.
     * Now in MasterService, LoanService, UnderwritingService, and ClosingService.
     */
    private void logAudit(UUID userId, String action, String resourceType, UUID resourceId,
                          String oldValue, String newValue) {
        try {
            AuditLog audit = new AuditLog();
            audit.setUserId(userId);
            audit.setAction(action);
            audit.setResourceType(resourceType);
            audit.setResourceId(resourceId != null ? resourceId.toString() : null);
            audit.setOldValue(oldValue);
            audit.setNewValue(newValue);
            audit.setIpAddress("0.0.0.0");
            auditLogRepository.save(audit);
        } catch (Exception e) {
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }
}
