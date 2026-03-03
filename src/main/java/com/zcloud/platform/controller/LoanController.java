package com.zcloud.platform.controller;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.*;
import com.zcloud.platform.repository.BorrowerAssetRepository;
import com.zcloud.platform.repository.BorrowerEmploymentRepository;
import com.zcloud.platform.repository.CreditReportRepository;
import com.zcloud.platform.repository.LoanApplicationRepository;
import com.zcloud.platform.service.LoanService;
import com.zcloud.platform.service.MasterService;

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
 * LoanController -- handles mortgage loan applications and related sub-resources.
 *
 * Anti-patterns:
 * - Status transition validation is a giant switch statement in the controller
 * - Credit pull creates 3 records (one per bureau) inline
 * - Monthly payment calculation duplicated here (already in LoanService and entity)
 * - GET /{id} manually assembles response by querying 5 repos separately
 * - Inconsistent error handling
 * - Business logic scattered between controller and LoanService
 */
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private static final Logger log = LoggerFactory.getLogger(LoanController.class);

    @Autowired
    private LoanService loanService;

    // Anti-pattern: injects repositories for direct access, bypassing LoanService
    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerEmploymentRepository borrowerEmploymentRepository;

    @Autowired
    private BorrowerAssetRepository borrowerAssetRepository;

    @Autowired
    private CreditReportRepository creditReportRepository;

    // Anti-pattern: also injects MasterService — cross-service dependency in controller
    @Autowired
    private MasterService masterService;

    // ==================== LIST ====================

    /**
     * List loan applications with optional filters.
     * Anti-pattern: conditional repo calls, no pagination.
     */
    @GetMapping
    public ResponseEntity<?> listLoans(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID loanOfficerId,
            @RequestParam(required = false) UUID borrowerId) {

        List<LoanApplication> loans;

        // Anti-pattern: cascading if/else for filter combinations
        if (status != null && loanOfficerId != null) {
            // Anti-pattern: no combined query — load by officer then filter by status in memory
            loans = loanApplicationRepository.findByLoanOfficerId(loanOfficerId);
            loans = loans.stream()
                    .filter(l -> status.equals(l.getStatus()))
                    .collect(Collectors.toList());
        } else if (status != null) {
            loans = loanApplicationRepository.findByStatus(status);
        } else if (loanOfficerId != null) {
            loans = loanApplicationRepository.findByLoanOfficerId(loanOfficerId);
        } else if (borrowerId != null) {
            loans = loanApplicationRepository.findByBorrowerId(borrowerId);
        } else {
            // Anti-pattern: loads ALL loan applications — unbounded
            loans = loanApplicationRepository.findAll();
        }

        return ResponseEntity.ok(loans);
    }

    // ==================== GET BY ID ====================

    /**
     * Get loan application with all related details.
     * Anti-pattern: manually assembles response by querying 5 repositories separately
     * instead of using JPA relationships or a single service method.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getLoan(@PathVariable UUID id) {
        // Anti-pattern: uses service for loan but repos for related data
        LoanApplication loan = loanService.getLoanApplication(id);
        if (loan == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Loan application not found", "id", id.toString()));
        }

        // Anti-pattern: manually assembling a response from 5 different repos
        // This is a classic "BFF in the controller" anti-pattern
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loanApplication", loan);

        // Query 1: employment records
        List<BorrowerEmployment> employments = borrowerEmploymentRepository.findByLoanApplicationId(id);
        response.put("employmentRecords", employments);

        // Query 2: asset records
        List<BorrowerAsset> assets = borrowerAssetRepository.findByLoanApplicationId(id);
        response.put("assets", assets);

        // Query 3: credit reports
        List<CreditReport> creditReports = creditReportRepository.findByLoanApplicationId(id);
        response.put("creditReports", creditReports);

        // Anti-pattern: inline DTI calculation (duplicated from LoanService.calculateDTI)
        BigDecimal totalMonthlyIncome = employments.stream()
                .filter(e -> e.getIsCurrent() != null && e.getIsCurrent())
                .map(BorrowerEmployment::getMonthlyIncome)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.put("totalMonthlyIncome", totalMonthlyIncome);

        if (totalMonthlyIncome.compareTo(BigDecimal.ZERO) > 0 && loan.getMonthlyPayment() != null) {
            double dti = loan.getMonthlyPayment().doubleValue() / totalMonthlyIncome.doubleValue();
            response.put("dtiRatio", Math.round(dti * 10000.0) / 10000.0); // Anti-pattern: rounding hack
            response.put("dtiStatus", dti <= Constants.MAX_DTI_RATIO ? "ACCEPTABLE" : "HIGH");
        }

        // Anti-pattern: inline LTV calculation (also done in UnderwritingService)
        if (loan.getLoanAmount() != null && loan.getDownPayment() != null) {
            BigDecimal totalPrice = loan.getLoanAmount().add(loan.getDownPayment());
            if (totalPrice.compareTo(BigDecimal.ZERO) > 0) {
                double ltv = loan.getLoanAmount().doubleValue() / totalPrice.doubleValue();
                response.put("ltvRatio", Math.round(ltv * 10000.0) / 10000.0);
                response.put("pmiRequired", ltv > 0.80);
            }
        }

        // Anti-pattern: inline total assets calculation
        BigDecimal totalAssets = assets.stream()
                .map(BorrowerAsset::getBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.put("totalAssets", totalAssets);

        // Anti-pattern: credit score summary calculated inline
        if (!creditReports.isEmpty()) {
            int avgScore = (int) creditReports.stream()
                    .filter(cr -> cr.getScore() != null)
                    .mapToInt(CreditReport::getScore)
                    .average()
                    .orElse(0);
            int minScore = creditReports.stream()
                    .filter(cr -> cr.getScore() != null)
                    .mapToInt(CreditReport::getScore)
                    .min()
                    .orElse(0);
            int maxScore = creditReports.stream()
                    .filter(cr -> cr.getScore() != null)
                    .mapToInt(CreditReport::getScore)
                    .max()
                    .orElse(0);
            response.put("creditScoreSummary", Map.of(
                    "average", avgScore,
                    "min", minScore,
                    "max", maxScore,
                    "bureausReported", creditReports.size()
            ));
        }

        return ResponseEntity.ok(response);
    }

    // ==================== CREATE ====================

    /**
     * Create a loan application.
     * Anti-pattern: uses LoanService for create but repos for reads.
     */
    @PostMapping
    public ResponseEntity<?> createLoan(@RequestBody LoanApplication application) {
        try {
            LoanApplication saved = loanService.createLoanApplication(application);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (RuntimeException e) {
            log.error("Error creating loan application: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== UPDATE ====================

    /**
     * Update a loan application.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateLoan(@PathVariable UUID id, @RequestBody LoanApplication updates) {
        try {
            LoanApplication updated = loanService.updateLoanApplication(id, updates);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== STATUS CHANGE ====================

    /**
     * Change loan application status with inline validation of valid transitions.
     *
     * Anti-pattern: the entire status transition validation is a giant switch statement
     * in the controller. This should be a state machine in the service layer.
     * Each case calls a different LoanService method, but some transitions are handled
     * entirely inline because the service doesn't support them.
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> changeLoanStatus(@PathVariable UUID id,
                                               @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        if (newStatus == null || newStatus.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Status is required"));
        }

        LoanApplication loan = loanService.getLoanApplication(id);
        if (loan == null) {
            return ResponseEntity.notFound().build();
        }

        String currentStatus = loan.getStatus();

        // Anti-pattern: giant switch statement for status transitions
        // This is a state machine implemented as a switch — fragile and hard to maintain
        try {
            LoanApplication result;

            switch (newStatus.toUpperCase()) {
                case "PROCESSING":
                    // Anti-pattern: inline validation of valid source statuses
                    if (!"DRAFT".equals(currentStatus) && !"STARTED".equals(currentStatus)) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Can only move to PROCESSING from DRAFT or STARTED",
                                "currentStatus", currentStatus));
                    }
                    result = loanService.submitForProcessing(id);
                    break;

                case "UNDERWRITING":
                    if (!Constants.LOAN_STATUS_PROCESSING.equals(currentStatus)) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Can only move to UNDERWRITING from PROCESSING",
                                "currentStatus", currentStatus));
                    }
                    result = loanService.submitForUnderwriting(id);
                    break;

                case "CLOSING":
                    if (!Constants.LOAN_STATUS_APPROVED.equals(currentStatus) &&
                            !"CONDITIONALLY_APPROVED".equals(currentStatus)) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Can only move to CLOSING from APPROVED or CONDITIONALLY_APPROVED",
                                "currentStatus", currentStatus));
                    }
                    result = loanService.moveToClosing(id);
                    break;

                case "DENIED":
                    // Anti-pattern: denial handled inline — LoanService has no deny method
                    // This modifies the entity directly in the controller
                    if ("FUNDED".equals(currentStatus) || "DENIED".equals(currentStatus)) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Cannot deny a " + currentStatus + " loan"));
                    }
                    loan.setStatus(Constants.LOAN_STATUS_DENIED);
                    result = loanApplicationRepository.save(loan);
                    log.info("Loan {} DENIED (status change done in controller)", id);
                    break;

                case "WITHDRAWN":
                    // Anti-pattern: withdrawal also handled inline
                    if ("FUNDED".equals(currentStatus)) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "Cannot withdraw a funded loan"));
                    }
                    loan.setStatus("WITHDRAWN");
                    result = loanApplicationRepository.save(loan);
                    log.info("Loan {} WITHDRAWN (status change done in controller)", id);
                    break;

                case "SUSPENDED":
                    // Anti-pattern: suspension handled inline
                    loan.setStatus(Constants.LOAN_STATUS_SUSPENDED);
                    result = loanApplicationRepository.save(loan);
                    log.info("Loan {} SUSPENDED (status change done in controller)", id);
                    break;

                default:
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Invalid status transition",
                            "requestedStatus", newStatus,
                            "validStatuses", Arrays.asList("PROCESSING", "UNDERWRITING", "CLOSING",
                                    "DENIED", "WITHDRAWN", "SUSPENDED")));
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("loan", result);
            response.put("previousStatus", currentStatus);
            response.put("newStatus", result.getStatus());
            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("Error changing loan {} status to {}: {}", id, newStatus, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== EMPLOYMENT ====================

    /**
     * Add borrower employment record.
     * Anti-pattern: uses LoanService but then does additional processing inline.
     */
    @PostMapping("/{id}/employment")
    public ResponseEntity<?> addEmployment(@PathVariable UUID id,
                                            @RequestBody BorrowerEmployment employment) {
        try {
            BorrowerEmployment saved = loanService.addEmployment(id, employment);

            // Anti-pattern: after saving via service, recalculate DTI here in the controller
            // because "the front-end needs the updated DTI in the response"
            double dti = loanService.calculateDTI(id);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("employment", saved);
            response.put("updatedDTI", Math.round(dti * 10000.0) / 10000.0);
            response.put("dtiAcceptable", dti <= Constants.MAX_DTI_RATIO);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== ASSETS ====================

    /**
     * Add borrower asset.
     * Anti-pattern: uses LoanService but adds inline response enrichment.
     */
    @PostMapping("/{id}/assets")
    public ResponseEntity<?> addAsset(@PathVariable UUID id,
                                       @RequestBody BorrowerAsset asset) {
        try {
            BorrowerAsset saved = loanService.addAsset(id, asset);

            // Anti-pattern: inline calculation of total assets for the response
            List<BorrowerAsset> allAssets = borrowerAssetRepository.findByLoanApplicationId(id);
            BigDecimal totalAssets = allAssets.stream()
                    .map(BorrowerAsset::getBalance)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("asset", saved);
            response.put("totalAssets", totalAssets);
            response.put("assetCount", allAssets.size());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== CREDIT PULL ====================

    /**
     * Pull credit report — generates fake scores for all 3 bureaus.
     *
     * Anti-pattern: creates 3 records (one per bureau) inline in the controller
     * by calling LoanService.pullCreditReport 3 times in a loop.
     * Monthly payment calculation duplicated here.
     */
    @PostMapping("/{id}/credit-pull")
    public ResponseEntity<?> pullCredit(@PathVariable UUID id) {
        LoanApplication loan = loanService.getLoanApplication(id);
        if (loan == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Loan application not found"); // Anti-pattern: plain string error
        }

        // Anti-pattern: check if credit was already pulled — but only warns, doesn't block
        List<CreditReport> existingReports = creditReportRepository.findByLoanApplicationId(id);
        if (!existingReports.isEmpty()) {
            log.warn("Credit already pulled for loan {} ({} reports exist) — pulling AGAIN",
                    id, existingReports.size());
            // Anti-pattern: doesn't block re-pulls — creates duplicate reports
        }

        // Anti-pattern: creates 3 credit reports (one per bureau) inline in the controller
        // LoanService.pullCreditReport takes a single bureau, so we call it 3 times
        List<CreditReport> reports = new ArrayList<>();
        String[] bureaus = {"EQUIFAX", "EXPERIAN", "TRANSUNION"};

        for (String bureau : bureaus) {
            try {
                CreditReport report = loanService.pullCreditReport(id, bureau);
                reports.add(report);
                log.info("Credit pull from {} for loan {}: score = {}", bureau, id, report.getScore());
            } catch (Exception e) {
                // Anti-pattern: if one bureau fails, continue with the others
                // This means partial data — some bureaus pulled, others not
                log.error("Failed to pull credit from {} for loan {}: {}", bureau, id, e.getMessage());
            }
        }

        if (reports.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to pull credit from any bureau"));
        }

        // Anti-pattern: monthly payment calculation DUPLICATED here — already in LoanService
        // and in the LoanApplication entity @PostLoad
        BigDecimal monthlyPayment = null;
        if (loan.getInterestRate() != null && loan.getLoanTermMonths() != null
                && loan.getLoanTermMonths() > 0 && loan.getLoanAmount() != null) {
            double monthlyRate = loan.getInterestRate().doubleValue() / 100.0 / 12.0;
            int n = loan.getLoanTermMonths();
            double loanAmt = loan.getLoanAmount().doubleValue();
            double payment = loanAmt * (monthlyRate * Math.pow(1 + monthlyRate, n))
                    / (Math.pow(1 + monthlyRate, n) - 1);
            monthlyPayment = BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP);
        }

        // Anti-pattern: build response with inline analytics
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loanId", id);
        response.put("creditReports", reports);
        response.put("bureausPulled", reports.size());

        // Calculate representative score (middle of sorted scores)
        List<Integer> scores = reports.stream()
                .filter(r -> r.getScore() != null)
                .map(CreditReport::getScore)
                .sorted()
                .collect(Collectors.toList());
        if (!scores.isEmpty()) {
            int representativeScore = scores.get(scores.size() / 2);
            response.put("representativeScore", representativeScore);
            response.put("allScores", scores);
            response.put("meetsConventionalMinimum", representativeScore >= Constants.MIN_CREDIT_SCORE_CONVENTIONAL);
            response.put("meetsFHAMinimum", representativeScore >= Constants.MIN_CREDIT_SCORE_FHA);
        }

        if (monthlyPayment != null) {
            response.put("estimatedMonthlyPayment", monthlyPayment);
        }

        return ResponseEntity.ok(response);
    }
}
