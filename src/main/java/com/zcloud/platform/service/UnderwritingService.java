package com.zcloud.platform.service;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.*;
import com.zcloud.platform.repository.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
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
 * UnderwritingService — handles underwriting decisions, appraisals, and risk assessment.
 *
 * Anti-patterns:
 * - Injects LoanService AND MasterService (circular dependency chain)
 * - calculateRiskScore() is 100+ lines of nested if/else with hardcoded thresholds
 * - Auto-underwrite engine checks credit score, DTI, LTV all inline
 * - Modifies LoanApplication status directly (bypassing LoanService)
 * - Raw SQL query via EntityManager for complex risk analysis
 * - Creates audit log entries directly (third copy of logAudit helper)
 */
@Service
public class UnderwritingService {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingService.class);

    // Anti-pattern: injects BOTH god classes — circular dependency chain
    @Autowired
    private LoanService loanService;

    @Autowired
    private MasterService masterService;

    @Autowired
    private UnderwritingDecisionRepository underwritingDecisionRepository;

    @Autowired
    private UnderwritingConditionRepository underwritingConditionRepository;

    @Autowired
    private AppraisalOrderRepository appraisalOrderRepository;

    @Autowired
    private AppraisalReportRepository appraisalReportRepository;

    @Autowired
    private ComparableSaleRepository comparableSaleRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private CreditReportRepository creditReportRepository;

    @Autowired
    private BorrowerEmploymentRepository borrowerEmploymentRepository;

    @Autowired
    private BorrowerAssetRepository borrowerAssetRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationHelper notificationHelper;

    @Autowired
    private CacheManager cacheManager;

    // Anti-pattern: raw EntityManager injection for hand-written SQL
    @PersistenceContext
    private EntityManager entityManager;

    // ==================== UNDERWRITING ====================

    @Transactional
    public UnderwritingDecision createDecision(UUID loanId, UnderwritingDecision decision) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        // Anti-pattern: should check that loan is in UNDERWRITING status, but only warns
        if (!Constants.LOAN_STATUS_UNDERWRITING.equals(loan.getStatus())) {
            log.warn("Creating underwriting decision for loan {} in status {} — expected UNDERWRITING",
                    loanId, loan.getStatus());
        }

        decision.setLoanApplication(loan);

        if (decision.getDecisionDate() == null) {
            decision.setDecisionDate(new java.sql.Timestamp(System.currentTimeMillis()));
        }

        // Anti-pattern: resolve underwriter via MasterService (coupling)
        if (decision.getUnderwriter() != null && decision.getUnderwriter().getId() != null) {
            Agent underwriter = masterService.getAgent(decision.getUnderwriter().getId());
            decision.setUnderwriter(underwriter);
        }

        UnderwritingDecision saved = underwritingDecisionRepository.save(decision);

        // Anti-pattern: modifies LoanApplication status directly, bypassing LoanService
        if (Constants.UW_APPROVED.equals(decision.getDecision())) {
            loan.setStatus(Constants.LOAN_STATUS_APPROVED);
            loanApplicationRepository.save(loan);
            log.info("Loan {} APPROVED by underwriting", loanId);
        } else if (Constants.UW_DENIED.equals(decision.getDecision())) {
            loan.setStatus(Constants.LOAN_STATUS_DENIED);
            loanApplicationRepository.save(loan);
            log.info("Loan {} DENIED by underwriting", loanId);
        } else if (Constants.UW_CONDITIONAL.equals(decision.getDecision())) {
            loan.setStatus(Constants.LOAN_STATUS_CONDITIONALLY_APPROVED);
            loanApplicationRepository.save(loan);
            log.info("Loan {} CONDITIONALLY APPROVED", loanId);
        } else if (Constants.UW_SUSPENDED.equals(decision.getDecision())) {
            loan.setStatus(Constants.LOAN_STATUS_SUSPENDED);
            loanApplicationRepository.save(loan);
            log.info("Loan {} SUSPENDED by underwriting", loanId);
        }

        logAudit(null, "CREATE", "UnderwritingDecision", saved.getId(), null,
                "Decision: " + saved.getDecision() + ", Loan: " + loanId);

        // Anti-pattern: inline notification
        notificationHelper.notifyLoanStatusChange(loanId,
                Constants.LOAN_STATUS_UNDERWRITING, loan.getStatus());

        cacheManager.invalidate("loan:");

        return saved;
    }

    /**
     * Auto-underwrite a loan application using hardcoded rules.
     * This is the core automated decision engine — and it's a giant inline mess.
     *
     * Anti-pattern: 100+ lines of nested if/else with hardcoded thresholds,
     * mixes credit score checks, DTI, LTV, asset verification, and risk scoring
     * all in one method.
     */
    @Transactional
    public UnderwritingDecision evaluateLoan(UUID loanId) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        // Anti-pattern: inline data gathering that should be in separate methods
        List<CreditReport> creditReports = creditReportRepository.findByLoanApplicationId(loanId);
        if (creditReports.isEmpty()) {
            throw new RuntimeException("No credit reports found — cannot underwrite loan " + loanId);
        }

        // Anti-pattern: find the middle score by sorting and taking the middle
        List<Integer> scores = new ArrayList<>();
        for (CreditReport report : creditReports) {
            if (report.getScore() != null) {
                scores.add(report.getScore());
            }
        }
        Collections.sort(scores);
        int representativeScore = scores.isEmpty() ? 0 : scores.get(scores.size() / 2);

        // Anti-pattern: DTI calculation duplicated from LoanService
        double dti = loanService.calculateDTI(loanId);

        // Anti-pattern: LTV calculation inline
        double ltv = 1.0; // default 100% LTV
        if (loan.getProperty() != null && loan.getLoanAmount() != null && loan.getDownPayment() != null) {
            BigDecimal purchasePrice = loan.getLoanAmount().add(loan.getDownPayment());
            if (purchasePrice.compareTo(BigDecimal.ZERO) > 0) {
                ltv = loan.getLoanAmount().doubleValue() / purchasePrice.doubleValue();
            }
        }

        // Calculate risk score using the massive inline method
        int riskScore = calculateRiskScore(loan, representativeScore, dti, ltv, creditReports);

        // Anti-pattern: auto-decision with hardcoded thresholds
        String decision;
        String notes;
        List<String> conditionsToAdd = new ArrayList<>();

        // Anti-pattern: massive if/else chain for decision logic
        if (representativeScore < Constants.MIN_CREDIT_SCORE_FHA) {
            decision = Constants.UW_DENIED;
            notes = "Credit score " + representativeScore + " below minimum threshold of " +
                    Constants.MIN_CREDIT_SCORE_FHA;
        } else if (dti > 0.50) {
            decision = Constants.UW_DENIED;
            notes = "DTI ratio " + String.format("%.2f", dti) + " exceeds maximum allowable ratio of 0.50";
        } else if (riskScore >= 80) {
            decision = Constants.UW_APPROVED;
            notes = "Auto-approved. Risk score: " + riskScore + ", Credit: " + representativeScore +
                    ", DTI: " + String.format("%.2f", dti) + ", LTV: " + String.format("%.2f", ltv);
        } else if (riskScore >= 60) {
            decision = Constants.UW_CONDITIONAL;
            notes = "Conditionally approved. Risk score: " + riskScore;

            // Anti-pattern: hardcoded conditions based on various factors
            if (dti > Constants.MAX_DTI_RATIO) {
                conditionsToAdd.add("Provide explanation letter for high DTI ratio");
            }
            if (ltv > 0.80) {
                conditionsToAdd.add("Private Mortgage Insurance (PMI) required");
            }
            if (representativeScore < Constants.MIN_CREDIT_SCORE_CONVENTIONAL) {
                conditionsToAdd.add("Additional credit documentation required — explain derogatory items");
            }

            // Anti-pattern: asset verification check inline
            List<BorrowerAsset> assets = borrowerAssetRepository.findByLoanApplicationId(loanId);
            BigDecimal totalAssets = assets.stream()
                    .map(BorrowerAsset::getBalance)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (loan.getDownPayment() != null && totalAssets.compareTo(loan.getDownPayment().multiply(BigDecimal.valueOf(1.5))) < 0) {
                conditionsToAdd.add("Verify sufficient reserves — assets appear low relative to down payment");
            }

            // Anti-pattern: employment verification check inline
            List<BorrowerEmployment> employments = borrowerEmploymentRepository.findByLoanApplicationId(loanId);
            boolean hasUnverifiedEmployment = employments.stream()
                    .anyMatch(e -> !"VERIFIED".equals(e.getVerificationStatus()));
            if (hasUnverifiedEmployment) {
                conditionsToAdd.add("Complete employment verification for all employers");
            }
        } else if (riskScore >= 40) {
            decision = Constants.UW_SUSPENDED;
            notes = "Suspended for manual review. Risk score: " + riskScore +
                    " — requires senior underwriter approval";
        } else {
            decision = Constants.UW_DENIED;
            notes = "Auto-denied. Risk score: " + riskScore + " below minimum threshold";
        }

        // Create the decision
        UnderwritingDecision uwDecision = new UnderwritingDecision();
        uwDecision.setLoanApplication(loan);
        uwDecision.setDecision(decision);
        uwDecision.setNotes(notes);
        uwDecision.setDtiRatio(BigDecimal.valueOf(dti));
        uwDecision.setLtvRatio(BigDecimal.valueOf(ltv));
        uwDecision.setRiskScore(BigDecimal.valueOf(riskScore));
        uwDecision.setDecisionDate(new java.sql.Timestamp(System.currentTimeMillis()));

        // Anti-pattern: re-use createDecision which also modifies loan status
        UnderwritingDecision saved = createDecision(loanId, uwDecision);

        // Add conditions for conditional approvals
        for (String conditionDesc : conditionsToAdd) {
            addCondition(saved.getId(), "PRIOR_TO_DOCS", conditionDesc);
        }

        log.info("Auto-underwrite complete for loan {} — Decision: {}, Risk Score: {}, Credit: {}, DTI: {:.2f}, LTV: {:.2f}",
                loanId, decision, riskScore, representativeScore, dti, ltv);

        return saved;
    }

    @Transactional
    public UnderwritingCondition addCondition(UUID decisionId, String conditionType, String description) {
        UnderwritingDecision decision = underwritingDecisionRepository.findById(decisionId).orElse(null);
        if (decision == null) {
            throw new RuntimeException("Underwriting decision not found: " + decisionId);
        }

        UnderwritingCondition condition = new UnderwritingCondition();
        condition.setDecision(decision);
        condition.setConditionType(conditionType != null ? conditionType : "PRIOR_TO_DOCS");
        condition.setDescription(description);
        condition.setStatus("PENDING");

        UnderwritingCondition saved = underwritingConditionRepository.save(condition);

        logAudit(null, "CREATE", "UnderwritingCondition", saved.getId(), null,
                "Type: " + conditionType + ", Description: " + description);

        return saved;
    }

    @Transactional
    public UnderwritingCondition satisfyCondition(UUID conditionId, UUID documentId) {
        UnderwritingCondition condition = underwritingConditionRepository.findById(conditionId).orElse(null);
        if (condition == null) {
            throw new RuntimeException("Underwriting condition not found: " + conditionId);
        }

        if ("SATISFIED".equals(condition.getStatus())) {
            log.warn("Condition {} is already satisfied — ignoring", conditionId);
            return condition;
        }

        condition.setStatus("SATISFIED");
        condition.setSatisfiedDate(LocalDate.now());
        if (documentId != null) {
            condition.setDocumentId(documentId);
        }

        UnderwritingCondition saved = underwritingConditionRepository.save(condition);

        logAudit(null, "UPDATE", "UnderwritingCondition", conditionId,
                "status:PENDING", "status:SATISFIED");

        // Anti-pattern: check if ALL conditions for this decision are now satisfied
        UUID decisionId = condition.getDecision().getId();
        List<UnderwritingCondition> allConditions = underwritingConditionRepository.findByDecisionId(decisionId);
        boolean allSatisfied = allConditions.stream()
                .allMatch(c -> "SATISFIED".equals(c.getStatus()) || "WAIVED".equals(c.getStatus()));

        if (allSatisfied) {
            log.info("All conditions satisfied for decision {} — loan may proceed", decisionId);
            // Anti-pattern: modifies loan status directly, bypassing LoanService
            UnderwritingDecision decision = condition.getDecision();
            if (decision.getLoanApplication() != null) {
                LoanApplication loan = decision.getLoanApplication();
                if (Constants.LOAN_STATUS_CONDITIONALLY_APPROVED.equals(loan.getStatus())) {
                    loan.setStatus(Constants.LOAN_STATUS_APPROVED);
                    loanApplicationRepository.save(loan);
                    notificationHelper.notifyLoanStatusChange(loan.getId(),
                            Constants.LOAN_STATUS_CONDITIONALLY_APPROVED, Constants.LOAN_STATUS_APPROVED);
                    logAudit(null, "STATUS_CHANGE", "LoanApplication", loan.getId(),
                            "status:CONDITIONALLY_APPROVED", "status:APPROVED");
                }
            }
        }

        return saved;
    }

    public List<UnderwritingDecision> getDecisionsByLoan(UUID loanId) {
        return underwritingDecisionRepository.findByLoanApplicationId(loanId);
    }

    // ==================== APPRAISAL ====================

    @Transactional
    public AppraisalOrder createAppraisalOrder(UUID loanId, AppraisalOrder order) {
        LoanApplication loan = loanApplicationRepository.findById(loanId).orElse(null);
        if (loan == null) {
            throw new RuntimeException("Loan application not found: " + loanId);
        }

        order.setLoanApplication(loan);

        if (loan.getProperty() != null) {
            order.setProperty(loan.getProperty());
        } else {
            throw new RuntimeException("Loan application has no property — cannot order appraisal");
        }

        if (order.getOrderDate() == null) {
            order.setOrderDate(LocalDate.now());
        }
        if (order.getDueDate() == null) {
            order.setDueDate(LocalDate.now().plusDays(14)); // Anti-pattern: magic number
        }
        if (order.getStatus() == null) {
            order.setStatus("ORDERED");
        }
        if (order.getFee() == null) {
            order.setFee(BigDecimal.valueOf(450.00)); // Anti-pattern: hardcoded appraisal fee
        }

        AppraisalOrder saved = appraisalOrderRepository.save(order);

        logAudit(null, "CREATE", "AppraisalOrder", saved.getId(), null,
                "Loan: " + loanId + ", Property: " + loan.getProperty().getAddressLine1());

        return saved;
    }

    @Transactional
    public AppraisalReport submitAppraisalReport(UUID orderId, AppraisalReport report) {
        AppraisalOrder order = appraisalOrderRepository.findById(orderId).orElse(null);
        if (order == null) {
            throw new RuntimeException("Appraisal order not found: " + orderId);
        }

        report.setAppraisalOrder(order);
        if (report.getReportDate() == null) {
            report.setReportDate(LocalDate.now());
        }

        // Anti-pattern: update order status inline
        order.setStatus("COMPLETED");
        order.setCompletedDate(LocalDate.now());
        appraisalOrderRepository.save(order);

        AppraisalReport saved = appraisalReportRepository.save(report);

        // Anti-pattern: inline LTV check based on appraised value
        if (saved.getAppraisedValue() != null && order.getLoanApplication() != null) {
            LoanApplication loan = order.getLoanApplication();
            if (loan.getLoanAmount() != null) {
                BigDecimal appraisedLTV = loan.getLoanAmount().divide(
                        saved.getAppraisedValue(), 4, RoundingMode.HALF_UP);
                if (appraisedLTV.doubleValue() > 1.0) {
                    log.warn("APPRAISAL GAP: Loan {} — loan amount ${} exceeds appraised value ${}",
                            loan.getId(), loan.getLoanAmount(), saved.getAppraisedValue());
                    // Anti-pattern: notifies but doesn't block or create a condition
                    if (loan.getLoanOfficer() != null) {
                        notificationHelper.notifyAgent(loan.getLoanOfficer().getId(),
                                "APPRAISAL GAP ALERT: Property appraised at $" + saved.getAppraisedValue() +
                                        " but loan amount is $" + loan.getLoanAmount());
                    }
                }
            }
        }

        logAudit(null, "CREATE", "AppraisalReport", saved.getId(), null,
                "Appraised Value: $" + saved.getAppraisedValue() + ", Order: " + orderId);

        return saved;
    }

    @Transactional
    public ComparableSale addComparableSale(UUID reportId, ComparableSale comp) {
        AppraisalReport report = appraisalReportRepository.findById(reportId).orElse(null);
        if (report == null) {
            throw new RuntimeException("Appraisal report not found: " + reportId);
        }

        comp.setAppraisalReport(report);

        // Anti-pattern: inline adjusted price calculation
        if (comp.getAdjustedPrice() == null && comp.getSalePrice() != null && comp.getAdjustments() != null) {
            // Try to parse adjustments from the text blob — anti-pattern: parsing unstructured data
            try {
                BigDecimal adjustment = BigDecimal.ZERO;
                String[] parts = comp.getAdjustments().split(",");
                for (String part : parts) {
                    String[] kv = part.split(":");
                    if (kv.length == 2) {
                        String value = kv[1].trim().replaceAll("[^\\d.-]", "");
                        if (!value.isEmpty()) {
                            adjustment = adjustment.add(new BigDecimal(value));
                        }
                    }
                }
                comp.setAdjustedPrice(comp.getSalePrice().add(adjustment));
            } catch (Exception e) {
                log.error("Failed to parse adjustments for comparable sale: {}", e.getMessage());
                comp.setAdjustedPrice(comp.getSalePrice()); // Anti-pattern: fallback to unadjusted
            }
        }

        return comparableSaleRepository.save(comp);
    }

    public List<AppraisalOrder> getAppraisalByLoan(UUID loanId) {
        return appraisalOrderRepository.findByLoanApplicationId(loanId);
    }

    // ==================== RISK ASSESSMENT ====================

    /**
     * Calculate a risk score for a loan application.
     * Anti-pattern: 100+ lines of nested if/else with hardcoded thresholds.
     * This should be a proper rules engine or at minimum a strategy pattern.
     */
    public int calculateRiskScore(LoanApplication loan, int creditScore, double dti,
                                   double ltv, List<CreditReport> creditReports) {
        int score = 50; // Base score of 50/100

        // === CREDIT SCORE COMPONENT (max +/- 25 points) ===
        if (creditScore >= 800) {
            score += 25;
        } else if (creditScore >= 760) {
            score += 20;
        } else if (creditScore >= 740) {
            score += 15;
        } else if (creditScore >= 720) {
            score += 12;
        } else if (creditScore >= 700) {
            score += 8;
        } else if (creditScore >= 680) {
            score += 5;
        } else if (creditScore >= 660) {
            score += 2;
        } else if (creditScore >= 640) {
            score -= 2;
        } else if (creditScore >= 620) {
            score -= 5;
        } else if (creditScore >= 600) {
            score -= 10;
        } else if (creditScore >= 580) {
            score -= 15;
        } else {
            score -= 25;
        }

        // === DTI COMPONENT (max +/- 15 points) ===
        if (dti <= 0.28) {
            score += 15;
        } else if (dti <= 0.33) {
            score += 10;
        } else if (dti <= 0.36) {
            score += 5;
        } else if (dti <= 0.43) {
            score += 0; // neutral
        } else if (dti <= 0.50) {
            score -= 10;
        } else {
            score -= 15;
        }

        // === LTV COMPONENT (max +/- 15 points) ===
        if (ltv <= 0.60) {
            score += 15;
        } else if (ltv <= 0.70) {
            score += 10;
        } else if (ltv <= 0.80) {
            score += 5;
        } else if (ltv <= 0.90) {
            score -= 2;
        } else if (ltv <= 0.95) {
            score -= 8;
        } else if (ltv <= 0.97) {
            score -= 12;
        } else {
            score -= 15;
        }

        // === LOAN TYPE COMPONENT (+/- 5 points) ===
        if (loan.getLoanType() != null) {
            if (Constants.LOAN_TYPE_CONVENTIONAL.equals(loan.getLoanType())) {
                score += 3;
            } else if (Constants.LOAN_TYPE_FHA.equals(loan.getLoanType())) {
                score += 0; // neutral
            } else if (Constants.LOAN_TYPE_VA.equals(loan.getLoanType())) {
                score += 5; // Anti-pattern: subjective weighting hardcoded
            } else if (Constants.LOAN_TYPE_JUMBO.equals(loan.getLoanType())) {
                score -= 5; // Anti-pattern: penalizing jumbo loans arbitrarily
            }
        }

        // === EMPLOYMENT STABILITY (max +/- 10 points) ===
        List<BorrowerEmployment> employments = borrowerEmploymentRepository
                .findByLoanApplicationId(loan.getId());
        if (!employments.isEmpty()) {
            boolean hasCurrentJob = employments.stream()
                    .anyMatch(e -> e.getIsCurrent() != null && e.getIsCurrent());
            if (hasCurrentJob) {
                score += 5;
                // Anti-pattern: bonus for job tenure — checking start date inline
                BorrowerEmployment current = employments.stream()
                        .filter(e -> e.getIsCurrent() != null && e.getIsCurrent())
                        .findFirst().orElse(null);
                if (current != null && current.getStartDate() != null) {
                    long yearsAtJob = java.time.temporal.ChronoUnit.YEARS
                            .between(current.getStartDate(), LocalDate.now());
                    if (yearsAtJob >= 5) {
                        score += 5;
                    } else if (yearsAtJob >= 2) {
                        score += 3;
                    } else if (yearsAtJob < 1) {
                        score -= 3;
                    }
                }
            } else {
                score -= 10; // Anti-pattern: no current employment is a big penalty
            }
        } else {
            score -= 10;
        }

        // === ASSET RESERVES (max +/- 5 points) ===
        List<BorrowerAsset> assets = borrowerAssetRepository.findByLoanApplicationId(loan.getId());
        BigDecimal totalAssets = assets.stream()
                .map(BorrowerAsset::getBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (loan.getMonthlyPayment() != null && loan.getMonthlyPayment().compareTo(BigDecimal.ZERO) > 0) {
            double monthsOfReserves = totalAssets.doubleValue() / loan.getMonthlyPayment().doubleValue();
            if (monthsOfReserves >= 12) {
                score += 5;
            } else if (monthsOfReserves >= 6) {
                score += 3;
            } else if (monthsOfReserves >= 3) {
                score += 1;
            } else if (monthsOfReserves < 2) {
                score -= 5;
            }
        }

        // === CREDIT REPORT DEPTH (max +/- 5 points) ===
        if (creditReports.size() >= 3) {
            score += 3; // All three bureaus pulled
        } else if (creditReports.size() == 1) {
            score -= 2; // Only one bureau — incomplete picture
        }

        // Anti-pattern: raw SQL via EntityManager for additional risk factors
        try {
            @SuppressWarnings("unchecked")
            Query nativeQuery = entityManager.createNativeQuery(
                    "SELECT COUNT(*) as loan_count, " +
                            "COALESCE(SUM(CASE WHEN la.status = 'DENIED' THEN 1 ELSE 0 END), 0) as denied_count " +
                            "FROM loan_applications la " +
                            "WHERE la.borrower_id = :borrowerId"
            );
            nativeQuery.setParameter("borrowerId", loan.getBorrower().getId());
            Object[] result = (Object[]) nativeQuery.getSingleResult();
            int previousLoans = ((Number) result[0]).intValue();
            int previousDenials = ((Number) result[1]).intValue();

            if (previousDenials > 0) {
                score -= (previousDenials * 3); // Anti-pattern: hardcoded penalty per denial
                log.info("Borrower has {} previous denials — penalty applied", previousDenials);
            }
            if (previousLoans > 3) {
                score -= 2; // Anti-pattern: penalizing multiple loan applications
            }
        } catch (Exception e) {
            log.error("Failed to query borrower loan history for risk score: {}", e.getMessage());
            // Anti-pattern: swallow the error and continue with partial risk score
        }

        // Clamp score to 0-100
        score = Math.max(0, Math.min(100, score));

        log.info("Risk score for loan {}: {} (credit={}, dti={:.2f}, ltv={:.2f})",
                loan.getId(), score, creditScore, dti, ltv);

        return score;
    }

    // ==================== HELPER ====================

    /**
     * Anti-pattern: THIRD copy of the logAudit helper method — now in three service classes.
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
