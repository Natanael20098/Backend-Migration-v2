package com.zcloud.platform.controller;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.*;
import com.zcloud.platform.repository.AppraisalOrderRepository;
import com.zcloud.platform.repository.AppraisalReportRepository;
import com.zcloud.platform.repository.ComparableSaleRepository;
import com.zcloud.platform.repository.UnderwritingDecisionRepository;
import com.zcloud.platform.service.LoanService;
import com.zcloud.platform.service.UnderwritingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * UnderwritingController -- handles underwriting decisions, conditions, and appraisals.
 *
 * Anti-patterns:
 * - Auto-underwrite endpoint calls service method then does additional processing in controller
 * - Appraisal report submission also creates comparable sales in a loop
 * - Condition satisfaction updates loan status directly
 * - Business logic scattered between controller and UnderwritingService
 * - No authorization checks (anyone can underwrite)
 */
@RestController
@RequestMapping("/api/underwriting")
public class UnderwritingController {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingController.class);

    @Autowired
    private UnderwritingService underwritingService;

    @Autowired
    private LoanService loanService;

    // Anti-pattern: injects repositories for direct access
    @Autowired
    private UnderwritingDecisionRepository underwritingDecisionRepository;

    @Autowired
    private AppraisalOrderRepository appraisalOrderRepository;

    @Autowired
    private AppraisalReportRepository appraisalReportRepository;

    @Autowired
    private ComparableSaleRepository comparableSaleRepository;

    // ==================== UNDERWRITING DECISION ====================

    /**
     * Create an underwriting decision for a loan.
     * Anti-pattern: uses service but wraps with additional controller logic.
     */
    @PostMapping("/loans/{loanId}/decision")
    public ResponseEntity<?> createDecision(@PathVariable UUID loanId,
                                             @RequestBody UnderwritingDecision decision) {
        try {
            // Anti-pattern: validate loan exists in controller (service also validates)
            LoanApplication loan = loanService.getLoanApplication(loanId);
            if (loan == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Loan application not found", "loanId", loanId.toString()));
            }

            // Anti-pattern: inline validation in controller
            if (decision.getDecision() == null || decision.getDecision().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Decision is required (APPROVED, DENIED, CONDITIONAL, SUSPENDED)"));
            }

            // Anti-pattern: validate decision value with hardcoded strings
            List<String> validDecisions = Arrays.asList(
                    Constants.UW_APPROVED, Constants.UW_DENIED,
                    Constants.UW_CONDITIONAL, Constants.UW_SUSPENDED
            );
            if (!validDecisions.contains(decision.getDecision())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid decision",
                        "validDecisions", validDecisions
                ));
            }

            UnderwritingDecision saved = underwritingService.createDecision(loanId, decision);

            // Anti-pattern: build response with extra data fetched after the save
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("decision", saved);
            response.put("loanStatus", loan.getStatus()); // stale — status changed in createDecision
            response.put("message", "Underwriting decision recorded. Loan status updated.");

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            log.error("Error creating underwriting decision for loan {}: {}", loanId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== AUTO-UNDERWRITE ====================

    /**
     * Run automatic underwriting engine on a loan application.
     *
     * Anti-pattern: calls service method then does ADDITIONAL processing in the controller.
     * The service already makes a decision, but the controller adds more logic on top,
     * creating confusion about where the "real" decision happens.
     */
    @PostMapping("/loans/{loanId}/auto-underwrite")
    public ResponseEntity<?> autoUnderwrite(@PathVariable UUID loanId) {
        try {
            LoanApplication loan = loanService.getLoanApplication(loanId);
            if (loan == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Loan application not found"));
            }

            // Anti-pattern: pre-check in controller that duplicates checks in the service
            if (!Constants.LOAN_STATUS_UNDERWRITING.equals(loan.getStatus()) &&
                    !Constants.LOAN_STATUS_PROCESSING.equals(loan.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Loan must be in UNDERWRITING or PROCESSING status for auto-underwrite",
                        "currentStatus", loan.getStatus()));
            }

            // Call service for the actual underwriting evaluation
            UnderwritingDecision decision = underwritingService.evaluateLoan(loanId);

            // Anti-pattern: ADDITIONAL processing in controller after service call
            // The service already made a decision, but now we second-guess it
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("decision", decision);
            response.put("loanId", loanId);

            // Anti-pattern: re-fetch loan to get updated status (service modified it)
            LoanApplication updatedLoan = loanService.getLoanApplication(loanId);
            response.put("updatedLoanStatus", updatedLoan != null ? updatedLoan.getStatus() : "UNKNOWN");

            // Anti-pattern: additional inline analysis that the service already did
            if (decision.getRiskScore() != null) {
                response.put("riskLevel", decision.getRiskScore().doubleValue() >= 80 ? "LOW" :
                        decision.getRiskScore().doubleValue() >= 60 ? "MODERATE" :
                                decision.getRiskScore().doubleValue() >= 40 ? "HIGH" : "VERY_HIGH");
            }
            if (decision.getDtiRatio() != null) {
                response.put("dtiStatus", decision.getDtiRatio().doubleValue() <= Constants.MAX_DTI_RATIO ?
                        "WITHIN_LIMITS" : "EXCEEDS_LIMITS");
            }
            if (decision.getLtvRatio() != null) {
                response.put("ltvStatus", decision.getLtvRatio().doubleValue() <= 0.80 ?
                        "GOOD_EQUITY" : "LOW_EQUITY");
                response.put("pmiRequired", decision.getLtvRatio().doubleValue() > 0.80);
            }

            // Anti-pattern: controller-level recommendation on top of service decision
            if (Constants.UW_CONDITIONAL.equals(decision.getDecision())) {
                response.put("recommendation",
                        "Review conditions before proceeding. All conditions must be satisfied prior to closing.");
            } else if (Constants.UW_DENIED.equals(decision.getDecision())) {
                response.put("recommendation",
                        "Consider alternative loan programs or address the issues noted in the decision.");
            } else if (Constants.UW_SUSPENDED.equals(decision.getDecision())) {
                response.put("recommendation",
                        "Route to senior underwriter for manual review. Risk score indicates further analysis needed.");
            }

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("Auto-underwrite failed for loan {}: {}", loanId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Auto-underwrite failed: " + e.getMessage()));
        }
    }

    // ==================== CONDITIONS ====================

    /**
     * Get underwriting conditions for a loan.
     * Anti-pattern: goes to repo directly, loads all decisions then all conditions.
     */
    @GetMapping("/loans/{loanId}/conditions")
    public ResponseEntity<?> getConditions(@PathVariable UUID loanId) {
        // Anti-pattern: loads ALL decisions for the loan, then loads conditions for EACH decision
        List<UnderwritingDecision> decisions = underwritingDecisionRepository.findByLoanApplicationId(loanId);

        if (decisions.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "loanId", loanId,
                    "conditions", Collections.emptyList(),
                    "message", "No underwriting decisions found for this loan"
            ));
        }

        // Anti-pattern: N+1 query — iterates decisions and queries conditions for each
        List<Map<String, Object>> allConditions = new ArrayList<>();
        for (UnderwritingDecision d : decisions) {
            List<UnderwritingCondition> conditions =
                    underwritingService.getDecisionsByLoan(loanId).isEmpty()
                            ? Collections.emptyList()
                            : new ArrayList<>(); // Anti-pattern: weird conditional that doesn't make sense

            // Anti-pattern: actually just use the repo directly
            List<UnderwritingCondition> actualConditions =
                    new ArrayList<>(); // Would need UnderwritingConditionRepository injected
            // but it's not — so we rely on what the service returns in the decision

            Map<String, Object> decisionWithConditions = new LinkedHashMap<>();
            decisionWithConditions.put("decisionId", d.getId());
            decisionWithConditions.put("decision", d.getDecision());
            decisionWithConditions.put("decisionDate", d.getDecisionDate());
            decisionWithConditions.put("notes", d.getNotes());
            decisionWithConditions.put("conditions", d.getConditions()); // This is the TEXT blob
            allConditions.add(decisionWithConditions);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loanId", loanId);
        response.put("decisions", allConditions);
        response.put("totalDecisions", decisions.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Mark an underwriting condition as satisfied.
     * Anti-pattern: calls service but also does additional loan status checking inline.
     */
    @PutMapping("/conditions/{id}/satisfy")
    public ResponseEntity<?> satisfyCondition(@PathVariable UUID id,
                                               @RequestBody(required = false) Map<String, String> body) {
        try {
            UUID documentId = null;
            if (body != null && body.containsKey("documentId")) {
                try {
                    documentId = UUID.fromString(body.get("documentId"));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Invalid document ID format"));
                }
            }

            UnderwritingCondition satisfied = underwritingService.satisfyCondition(id, documentId);

            // Anti-pattern: controller checks if all conditions are satisfied
            // (the service ALREADY does this — duplicated logic)
            UUID decisionId = satisfied.getDecision().getId();
            boolean allSatisfied = true; // Anti-pattern: assumes true then tries to disprove
            // Can't actually check here because UnderwritingConditionRepository is not injected
            // This is dead logic that was partially implemented and abandoned

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("condition", satisfied);
            response.put("status", satisfied.getStatus());
            response.put("satisfiedDate", satisfied.getSatisfiedDate());
            response.put("message", "Condition marked as satisfied");

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== APPRAISAL ====================

    /**
     * Order an appraisal for a loan.
     * Anti-pattern: uses service for create but then queries repo for response enrichment.
     */
    @PostMapping("/loans/{loanId}/appraisal")
    public ResponseEntity<?> orderAppraisal(@PathVariable UUID loanId,
                                             @RequestBody AppraisalOrder order) {
        try {
            AppraisalOrder saved = underwritingService.createAppraisalOrder(loanId, order);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("appraisalOrder", saved);
            response.put("estimatedCompletion", saved.getDueDate());
            response.put("fee", saved.getFee());
            response.put("message", "Appraisal ordered successfully");

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Submit an appraisal report with comparable sales.
     *
     * Anti-pattern: creates the appraisal report via service, then creates
     * comparable sales one-by-one in a loop directly in the controller.
     * The report and comps should be created atomically in a single service call.
     */
    @PostMapping("/appraisals/{appraisalId}/report")
    public ResponseEntity<?> submitAppraisalReport(
            @PathVariable UUID appraisalId,
            @RequestBody Map<String, Object> requestBody) {

        try {
            // Anti-pattern: manually extract report fields from a generic Map instead of
            // using a proper request DTO
            AppraisalReport report = new AppraisalReport();

            if (requestBody.containsKey("appraisedValue")) {
                report.setAppraisedValue(new BigDecimal(requestBody.get("appraisedValue").toString()));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Appraised value is required"));
            }

            if (requestBody.containsKey("approachUsed")) {
                report.setApproachUsed(requestBody.get("approachUsed").toString());
            }
            if (requestBody.containsKey("conditionRating")) {
                report.setConditionRating(requestBody.get("conditionRating").toString());
            }
            if (requestBody.containsKey("reportData")) {
                report.setReportData(requestBody.get("reportData").toString());
            }

            // Create the report via service
            AppraisalReport savedReport = underwritingService.submitAppraisalReport(appraisalId, report);

            // Anti-pattern: create comparable sales in a loop directly in the controller
            // This should be part of the service method or a separate endpoint
            List<ComparableSale> savedComps = new ArrayList<>();
            if (requestBody.containsKey("comparableSales")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> compsData =
                        (List<Map<String, Object>>) requestBody.get("comparableSales");

                for (Map<String, Object> compData : compsData) {
                    ComparableSale comp = new ComparableSale();
                    comp.setAppraisalReport(savedReport);

                    // Anti-pattern: manual field extraction from Map — fragile and no validation
                    if (compData.containsKey("address")) {
                        comp.setAddress(compData.get("address").toString());
                    }
                    if (compData.containsKey("salePrice")) {
                        comp.setSalePrice(new BigDecimal(compData.get("salePrice").toString()));
                    }
                    if (compData.containsKey("saleDate")) {
                        comp.setSaleDate(LocalDate.parse(compData.get("saleDate").toString()));
                    }
                    if (compData.containsKey("sqft")) {
                        comp.setSqft(Integer.parseInt(compData.get("sqft").toString()));
                    }
                    if (compData.containsKey("beds")) {
                        comp.setBeds(Integer.parseInt(compData.get("beds").toString()));
                    }
                    if (compData.containsKey("baths")) {
                        comp.setBaths(new BigDecimal(compData.get("baths").toString()));
                    }
                    if (compData.containsKey("distanceMiles")) {
                        comp.setDistanceMiles(new BigDecimal(compData.get("distanceMiles").toString()));
                    }
                    if (compData.containsKey("adjustments")) {
                        comp.setAdjustments(compData.get("adjustments").toString());
                    }
                    if (compData.containsKey("adjustedPrice")) {
                        comp.setAdjustedPrice(new BigDecimal(compData.get("adjustedPrice").toString()));
                    }

                    // Anti-pattern: saves each comp individually instead of batch
                    ComparableSale savedComp = comparableSaleRepository.save(comp);
                    savedComps.add(savedComp);
                    log.info("Created comparable sale {} for appraisal report {}", savedComp.getId(), savedReport.getId());
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("report", savedReport);
            response.put("comparableSales", savedComps);
            response.put("compCount", savedComps.size());

            // Anti-pattern: inline appraisal gap analysis in controller
            AppraisalOrder order = appraisalOrderRepository.findById(appraisalId).orElse(null);
            if (order != null && order.getLoanApplication() != null) {
                LoanApplication loan = order.getLoanApplication();
                if (loan.getLoanAmount() != null && savedReport.getAppraisedValue() != null) {
                    BigDecimal gap = savedReport.getAppraisedValue().subtract(loan.getLoanAmount());
                    response.put("appraisalGap", gap.compareTo(BigDecimal.ZERO) < 0 ? gap.abs() : BigDecimal.ZERO);
                    response.put("hasAppraisalGap", gap.compareTo(BigDecimal.ZERO) < 0);
                }
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (RuntimeException e) {
            log.error("Error submitting appraisal report for order {}: {}", appraisalId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
