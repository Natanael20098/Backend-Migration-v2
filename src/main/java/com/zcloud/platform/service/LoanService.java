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
import java.util.*;
import java.util.stream.Collectors;

/**
 * SECOND GOD CLASS — LoanService handles the entire mortgage loan lifecycle:
 * origination, borrower info, document management, and status transitions.
 *
 * Anti-patterns:
 * - Injects MasterService (creates coupling between god classes)
 * - Credit score generation with Math.random() hardcoded
 * - Monthly payment calculation done inline (duplicates entity logic)
 * - DTI ratio calculation with hardcoded thresholds
 * - Sends notifications inline when status changes
 * - No proper state machine for loan status transitions (just string comparisons)
 * - Creates audit log entries directly
 * - 8+ repository injections
 */
@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    // Anti-pattern: 8+ repository injections
    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerEmploymentRepository borrowerEmploymentRepository;

    @Autowired
    private BorrowerAssetRepository borrowerAssetRepository;

    @Autowired
    private CreditReportRepository creditReportRepository;

    @Autowired
    private ClientDocumentRepository clientDocumentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    // Anti-pattern: injects the other god class — creates coupling between god classes
    @Autowired
    private MasterService masterService;

    @Autowired
    private NotificationHelper notificationHelper;

    @Autowired
    private CacheManager cacheManager;

    // ==================== LOAN ORIGINATION ====================

    /**
     * Create a new loan application with inline validation, business rules,
     * payment calculation, and notification sending.
     */
    @Transactional
    public LoanApplication createLoanApplication(LoanApplication application) {
        // Anti-pattern: inline validation spread across 40+ lines
        if (application.getBorrower() == null || application.getBorrower().getId() == null) {
            throw new RuntimeException("Borrower is required for loan application");
        }

        // Anti-pattern: uses MasterService to resolve client (coupling between god classes)
        Client borrower = masterService.getClient(application.getBorrower().getId());
        if (borrower == null) {
            throw new RuntimeException("Borrower not found: " + application.getBorrower().getId());
        }
        application.setBorrower(borrower);

        if (application.getLoanAmount() == null || application.getLoanAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Loan amount must be positive");
        }

        // Anti-pattern: validate loan type against hardcoded list
        if (application.getLoanType() != null) {
            List<String> validTypes = Arrays.asList(
                    Constants.LOAN_TYPE_CONVENTIONAL, Constants.LOAN_TYPE_FHA,
                    Constants.LOAN_TYPE_VA, Constants.LOAN_TYPE_USDA,
                    Constants.LOAN_TYPE_JUMBO
            );
            if (!validTypes.contains(application.getLoanType())) {
                throw new RuntimeException("Invalid loan type: " + application.getLoanType());
            }
        } else {
            application.setLoanType(Constants.LOAN_TYPE_CONVENTIONAL);
        }

        // Anti-pattern: inline business rule — jumbo loan minimum
        if (Constants.LOAN_TYPE_JUMBO.equals(application.getLoanType())
                && application.getLoanAmount().compareTo(BigDecimal.valueOf(726200)) <= 0) {
            throw new RuntimeException("Jumbo loans must exceed $726,200");
        }

        // Anti-pattern: default values scattered throughout create method
        if (application.getStatus() == null) {
            application.setStatus("DRAFT");
        }
        if (application.getApplicationDate() == null) {
            application.setApplicationDate(LocalDate.now());
        }
        if (application.getLoanTermMonths() == null) {
            application.setLoanTermMonths(360); // Anti-pattern: magic number for 30-year mortgage
        }
        if (application.getInterestRate() == null) {
            // Anti-pattern: hardcoded default interest rate
            application.setInterestRate(BigDecimal.valueOf(6.75));
        }

        // Anti-pattern: down payment calculation done inline
        if (application.getDownPayment() == null) {
            // Default 20% down
            BigDecimal purchasePrice = application.getLoanAmount().multiply(BigDecimal.valueOf(1.25));
            application.setDownPayment(purchasePrice.multiply(BigDecimal.valueOf(0.20))
                    .setScale(2, RoundingMode.HALF_UP));
        }

        // Anti-pattern: monthly payment calculation duplicated from entity @PostLoad
        if (application.getInterestRate() != null && application.getLoanTermMonths() != null
                && application.getLoanTermMonths() > 0) {
            double monthlyRate = application.getInterestRate().doubleValue() / 100.0 / 12.0;
            int n = application.getLoanTermMonths();
            double loanAmt = application.getLoanAmount().doubleValue();
            double payment = loanAmt * (monthlyRate * Math.pow(1 + monthlyRate, n))
                    / (Math.pow(1 + monthlyRate, n) - 1);
            application.setMonthlyPayment(BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP));
            log.info("Calculated monthly payment: ${} for loan amount ${}",
                    application.getMonthlyPayment(), application.getLoanAmount());
        }

        // Resolve property if provided
        if (application.getProperty() != null && application.getProperty().getId() != null) {
            Property property = propertyRepository.findById(application.getProperty().getId()).orElse(null);
            if (property == null) {
                log.warn("Property {} not found — creating loan without property", application.getProperty().getId());
                application.setProperty(null);
            } else {
                application.setProperty(property);
            }
        }

        // Resolve loan officer if provided
        if (application.getLoanOfficer() != null && application.getLoanOfficer().getId() != null) {
            Agent loanOfficer = masterService.getAgent(application.getLoanOfficer().getId());
            if (loanOfficer != null) {
                application.setLoanOfficer(loanOfficer);
            }
        }

        LoanApplication saved = loanApplicationRepository.save(application);

        // Anti-pattern: audit log creation inline
        logAudit(borrower.getId(), "CREATE", "LoanApplication", saved.getId(), null,
                "Type: " + saved.getLoanType() + ", Amount: $" + saved.getLoanAmount());

        // Anti-pattern: notification inline
        if (saved.getLoanOfficer() != null) {
            notificationHelper.notifyAgent(saved.getLoanOfficer().getId(),
                    "New loan application from " + borrower.getFullName() + " for $" + saved.getLoanAmount());
        }

        cacheManager.put("loan:" + saved.getId(), saved);

        return saved;
    }

    @Transactional
    public LoanApplication updateLoanApplication(UUID loanId, LoanApplication updates) {
        LoanApplication existing = loanApplicationRepository.findById(loanId).orElse(null);
        if (existing == null) {
            return null; // Anti-pattern: returns null
        }

        // Anti-pattern: only allow updates in certain statuses
        if ("FUNDED".equals(existing.getStatus()) || "DENIED".equals(existing.getStatus())) {
            throw new RuntimeException("Cannot update a " + existing.getStatus() + " loan application");
        }

        String oldValue = "Amount: " + existing.getLoanAmount() + ", Rate: " + existing.getInterestRate();

        if (updates.getLoanAmount() != null) existing.setLoanAmount(updates.getLoanAmount());
        if (updates.getInterestRate() != null) existing.setInterestRate(updates.getInterestRate());
        if (updates.getLoanTermMonths() != null) existing.setLoanTermMonths(updates.getLoanTermMonths());
        if (updates.getDownPayment() != null) existing.setDownPayment(updates.getDownPayment());
        if (updates.getLoanType() != null) existing.setLoanType(updates.getLoanType());
        if (updates.getNotes() != null) existing.setNotes(updates.getNotes());
        if (updates.getEstimatedClosingDate() != null) existing.setEstimatedClosingDate(updates.getEstimatedClosingDate());

        // Anti-pattern: recalculate monthly payment inline (duplicated)
        if (existing.getInterestRate() != null && existing.getLoanTermMonths() != null
                && existing.getLoanTermMonths() > 0 && existing.getLoanAmount() != null) {
            double monthlyRate = existing.getInterestRate().doubleValue() / 100.0 / 12.0;
            int n = existing.getLoanTermMonths();
            double loanAmt = existing.getLoanAmount().doubleValue();
            double payment = loanAmt * (monthlyRate * Math.pow(1 + monthlyRate, n))
                    / (Math.pow(1 + monthlyRate, n) - 1);
            existing.setMonthlyPayment(BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP));
        }

        LoanApplication saved = loanApplicationRepository.save(existing);
        logAudit(null, "UPDATE", "LoanApplication", loanId, oldValue,
                "Amount: " + saved.getLoanAmount() + ", Rate: " + saved.getInterestRate());
        cacheManager.invalidate("loan:");

        return saved;
    }

    public LoanApplication getLoanApplication(UUID loanId) {
        LoanApplication cached = cacheManager.getTyped("loan:" + loanId, LoanApplication.class);
        if (cached != null) return cached;

        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan != null) {
            cacheManager.put("loan:" + loanId, loan);
        }
        return loan;
    }

    public List<LoanApplication> getLoansByBorrower(UUID borrowerId) {
        return loanApplicationRepository.findByBorrowerId(borrowerId);
    }

    public List<LoanApplication> getLoansByOfficer(UUID officerId) {
        return loanApplicationRepository.findByLoanOfficerId(officerId);
    }

    /**
     * Get active loan pipeline for a loan officer.
     * Anti-pattern: loads all loans then filters in memory instead of using a targeted query.
     */
    public Map<String, Object> getLoanPipeline(UUID officerId) {
        // Anti-pattern: returns Map<String, Object> instead of a typed DTO
        Map<String, Object> pipeline = new LinkedHashMap<>();

        List<LoanApplication> allLoans = loanApplicationRepository.findByLoanOfficerId(officerId);

        // Anti-pattern: in-memory grouping instead of GROUP BY query
        Map<String, List<LoanApplication>> byStatus = allLoans.stream()
                .collect(Collectors.groupingBy(LoanApplication::getStatus));

        pipeline.put("officerId", officerId);
        pipeline.put("totalLoans", allLoans.size());
        pipeline.put("loansByStatus", byStatus);

        // Anti-pattern: calculating totals in Java instead of SQL SUM
        BigDecimal totalVolume = allLoans.stream()
                .map(LoanApplication::getLoanAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        pipeline.put("totalVolume", totalVolume);

        // Anti-pattern: inline calculation of pipeline statistics
        long activeCount = allLoans.stream()
                .filter(l -> !"FUNDED".equals(l.getStatus()) && !"DENIED".equals(l.getStatus()))
                .count();
        pipeline.put("activeCount", activeCount);

        BigDecimal avgLoanAmount = allLoans.isEmpty() ? BigDecimal.ZERO
                : totalVolume.divide(BigDecimal.valueOf(allLoans.size()), 2, RoundingMode.HALF_UP);
        pipeline.put("averageLoanAmount", avgLoanAmount);

        return pipeline;
    }

    // ==================== BORROWER INFORMATION ====================

    @Transactional
    public BorrowerEmployment addEmployment(UUID loanId, BorrowerEmployment employment) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        employment.setLoanApplication(loan);

        // Anti-pattern: inline validation
        if (employment.getEmployerName() == null || employment.getEmployerName().trim().isEmpty()) {
            throw new RuntimeException("Employer name is required");
        }
        if (employment.getMonthlyIncome() == null || employment.getMonthlyIncome().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Monthly income must be positive");
        }

        // Anti-pattern: default verification status
        if (employment.getVerificationStatus() == null) {
            employment.setVerificationStatus("PENDING");
        }

        BorrowerEmployment saved = borrowerEmploymentRepository.save(employment);
        logAudit(null, "CREATE", "BorrowerEmployment", saved.getId(), null,
                "Employer: " + saved.getEmployerName() + ", Income: $" + saved.getMonthlyIncome());

        return saved;
    }

    @Transactional
    public BorrowerAsset addAsset(UUID loanId, BorrowerAsset asset) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        asset.setLoanApplication(loan);

        if (asset.getAssetType() == null) {
            asset.setAssetType("CHECKING"); // Anti-pattern: hardcoded default
        }
        if (asset.getBalance() == null || asset.getBalance().compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("Asset balance must be non-negative");
        }

        BorrowerAsset saved = borrowerAssetRepository.save(asset);
        logAudit(null, "CREATE", "BorrowerAsset", saved.getId(), null,
                "Type: " + saved.getAssetType() + ", Balance: $" + saved.getBalance());

        return saved;
    }

    /**
     * Pull a "credit report" for the borrower.
     * Anti-pattern: generates FAKE credit scores with Math.random() — security and compliance nightmare.
     * In production, this would call Equifax/Experian/TransUnion APIs.
     */
    @Transactional
    public CreditReport pullCreditReport(UUID loanId, String bureau) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        // Anti-pattern: validate bureau with hardcoded strings
        if (bureau == null) {
            bureau = "EQUIFAX"; // Anti-pattern: hardcoded default bureau
        }
        if (!Arrays.asList("EQUIFAX", "EXPERIAN", "TRANSUNION").contains(bureau.toUpperCase())) {
            throw new RuntimeException("Invalid credit bureau: " + bureau);
        }

        // Anti-pattern: FAKE credit score generation with Math.random()
        // This is a massive compliance violation in a real system
        int fakeScore = 580 + (int) (Math.random() * 270); // Random score between 580-850
        log.warn("GENERATING FAKE CREDIT SCORE: {} from {} — THIS IS NOT PRODUCTION CODE", fakeScore, bureau);

        // Anti-pattern: fake credit report data built inline
        String fakeReportData = String.format(
                "{\"bureau\":\"%s\",\"score\":%d,\"accounts\":%d,\"derogatory\":%d," +
                        "\"inquiries\":%d,\"collections\":%d,\"publicRecords\":%d," +
                        "\"totalDebt\":%.2f,\"creditUtilization\":%.1f}",
                bureau, fakeScore,
                10 + (int) (Math.random() * 20),    // accounts
                (int) (Math.random() * 3),           // derogatory marks
                (int) (Math.random() * 5),           // inquiries
                (int) (Math.random() * 2),           // collections
                0,                                    // public records
                5000 + Math.random() * 50000,        // total debt
                Math.random() * 60                    // utilization %
        );

        CreditReport report = new CreditReport();
        report.setLoanApplication(loan);
        report.setBureau(bureau.toUpperCase());
        report.setScore(fakeScore);
        report.setReportDate(LocalDate.now());
        report.setReportData(fakeReportData);
        report.setPulledBy(null); // Anti-pattern: no system user UUID available

        CreditReport saved = creditReportRepository.save(report);

        logAudit(null, "CREATE", "CreditReport", saved.getId(), null,
                "Bureau: " + bureau + ", Score: " + fakeScore + " (SIMULATED)");

        // Anti-pattern: inline notification about credit pull
        if (loan.getBorrower() != null) {
            notificationHelper.sendNotification(loan.getBorrower().getId(),
                    "Credit Report Pulled",
                    "A credit report has been pulled from " + bureau + ". Your score: " + fakeScore,
                    "IN_APP");
            // Anti-pattern: sending the credit score in a notification — PII exposure
        }

        return saved;
    }

    // ==================== DOCUMENT MANAGEMENT ====================

    @Transactional
    public ClientDocument uploadDocument(UUID loanId, ClientDocument document) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        if (loan.getBorrower() == null) {
            throw new RuntimeException("Loan has no borrower — cannot attach document");
        }

        document.setClient(loan.getBorrower());

        // Anti-pattern: inline file path generation — should use a storage service
        if (document.getFilePath() == null) {
            document.setFilePath("/uploads/loans/" + loanId + "/" + document.getFileName());
        }

        ClientDocument saved = clientDocumentRepository.save(document);
        logAudit(null, "CREATE", "ClientDocument", saved.getId(), null,
                "Type: " + saved.getDocumentType() + ", File: " + saved.getFileName());

        return saved;
    }

    public List<ClientDocument> getDocumentsByLoan(UUID loanId) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null || loan.getBorrower() == null) {
            return Collections.emptyList();
        }
        // Anti-pattern: loads ALL documents for the client, not just for this loan
        return clientDocumentRepository.findAll().stream()
                .filter(d -> d.getClient() != null && d.getClient().getId().equals(loan.getBorrower().getId()))
                .collect(Collectors.toList());
    }

    // ==================== STATUS TRANSITIONS ====================

    /**
     * Submit loan for processing.
     * Anti-pattern: no proper state machine — just string comparison and manual transitions.
     */
    @Transactional
    public LoanApplication submitForProcessing(UUID loanId) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        // Anti-pattern: inline status validation — no state machine
        if (!"DRAFT".equals(loan.getStatus()) && !"STARTED".equals(loan.getStatus())) {
            throw new RuntimeException("Loan must be in DRAFT or STARTED status to submit for processing. Current: " + loan.getStatus());
        }

        // Anti-pattern: inline completeness check — should be in a validator
        List<BorrowerEmployment> employments = borrowerEmploymentRepository.findByLoanApplicationId(loanId);
        if (employments.isEmpty()) {
            throw new RuntimeException("At least one employment record is required before submitting");
        }

        List<BorrowerAsset> assets = borrowerAssetRepository.findByLoanApplicationId(loanId);
        if (assets.isEmpty()) {
            log.warn("No assets recorded for loan {} — proceeding anyway", loanId);
            // Anti-pattern: warns but doesn't block — inconsistent with employment check above
        }

        String oldStatus = loan.getStatus();
        loan.setStatus(Constants.LOAN_STATUS_PROCESSING);
        LoanApplication saved = loanApplicationRepository.save(loan);

        logAudit(null, "STATUS_CHANGE", "LoanApplication", loanId,
                "status:" + oldStatus, "status:" + Constants.LOAN_STATUS_PROCESSING);

        // Anti-pattern: inline notification sending
        notificationHelper.notifyLoanStatusChange(loanId, oldStatus, Constants.LOAN_STATUS_PROCESSING);

        cacheManager.invalidate("loan:");
        return saved;
    }

    /**
     * Submit loan for underwriting.
     * Anti-pattern: DTI ratio calculation with hardcoded thresholds from Constants.
     */
    @Transactional
    public LoanApplication submitForUnderwriting(UUID loanId) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        if (!Constants.LOAN_STATUS_PROCESSING.equals(loan.getStatus())) {
            throw new RuntimeException("Loan must be in PROCESSING status to submit for underwriting");
        }

        // Anti-pattern: check that credit report exists
        List<CreditReport> creditReports = creditReportRepository.findByLoanApplicationId(loanId);
        if (creditReports.isEmpty()) {
            throw new RuntimeException("Credit report is required before underwriting");
        }

        // Anti-pattern: DTI calculation done inline with hardcoded thresholds
        List<BorrowerEmployment> employments = borrowerEmploymentRepository.findByLoanApplicationId(loanId);
        BigDecimal totalMonthlyIncome = employments.stream()
                .filter(e -> e.getIsCurrent() != null && e.getIsCurrent())
                .map(BorrowerEmployment::getMonthlyIncome)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalMonthlyIncome.compareTo(BigDecimal.ZERO) > 0 && loan.getMonthlyPayment() != null) {
            double dti = loan.getMonthlyPayment().doubleValue() / totalMonthlyIncome.doubleValue();
            if (dti > Constants.MAX_DTI_RATIO) {
                log.warn("DTI ratio {:.2f} exceeds maximum {} for loan {} — flagging for manual review",
                        dti, Constants.MAX_DTI_RATIO, loanId);
                // Anti-pattern: doesn't block — just logs a warning
            }
            log.info("Loan {} front-end DTI: {:.4f}", loanId, dti);
        }

        String oldStatus = loan.getStatus();
        loan.setStatus(Constants.LOAN_STATUS_UNDERWRITING);
        LoanApplication saved = loanApplicationRepository.save(loan);

        logAudit(null, "STATUS_CHANGE", "LoanApplication", loanId,
                "status:" + oldStatus, "status:" + Constants.LOAN_STATUS_UNDERWRITING);

        notificationHelper.notifyLoanStatusChange(loanId, oldStatus, Constants.LOAN_STATUS_UNDERWRITING);

        cacheManager.invalidate("loan:");
        return saved;
    }

    /**
     * Move loan to closing status.
     * Anti-pattern: modifies loan status directly, no validation of underwriting approval.
     */
    @Transactional
    public LoanApplication moveToClosing(UUID loanId) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        // Anti-pattern: checks multiple statuses with OR — fragile
        if (!Constants.LOAN_STATUS_APPROVED.equals(loan.getStatus()) &&
                !"CONDITIONALLY_APPROVED".equals(loan.getStatus())) {
            throw new RuntimeException("Loan must be APPROVED or CONDITIONALLY_APPROVED to move to closing");
        }

        // Anti-pattern: should verify all underwriting conditions are satisfied, but doesn't
        log.info("Moving loan {} to closing — skipping condition verification", loanId);

        String oldStatus = loan.getStatus();
        loan.setStatus(Constants.LOAN_STATUS_CLOSING);

        // Anti-pattern: set estimated closing date inline if not already set
        if (loan.getEstimatedClosingDate() == null) {
            loan.setEstimatedClosingDate(LocalDate.now().plusDays(30)); // Magic number
        }

        LoanApplication saved = loanApplicationRepository.save(loan);

        logAudit(null, "STATUS_CHANGE", "LoanApplication", loanId,
                "status:" + oldStatus, "status:" + Constants.LOAN_STATUS_CLOSING);

        notificationHelper.notifyLoanStatusChange(loanId, oldStatus, Constants.LOAN_STATUS_CLOSING);

        cacheManager.invalidate("loan:");
        return saved;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Calculate monthly payment for given loan parameters.
     * Anti-pattern: duplicated from LoanApplication entity and from createLoanApplication above.
     * Three places with the same formula — guaranteed to diverge.
     */
    public BigDecimal calculateMonthlyPayment(BigDecimal loanAmount, BigDecimal interestRate, int termMonths) {
        if (loanAmount == null || interestRate == null || termMonths <= 0) {
            return BigDecimal.ZERO;
        }
        double monthlyRate = interestRate.doubleValue() / 100.0 / 12.0;
        double amount = loanAmount.doubleValue();
        double payment = amount * (monthlyRate * Math.pow(1 + monthlyRate, termMonths))
                / (Math.pow(1 + monthlyRate, termMonths) - 1);
        return BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate DTI ratio for a loan.
     * Anti-pattern: public utility method that could be called from anywhere,
     * duplicates logic from submitForUnderwriting.
     */
    public double calculateDTI(UUID loanId) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            return 0.0;
        }

        List<BorrowerEmployment> employments = borrowerEmploymentRepository.findByLoanApplicationId(loanId);
        BigDecimal totalIncome = employments.stream()
                .filter(e -> e.getIsCurrent() != null && e.getIsCurrent())
                .map(BorrowerEmployment::getMonthlyIncome)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalIncome.compareTo(BigDecimal.ZERO) == 0) {
            return 99.99; // Anti-pattern: magic number for "infinite" DTI
        }

        BigDecimal monthlyPayment = loan.getMonthlyPayment();
        if (monthlyPayment == null) {
            monthlyPayment = calculateMonthlyPayment(loan.getLoanAmount(),
                    loan.getInterestRate(), loan.getLoanTermMonths());
        }

        return monthlyPayment.doubleValue() / totalIncome.doubleValue();
    }

    /**
     * Anti-pattern: exact duplicate of the logAudit method in MasterService.
     * Two separate copies of the same helper — will inevitably diverge.
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
