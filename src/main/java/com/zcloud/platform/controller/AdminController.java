package com.zcloud.platform.controller;

import com.zcloud.platform.config.Constants;
import com.zcloud.platform.model.AuditLog;
import com.zcloud.platform.model.Notification;
import com.zcloud.platform.model.SystemSetting;
import com.zcloud.platform.repository.AuditLogRepository;
import com.zcloud.platform.repository.NotificationRepository;
import com.zcloud.platform.repository.SystemSettingRepository;
import com.zcloud.platform.service.CacheManager;
import com.zcloud.platform.service.ReportingService;
import com.zcloud.platform.util.SqlBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * AdminController -- handles system settings, notifications, audit logs, reports, and cache.
 *
 * Anti-patterns:
 * - Settings endpoint returns ALL settings including sensitive ones (JWT secret, default password)
 * - Audit log query uses SqlBuilder (SQL injection vulnerability)
 * - Reports returned as Map<String, Object> with no typed responses
 * - Hidden cache clear endpoint exists but is not listed in API docs (backdoor)
 * - No role-based authorization — any authenticated user can access admin endpoints
 * - Direct repository access bypasses any service-layer validation
 * - JdbcTemplate injected for raw SQL queries via SqlBuilder
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired
    private ReportingService reportingService;

    // Anti-pattern: injects repositories for direct access bypassing service layer
    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    // Anti-pattern: CacheManager injected for backdoor cache clearing
    @Autowired
    private CacheManager cacheManager;

    // Anti-pattern: JdbcTemplate injected into controller for raw SQL via SqlBuilder
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== SETTINGS ====================

    /**
     * Get all system settings.
     *
     * Anti-pattern: returns ALL settings including sensitive ones:
     * - JWT_SECRET (used for signing tokens)
     * - DEFAULT_PASSWORD (the default portal password)
     * - Database connection strings
     * - API keys for third-party services
     * No filtering of sensitive settings, no role check.
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getSettings(@RequestParam(required = false) String category) {
        List<SystemSetting> settings;

        if (category != null && !category.trim().isEmpty()) {
            settings = systemSettingRepository.findByCategory(category);
        } else {
            // Anti-pattern: loads ALL settings — including sensitive ones like JWT_SECRET,
            // database credentials, API keys, etc. No filtering whatsoever.
            settings = systemSettingRepository.findAll();
        }

        // Anti-pattern: returns settings in a Map wrapper with metadata
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("settings", settings);
        response.put("totalSettings", settings.size());

        // Anti-pattern: also expose settings as a key-value map for convenience
        // This makes it trivially easy for any caller to extract sensitive values
        Map<String, String> settingsMap = new LinkedHashMap<>();
        for (SystemSetting setting : settings) {
            settingsMap.put(setting.getSettingKey(), setting.getSettingValue());
        }
        response.put("settingsMap", settingsMap);

        // Anti-pattern: expose hardcoded constants alongside database settings
        // This leaks the JWT secret, default password, and other sensitive constants
        Map<String, Object> constantsMap = new LinkedHashMap<>();
        constantsMap.put("DEFAULT_COMMISSION_RATE", Constants.DEFAULT_COMMISSION_RATE);
        constantsMap.put("LISTING_AGENT_SPLIT", Constants.LISTING_AGENT_SPLIT);
        constantsMap.put("BUYER_AGENT_SPLIT", Constants.BUYER_AGENT_SPLIT);
        constantsMap.put("MAX_DTI_RATIO", Constants.MAX_DTI_RATIO);
        constantsMap.put("LATE_FEE_PERCENTAGE", Constants.LATE_FEE_PERCENTAGE);
        constantsMap.put("LATE_FEE_GRACE_DAYS", Constants.LATE_FEE_GRACE_DAYS);
        constantsMap.put("ESCROW_RESERVE_MONTHS", Constants.ESCROW_RESERVE_MONTHS);
        constantsMap.put("CACHE_TTL_SECONDS", Constants.CACHE_TTL_SECONDS);
        constantsMap.put("MAX_RESULTS", Constants.MAX_RESULTS);
        // Anti-pattern: EXPOSING SENSITIVE CONSTANTS IN THE API RESPONSE
        constantsMap.put("JWT_SECRET", Constants.JWT_SECRET);
        constantsMap.put("DEFAULT_PASSWORD", Constants.DEFAULT_PASSWORD);
        constantsMap.put("JWT_EXPIRATION_MS", Constants.JWT_EXPIRATION_MS);
        response.put("applicationConstants", constantsMap);

        // Anti-pattern: expose cache stats in settings response (mixing concerns)
        response.put("cacheStats", cacheManager.getStats());
        response.put("cacheSize", cacheManager.getSize());

        return ResponseEntity.ok(response);
    }

    /**
     * Update a system setting by key.
     * Anti-pattern: no validation of setting value, no audit trail, no authorization.
     * Allows updating ANY setting including security-sensitive ones.
     */
    @PutMapping("/settings/{key}")
    public ResponseEntity<?> updateSetting(@PathVariable String key,
                                             @RequestBody Map<String, String> body) {
        String newValue = body.get("value");
        if (newValue == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Setting value is required", "key", key));
        }

        // Anti-pattern: direct repo access, no service layer
        SystemSetting setting = systemSettingRepository.findBySettingKey(key);

        if (setting == null) {
            // Anti-pattern: if setting doesn't exist, create it on the fly
            // This means any arbitrary key-value pair can be stored
            setting = new SystemSetting();
            setting.setSettingKey(key);
            setting.setCategory("CUSTOM"); // Anti-pattern: uncategorized custom settings
            setting.setDescription("Auto-created via admin API");
            log.warn("Creating new system setting via admin API: {} = {}", key, newValue);
        }

        String oldValue = setting.getSettingValue();
        setting.setSettingValue(newValue);

        SystemSetting saved = systemSettingRepository.save(setting);

        // Anti-pattern: log the old and new values — including potential sensitive data
        log.info("System setting updated: {} changed from '{}' to '{}'", key, oldValue, newValue);

        // Anti-pattern: invalidate cache entries related to settings — but we don't know
        // which cache keys are affected, so we just invalidate the prefix "setting:"
        cacheManager.invalidate("setting:");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("setting", saved);
        response.put("previousValue", oldValue);
        response.put("newValue", newValue);
        response.put("message", "Setting updated successfully");

        return ResponseEntity.ok(response);
    }

    // ==================== NOTIFICATIONS ====================

    /**
     * Get notifications with optional filters.
     * Anti-pattern: bypasses service, direct repo access, loads all then filters in memory.
     * No pagination.
     */
    @GetMapping("/notifications")
    public ResponseEntity<?> getNotifications(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean unreadOnly) {

        List<Notification> notifications;

        // Anti-pattern: conditional repo method calls with fallback to load-all
        if (userId != null) {
            if (unreadOnly != null && unreadOnly) {
                notifications = notificationRepository.getUnreadNotificationsForUser(userId);
            } else {
                notifications = notificationRepository.findByUserId(userId);
            }
        } else if (unreadOnly != null && unreadOnly) {
            notifications = notificationRepository.findByIsReadFalse();
        } else if (type != null && !type.trim().isEmpty()) {
            notifications = notificationRepository.findByType(type);
        } else {
            // Anti-pattern: loads ALL notifications — could be millions
            notifications = notificationRepository.findAll();
        }

        // Anti-pattern: post-filter in memory for type when userId was also specified
        if (userId != null && type != null && !type.trim().isEmpty()) {
            notifications = new ArrayList<>(notifications);
            notifications.removeIf(n -> !type.equals(n.getType()));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("notifications", notifications);
        response.put("totalNotifications", notifications.size());

        // Anti-pattern: inline analytics in controller
        long unreadCount = notifications.stream()
                .filter(n -> n.getIsRead() == null || !n.getIsRead())
                .count();
        long readCount = notifications.stream()
                .filter(n -> Boolean.TRUE.equals(n.getIsRead()))
                .count();

        response.put("unreadCount", unreadCount);
        response.put("readCount", readCount);

        // Anti-pattern: group by type in memory
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Notification n : notifications) {
            String nType = n.getType() != null ? n.getType() : "UNKNOWN";
            byType.merge(nType, 1L, Long::sum);
        }
        response.put("countByType", byType);

        return ResponseEntity.ok(response);
    }

    // ==================== AUDIT LOGS ====================

    /**
     * Query audit logs with filtering.
     *
     * Anti-pattern: uses SqlBuilder for dynamic query construction,
     * which has a known SQL injection vulnerability via the and() method.
     * User-supplied filter values are concatenated directly into SQL strings.
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<?> getAuditLogs(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) UUID userId) {

        try {
            // Anti-pattern: build query using SqlBuilder — SQL INJECTION VULNERABILITY
            // User-supplied parameters are concatenated directly into the SQL string
            SqlBuilder builder = new SqlBuilder();
            builder.select("*").from("audit_logs");

            // Anti-pattern: user-supplied values concatenated into SQL via SqlBuilder.and()
            // Each of these is a SQL injection vector
            if (resourceType != null && !resourceType.trim().isEmpty()) {
                // SQL INJECTION: resourceType is concatenated directly into SQL
                builder.and("resource_type", "=", resourceType);
            }
            if (resourceId != null) {
                builder.and("resource_id", "=", resourceId.toString());
            }
            if (action != null && !action.trim().isEmpty()) {
                // SQL INJECTION: action is concatenated directly into SQL
                builder.and("action", "=", action);
            }
            if (userId != null) {
                builder.and("user_id", "=", userId.toString());
            }

            // Anti-pattern: date range filtering via string concatenation
            if (startDate != null && !startDate.trim().isEmpty()) {
                // SQL INJECTION: startDate string is concatenated into SQL
                builder.and("timestamp", ">=", startDate);
            }
            if (endDate != null && !endDate.trim().isEmpty()) {
                // SQL INJECTION: endDate string is concatenated into SQL
                builder.and("timestamp", "<=", endDate);
            }

            builder.orderBy("timestamp", "DESC");
            builder.limit(Constants.MAX_RESULTS);

            String sql = builder.build();
            log.debug("Audit log query: {}", sql); // Anti-pattern: logs potentially injected SQL

            // Anti-pattern: executes the injectable SQL string directly
            List<Map<String, Object>> results = SqlBuilder.executeQuery(jdbcTemplate, sql);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("auditLogs", results);
            response.put("totalResults", results.size());
            response.put("maxResults", Constants.MAX_RESULTS);
            response.put("query", sql); // Anti-pattern: EXPOSES THE SQL QUERY IN THE RESPONSE

            // Anti-pattern: also run the ReportingService query as a "fallback" and merge results
            // This creates confusion about which data source is authoritative
            if (resourceType != null && resourceId != null) {
                List<AuditLog> jpaResults = auditLogRepository.findResourceHistory(resourceType, resourceId.toString());
                response.put("jpaResultCount", jpaResults.size());
                if (jpaResults.size() != results.size()) {
                    log.warn("Audit log count mismatch: SqlBuilder returned {} but JPA returned {} " +
                                    "for resource {}:{}", results.size(), jpaResults.size(),
                            resourceType, resourceId);
                    response.put("dataInconsistencyWarning",
                            "SqlBuilder and JPA returned different result counts");
                }
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error querying audit logs: {}", e.getMessage(), e);
            // Anti-pattern: expose stack trace information in error response
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to query audit logs: " + e.getMessage(),
                            "errorType", e.getClass().getSimpleName()
                    ));
        }
    }

    // ==================== REPORTS ====================

    /**
     * Generate loan pipeline report.
     * Anti-pattern: returns Map<String, Object> from ReportingService,
     * adds additional controller-level processing on top.
     */
    @GetMapping("/reports/pipeline")
    public ResponseEntity<?> getLoanPipelineReport(
            @RequestParam UUID loanOfficerId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        try {
            // Anti-pattern: parse dates in controller with poor error handling
            LocalDate start = startDate != null ?
                    LocalDate.parse(startDate) : LocalDate.now().minusMonths(3);
            LocalDate end = endDate != null ?
                    LocalDate.parse(endDate) : LocalDate.now();

            Map<String, Object> report = reportingService.generateLoanPipelineReport(
                    loanOfficerId, start, end);

            // Anti-pattern: controller adds wrapping and additional metadata
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("report", report);
            response.put("requestedBy", "ADMIN"); // Anti-pattern: hardcoded, no actual auth context
            response.put("generatedAt", LocalDateTime.now().toString());
            response.put("parameters", Map.of(
                    "loanOfficerId", loanOfficerId,
                    "startDate", start.toString(),
                    "endDate", end.toString()
            ));

            // Anti-pattern: cache the report in the hand-rolled CacheManager
            String cacheKey = "report:pipeline:" + loanOfficerId + ":" + start + ":" + end;
            cacheManager.put(cacheKey, response);
            response.put("cacheKey", cacheKey); // Anti-pattern: expose cache key in response

            return ResponseEntity.ok(response);

        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid date format. Use yyyy-MM-dd",
                            "startDate", startDate != null ? startDate : "null",
                            "endDate", endDate != null ? endDate : "null"));
        } catch (Exception e) {
            log.error("Error generating pipeline report: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Generate commission report.
     * Anti-pattern: returns untyped Map from ReportingService,
     * includes HTML report string in JSON response.
     */
    @GetMapping("/reports/commissions")
    public ResponseEntity<?> getCommissionReport(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        try {
            LocalDate start = startDate != null ?
                    LocalDate.parse(startDate) : LocalDate.now().minusMonths(6);
            LocalDate end = endDate != null ?
                    LocalDate.parse(endDate) : LocalDate.now();

            Map<String, Object> report = reportingService.generateCommissionReport(
                    agentId, start, end);

            // Anti-pattern: controller wraps the report with additional metadata
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("report", report);
            response.put("generatedAt", LocalDateTime.now().toString());
            response.put("parameters", Map.of(
                    "agentId", agentId != null ? agentId.toString() : "ALL",
                    "startDate", start.toString(),
                    "endDate", end.toString()
            ));

            // Anti-pattern: expose whether the report contains HTML (from the service)
            // The HTML report is embedded in the JSON response — mixing content types
            if (report.containsKey("htmlReport")) {
                response.put("hasHtmlReport", true);
                response.put("htmlReportSize", report.get("htmlReport").toString().length());
            }

            // Anti-pattern: cache the commission report
            String cacheKey = "report:commission:" +
                    (agentId != null ? agentId : "all") + ":" + start + ":" + end;
            cacheManager.put(cacheKey, response);

            return ResponseEntity.ok(response);

        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid date format. Use yyyy-MM-dd"));
        } catch (Exception e) {
            log.error("Error generating commission report: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== HIDDEN BACKDOOR ====================

    /**
     * Clear all caches.
     *
     * Anti-pattern: HIDDEN BACKDOOR — this endpoint is not documented in any API spec,
     * Swagger definition, or README. It exists solely because a developer needed it
     * during debugging and left it in production code. No authorization check.
     *
     * The path "/api/admin/cache/clear" is not RESTful and uses a verb in the URL.
     * A DELETE to "/api/admin/cache" would be more appropriate if this were intentional.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<?> clearCache(@RequestParam(required = false) String prefix) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            String statsBefore = cacheManager.getStats();
            int sizeBefore = cacheManager.getSize();

            if (prefix != null && !prefix.trim().isEmpty()) {
                // Anti-pattern: invalidate by prefix — could accidentally clear important entries
                cacheManager.invalidate(prefix);
                response.put("action", "INVALIDATE_BY_PREFIX");
                response.put("prefix", prefix);
                log.warn("Cache invalidated by prefix '{}' via admin backdoor", prefix);
            } else {
                // Anti-pattern: nuke the ENTIRE cache with no confirmation
                cacheManager.clearAll();
                response.put("action", "CLEAR_ALL");
                log.warn("ENTIRE CACHE CLEARED via admin backdoor endpoint");
            }

            String statsAfter = cacheManager.getStats();
            int sizeAfter = cacheManager.getSize();

            response.put("statsBefore", statsBefore);
            response.put("statsAfter", statsAfter);
            response.put("entriesBefore", sizeBefore);
            response.put("entriesAfter", sizeAfter);
            response.put("entriesRemoved", sizeBefore - sizeAfter);
            response.put("message", "Cache operation completed");

            // Anti-pattern: log an audit entry for the cache clear but bypass the service
            try {
                AuditLog audit = new AuditLog();
                audit.setAction("CACHE_CLEAR");
                audit.setResourceType("SystemCache");
                audit.setOldValue("size:" + sizeBefore);
                audit.setNewValue("size:" + sizeAfter);
                audit.setIpAddress("0.0.0.0"); // Anti-pattern: hardcoded IP
                auditLogRepository.save(audit);
            } catch (Exception e) {
                log.error("Failed to audit cache clear: {}", e.getMessage());
                // Anti-pattern: swallow audit failure
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error clearing cache: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to clear cache: " + e.getMessage()));
        }
    }

    // ==================== USER ACTIVITY ====================

    /**
     * Get user activity report from audit logs.
     * Anti-pattern: uses native query from repo that returns Object[],
     * manually maps results to a Map — fragile and untyped.
     */
    @GetMapping("/activity")
    public ResponseEntity<?> getUserActivity(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        try {
            LocalDateTime start = startDate != null ?
                    LocalDate.parse(startDate).atStartOfDay() :
                    LocalDateTime.now().minusDays(30);
            LocalDateTime end = endDate != null ?
                    LocalDate.parse(endDate).atTime(23, 59, 59) :
                    LocalDateTime.now();

            // Anti-pattern: calls repo's native query that returns Object[]
            List<Object[]> rawResults = auditLogRepository.getUserActivityReport(start, end);

            // Anti-pattern: manually maps Object[] to Map — fragile, index-based access
            List<Map<String, Object>> activities = new ArrayList<>();
            for (Object[] row : rawResults) {
                Map<String, Object> activity = new LinkedHashMap<>();
                activity.put("userId", row[0]);      // Anti-pattern: assumes column order
                activity.put("action", row[1]);       // Anti-pattern: index-based access
                activity.put("actionCount", row[2]);  // Anti-pattern: no type safety
                activities.add(activity);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("activityReport", activities);
            response.put("totalEntries", activities.size());
            response.put("dateRange", Map.of("start", start.toString(), "end", end.toString()));

            return ResponseEntity.ok(response);

        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid date format. Use yyyy-MM-dd"));
        } catch (Exception e) {
            log.error("Error generating user activity report: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
