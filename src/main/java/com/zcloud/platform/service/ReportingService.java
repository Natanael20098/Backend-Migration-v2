package com.zcloud.platform.service;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.*;
import com.zcloud.platform.repository.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ReportingService — handles reports and analytics across the platform.
 *
 * Anti-patterns:
 * - Injects JdbcTemplate AND multiple repositories (mixed data access strategies)
 * - Raw SQL string concatenation for reports (SQL injection risk)
 * - Builds HTML report strings inline
 * - Returns Map<String, Object> for everything (no typed responses)
 * - 50-line SQL queries as string literals
 * - No pagination, no caching, no streaming for large datasets
 */
@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

    // Anti-pattern: injects BOTH JdbcTemplate AND JPA repositories — mixed data access
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private CommissionRepository commissionRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private ClosingDetailRepository closingDetailRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Autowired
    private ShowingRepository showingRepository;

    // ==================== REPORTS ====================

    /**
     * Generate a loan pipeline report for a given loan officer and date range.
     * Anti-pattern: raw SQL string concatenation — SQL injection risk if parameters weren't
     * parameterized. Returns Map<String, Object> instead of a typed report DTO.
     */
    public Map<String, Object> generateLoanPipelineReport(UUID loanOfficerId,
                                                            LocalDate startDate, LocalDate endDate) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportType", "LOAN_PIPELINE");
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("loanOfficerId", loanOfficerId);
        report.put("dateRange", startDate + " to " + endDate);

        // Anti-pattern: 50-line raw SQL query as a string literal
        String sql = "SELECT " +
                "la.status, " +
                "COUNT(*) as loan_count, " +
                "COALESCE(SUM(la.loan_amount), 0) as total_volume, " +
                "COALESCE(AVG(la.loan_amount), 0) as avg_loan_amount, " +
                "COALESCE(MIN(la.loan_amount), 0) as min_loan, " +
                "COALESCE(MAX(la.loan_amount), 0) as max_loan, " +
                "COALESCE(AVG(la.interest_rate), 0) as avg_rate, " +
                "COUNT(CASE WHEN la.loan_type = 'CONVENTIONAL' THEN 1 END) as conventional_count, " +
                "COUNT(CASE WHEN la.loan_type = 'FHA' THEN 1 END) as fha_count, " +
                "COUNT(CASE WHEN la.loan_type = 'VA' THEN 1 END) as va_count, " +
                "COUNT(CASE WHEN la.loan_type = 'JUMBO' THEN 1 END) as jumbo_count " +
                "FROM loan_applications la " +
                "WHERE la.loan_officer_id = ? " +
                "AND la.application_date BETWEEN ? AND ? " +
                "GROUP BY la.status " +
                "ORDER BY loan_count DESC";

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
                    loanOfficerId, startDate, endDate);
            report.put("statusBreakdown", rows);

            // Anti-pattern: compute totals in Java by iterating the result set
            int totalLoans = 0;
            BigDecimal totalVolume = BigDecimal.ZERO;
            for (Map<String, Object> row : rows) {
                totalLoans += ((Number) row.get("loan_count")).intValue();
                totalVolume = totalVolume.add(new BigDecimal(row.get("total_volume").toString()));
            }
            report.put("totalLoans", totalLoans);
            report.put("totalVolume", totalVolume);

        } catch (Exception e) {
            log.error("Failed to generate loan pipeline report: {}", e.getMessage());
            report.put("error", e.getMessage());
        }

        // Anti-pattern: also load individual loans into the report (could be thousands)
        List<LoanApplication> loans = loanApplicationRepository.findByLoanOfficerId(loanOfficerId);
        report.put("loanDetails", loans); // Anti-pattern: serializing full entity graphs

        return report;
    }

    /**
     * Generate a commission report for agents.
     * Anti-pattern: raw SQL with string concatenation for the WHERE clause,
     * builds an HTML report string inline.
     */
    public Map<String, Object> generateCommissionReport(UUID agentId,
                                                          LocalDate startDate, LocalDate endDate) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportType", "COMMISSION");
        report.put("generatedAt", LocalDateTime.now().toString());

        // Anti-pattern: building SQL with string concatenation — injection risk
        // The agentId filter is conditionally appended
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.agent_id, ");
        sql.append("a.first_name || ' ' || a.last_name as agent_name, ");
        sql.append("COUNT(*) as transaction_count, ");
        sql.append("SUM(c.amount) as total_commission, ");
        sql.append("AVG(c.amount) as avg_commission, ");
        sql.append("SUM(CASE WHEN c.status = 'PAID' THEN c.amount ELSE 0 END) as paid_amount, ");
        sql.append("SUM(CASE WHEN c.status = 'PENDING' THEN c.amount ELSE 0 END) as pending_amount, ");
        sql.append("COUNT(CASE WHEN c.type = 'LISTING' THEN 1 END) as listing_side_count, ");
        sql.append("COUNT(CASE WHEN c.type = 'BUYER' THEN 1 END) as buyer_side_count ");
        sql.append("FROM commissions c ");
        sql.append("INNER JOIN agents a ON c.agent_id = a.id ");
        sql.append("WHERE c.created_at BETWEEN '").append(startDate).append("' AND '").append(endDate).append("' ");
        // Anti-pattern: string concatenation of date values into SQL — SQL injection vulnerability
        if (agentId != null) {
            sql.append("AND c.agent_id = '").append(agentId).append("' ");
            // Anti-pattern: UUID directly concatenated into SQL string
        }
        sql.append("GROUP BY c.agent_id, a.first_name, a.last_name ");
        sql.append("ORDER BY total_commission DESC");

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString());
            report.put("commissionData", rows);

            // Anti-pattern: build an HTML report string inline
            StringBuilder html = new StringBuilder();
            html.append("<html><head><title>Commission Report</title>");
            html.append("<style>table{border-collapse:collapse;width:100%;}th,td{border:1px solid #ddd;padding:8px;text-align:left;}</style>");
            html.append("</head><body>");
            html.append("<h1>Commission Report: ").append(startDate).append(" to ").append(endDate).append("</h1>");
            html.append("<table><tr><th>Agent</th><th>Transactions</th><th>Total</th><th>Paid</th><th>Pending</th></tr>");

            for (Map<String, Object> row : rows) {
                html.append("<tr>");
                html.append("<td>").append(row.get("agent_name")).append("</td>");
                html.append("<td>").append(row.get("transaction_count")).append("</td>");
                html.append("<td>$").append(row.get("total_commission")).append("</td>");
                html.append("<td>$").append(row.get("paid_amount")).append("</td>");
                html.append("<td>$").append(row.get("pending_amount")).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");
            html.append("<p style='font-size:10px;color:gray;'>Generated: ").append(LocalDateTime.now()).append("</p>");
            html.append("</body></html>");

            report.put("htmlReport", html.toString());

        } catch (Exception e) {
            log.error("Failed to generate commission report: {}", e.getMessage());
            report.put("error", e.getMessage());
        }

        return report;
    }

    /**
     * Generate a market analysis report.
     * Anti-pattern: multiple raw SQL queries, loads entire entity collections, no pagination.
     */
    public Map<String, Object> generateMarketAnalysis(String city, String state) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportType", "MARKET_ANALYSIS");
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("city", city);
        report.put("state", state);

        // Anti-pattern: raw SQL for average prices by property type
        String priceSql = "SELECT p.property_type, " +
                "COUNT(*) as listing_count, " +
                "AVG(l.list_price) as avg_price, " +
                "MIN(l.list_price) as min_price, " +
                "MAX(l.list_price) as max_price, " +
                "AVG(p.sqft) as avg_sqft, " +
                "AVG(l.list_price / NULLIF(p.sqft, 0)) as price_per_sqft, " +
                "AVG(EXTRACT(DAY FROM (CURRENT_DATE - l.listed_date))) as avg_days_on_market " +
                "FROM listings l " +
                "INNER JOIN properties p ON l.property_id = p.id " +
                "WHERE l.status = 'ACTIVE' " +
                "AND p.city = ? AND p.state = ? " +
                "GROUP BY p.property_type " +
                "ORDER BY listing_count DESC";

        try {
            List<Map<String, Object>> priceData = jdbcTemplate.queryForList(priceSql, city, state);
            report.put("priceAnalysis", priceData);
        } catch (Exception e) {
            log.error("Failed price analysis query: {}", e.getMessage());
            report.put("priceAnalysisError", e.getMessage());
        }

        // Anti-pattern: separate query for sold properties — could be combined
        String soldSql = "SELECT " +
                "COUNT(*) as sold_count, " +
                "AVG(l.list_price) as avg_sold_price, " +
                "AVG(EXTRACT(DAY FROM (l.updated_at - l.created_at))) as avg_days_to_sell " +
                "FROM listings l " +
                "INNER JOIN properties p ON l.property_id = p.id " +
                "WHERE l.status = 'SOLD' " +
                "AND p.city = ? AND p.state = ? " +
                "AND l.updated_at >= CURRENT_DATE - INTERVAL '90 days'";

        try {
            Map<String, Object> soldData = jdbcTemplate.queryForMap(soldSql, city, state);
            report.put("recentSales", soldData);
        } catch (Exception e) {
            log.error("Failed sold analysis query: {}", e.getMessage());
            report.put("recentSalesError", e.getMessage());
        }

        // Anti-pattern: load all active listings for this city — could be thousands
        List<Property> properties = propertyRepository.findByCity(city);
        report.put("totalProperties", properties.size());
        report.put("propertyDetails", properties); // Anti-pattern: full entity graph in report

        return report;
    }

    /**
     * Get audit logs with filtering.
     * Anti-pattern: loads all logs then filters in Java instead of using database queries.
     */
    public List<AuditLog> getAuditLogs(String resourceType, UUID resourceId,
                                         LocalDateTime startTime, LocalDateTime endTime) {
        // Anti-pattern: loads ALL audit logs then filters in memory
        List<AuditLog> allLogs = auditLogRepository.findAll();

        return allLogs.stream()
                .filter(log -> resourceType == null || resourceType.equals(log.getResourceType()))
                .filter(log -> resourceId == null || resourceId.toString().equals(log.getResourceId()))
                .filter(log -> startTime == null || log.getTimestamp() == null || !log.getTimestamp().toLocalDateTime().isBefore(startTime))
                .filter(log -> endTime == null || log.getTimestamp() == null || !log.getTimestamp().toLocalDateTime().isAfter(endTime))
                .sorted(Comparator.comparing(AuditLog::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // ==================== ANALYTICS ====================

    /**
     * Get listing statistics for a given time period.
     * Anti-pattern: multiple database calls, in-memory aggregation.
     */
    public Map<String, Object> getListingStatistics(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("period", startDate + " to " + endDate);

        // Anti-pattern: loads ALL listings then filters and groups in memory
        List<Listing> allListings = listingRepository.findAll();

        long activeCount = allListings.stream()
                .filter(l -> Constants.LISTING_ACTIVE.equals(l.getStatus()))
                .count();
        long pendingCount = allListings.stream()
                .filter(l -> Constants.LISTING_PENDING.equals(l.getStatus()))
                .count();
        long soldCount = allListings.stream()
                .filter(l -> Constants.LISTING_SOLD.equals(l.getStatus()))
                .count();

        stats.put("activeListings", activeCount);
        stats.put("pendingListings", pendingCount);
        stats.put("soldListings", soldCount);
        stats.put("totalListings", allListings.size());

        // Anti-pattern: computing averages in Java when SQL AVG would be far more efficient
        OptionalDouble avgPrice = allListings.stream()
                .filter(l -> l.getListPrice() != null && Constants.LISTING_ACTIVE.equals(l.getStatus()))
                .mapToDouble(l -> l.getListPrice().doubleValue())
                .average();
        stats.put("averageActiveListPrice", avgPrice.isPresent() ? avgPrice.getAsDouble() : 0.0);

        // Anti-pattern: price reduction analysis done in memory
        long priceReductionCount = allListings.stream()
                .filter(l -> l.getOriginalPrice() != null && l.getListPrice() != null)
                .filter(l -> l.getListPrice().compareTo(l.getOriginalPrice()) < 0)
                .count();
        stats.put("listingsWithPriceReduction", priceReductionCount);

        return stats;
    }

    /**
     * Get agent performance metrics.
     * Anti-pattern: N+1 queries — loads each agent then queries their related data.
     */
    public Map<String, Object> getAgentPerformance(UUID agentId, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> performance = new LinkedHashMap<>();

        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            performance.put("error", "Agent not found: " + agentId);
            return performance;
        }

        performance.put("agentId", agentId);
        performance.put("agentName", agent.getFullName());
        performance.put("brokerage", agent.getBrokerage() != null ? agent.getBrokerage().getName() : "Independent");

        // Anti-pattern: N+1 — separate queries for each metric
        List<Listing> agentListings = listingRepository.findByAgentId(agentId);
        performance.put("totalListings", agentListings.size());
        performance.put("activeListings", agentListings.stream()
                .filter(l -> Constants.LISTING_ACTIVE.equals(l.getStatus())).count());
        performance.put("soldListings", agentListings.stream()
                .filter(l -> Constants.LISTING_SOLD.equals(l.getStatus())).count());

        // Anti-pattern: another query
        List<Commission> commissions = commissionRepository.findByAgentId(agentId);
        BigDecimal totalCommission = commissions.stream()
                .filter(c -> "PAID".equals(c.getStatus()))
                .map(Commission::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        performance.put("totalCommissionEarned", totalCommission);
        performance.put("pendingCommissions", commissions.stream()
                .filter(c -> "PENDING".equals(c.getStatus()))
                .map(Commission::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        // Anti-pattern: yet another query
        List<Showing> showings = showingRepository.findByAgentId(agentId);
        performance.put("totalShowings", showings.size());
        performance.put("averageRating", showings.stream()
                .filter(s -> s.getRating() != null)
                .mapToInt(Showing::getRating)
                .average()
                .orElse(0.0));

        // Anti-pattern: compute sales volume via raw SQL because JPA doesn't aggregate well
        try {
            String volumeSql = "SELECT COALESCE(SUM(l.list_price), 0) as sales_volume " +
                    "FROM listings l WHERE l.agent_id = ? AND l.status = 'SOLD'";
            Map<String, Object> volumeResult = jdbcTemplate.queryForMap(volumeSql, agentId);
            performance.put("totalSalesVolume", volumeResult.get("sales_volume"));
        } catch (Exception e) {
            performance.put("totalSalesVolume", BigDecimal.ZERO);
        }

        return performance;
    }

    /**
     * Get monthly closing summary.
     * Anti-pattern: raw SQL with hardcoded date formatting, returns untyped Map.
     */
    public List<Map<String, Object>> getMonthlyClosings(int year) {
        String sql = "SELECT " +
                "EXTRACT(MONTH FROM cd.closing_date) as month, " +
                "COUNT(*) as closing_count, " +
                "COALESCE(SUM(cd.total_closing_costs), 0) as total_costs, " +
                "COALESCE(SUM(la.loan_amount), 0) as total_funded, " +
                "COALESCE(AVG(la.loan_amount), 0) as avg_loan_amount, " +
                "COALESCE(AVG(la.interest_rate), 0) as avg_rate " +
                "FROM closing_details cd " +
                "INNER JOIN loan_applications la ON cd.loan_application_id = la.id " +
                "WHERE cd.status = 'COMPLETED' " +
                "AND EXTRACT(YEAR FROM cd.closing_date) = ? " +
                "GROUP BY EXTRACT(MONTH FROM cd.closing_date) " +
                "ORDER BY month";

        try {
            List<Map<String, Object>> monthlyData = jdbcTemplate.queryForList(sql, year);

            // Anti-pattern: post-process to add month names and formatting
            for (Map<String, Object> row : monthlyData) {
                int month = ((Number) row.get("month")).intValue();
                row.put("monthName", java.time.Month.of(month).name());
                // Anti-pattern: formatting in the service layer
                if (row.get("total_funded") != null) {
                    row.put("totalFundedFormatted",
                            String.format("$%,.2f", ((Number) row.get("total_funded")).doubleValue()));
                }
            }

            return monthlyData;
        } catch (Exception e) {
            log.error("Failed to generate monthly closings report: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
