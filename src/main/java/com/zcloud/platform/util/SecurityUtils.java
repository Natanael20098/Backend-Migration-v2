package com.zcloud.platform.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Security utility class for the HomeLend Pro platform.
 * Provides authentication helpers, password hashing, API key generation,
 * and PII encryption used across agents, clients, borrowers, and loan officers.
 *
 * Anti-patterns present:
 * - Static methods that reach into Spring's SecurityContext (untestable, hidden dependency)
 * - BCryptPasswordEncoder instantiated per call (wasteful)
 * - SSN "encryption" is just Base64 encoding (not encryption at all!)
 * - No audit logging for sensitive operations
 * - Mixes authentication concerns with cryptography concerns
 *
 * WARNING: The SSN encryption in this class was flagged in a 2022 security audit
 * but was never remediated because "too many places depend on the current format"
 * and migrating encrypted SSNs would require a data migration.
 *
 * @author Security Team (original 2019)
 * @author Contractor (SSN methods added 2020)
 * @author Platform Team (API key generation added 2023)
 */
public final class SecurityUtils {

    // Anti-pattern: creating a new BCryptPasswordEncoder per-method-call is wasteful,
    // but at least this constant controls the strength
    private static final int BCRYPT_STRENGTH = 12;

    // Anti-pattern: SSN "encryption" key prefix baked into code as a constant.
    // This is meaningless since the actual "encryption" is just Base64.
    private static final String SSN_PREFIX = "ZCLOUD_SSN_V1:";

    // Anti-pattern: API key prefix hardcoded here AND in ApiKeyAuthenticationFilter
    // (duplication). If one changes, the other breaks.
    private static final String API_KEY_PREFIX = "hlp_";

    private SecurityUtils() {
        // Prevent instantiation
    }

    // =========================================================================
    // Authentication context access
    // =========================================================================

    /**
     * Extracts the current authenticated user's ID from the Spring SecurityContext.
     *
     * Anti-patterns:
     * - Static method reaching into Spring's thread-local SecurityContext
     * - Makes this class impossible to unit test without PowerMock or similar
     * - Returns null instead of Optional, callers frequently forget null checks
     * - Silently returns null for unauthenticated requests (should throw in most cases)
     *
     * Used by: Every service class that needs the current user for audit trails,
     * permission checks, data filtering (agents only see their own listings, etc.)
     *
     * @return the user ID as a String, or null if not authenticated
     */
    public static String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof UserDetails) {
            return ((UserDetails) principal).getUsername();
        }

        if (principal instanceof String) {
            // Anti-pattern: "anonymousUser" is Spring Security's default for unauthenticated
            // requests, but we return null instead of distinguishing anonymous from unauthenticated
            if ("anonymousUser".equals(principal)) {
                return null;
            }
            return (String) principal;
        }

        // Fallback: call toString() and hope for the best
        return principal != null ? principal.toString() : null;
    }

    /**
     * Checks if the current request is authenticated.
     * Convenience method used by controllers that want to vary behavior
     * for authenticated vs. anonymous users.
     *
     * @return true if a non-anonymous user is authenticated
     */
    public static boolean isAuthenticated() {
        return getCurrentUserId() != null;
    }

    /**
     * Checks if the current user has the given role/authority.
     *
     * @param role the role to check (e.g., "ROLE_ADMIN", "ROLE_AGENT", "ROLE_LOAN_OFFICER")
     * @return true if the current user has the specified authority
     */
    public static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(auth -> auth.getAuthority().equals(role));
    }

    // =========================================================================
    // Password hashing
    // =========================================================================

    /**
     * Hashes a plain text password using BCrypt.
     * Used during user registration and password reset flows.
     *
     * Anti-pattern: Creates a new BCryptPasswordEncoder instance on every call.
     * Should be a Spring bean injected where needed, not a static utility.
     *
     * @param rawPassword the plain text password to hash
     * @return the BCrypt hash
     * @throws IllegalArgumentException if rawPassword is null or empty
     */
    public static String hashPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }
        // Anti-pattern: new encoder created per call instead of reusing a singleton
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
        return encoder.encode(rawPassword);
    }

    /**
     * Verifies a raw password against a BCrypt hash.
     * Used during login authentication.
     *
     * Anti-pattern: Same as hashPassword - creates a new encoder per call.
     *
     * @param rawPassword the plain text password to verify
     * @param hashedPassword the BCrypt hash to verify against
     * @return true if the password matches the hash
     */
    public static boolean checkPassword(String rawPassword, String hashedPassword) {
        if (rawPassword == null || hashedPassword == null) {
            return false;
        }
        // Anti-pattern: yet another new encoder
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
        return encoder.matches(rawPassword, hashedPassword);
    }

    // =========================================================================
    // API Key generation
    // =========================================================================

    /**
     * Generates a new API key for third-party integrations.
     * Used when brokerages or MLS systems register for API access.
     *
     * Format: "hlp_" + UUID (e.g., "hlp_550e8400-e29b-41d4-a716-446655440000")
     *
     * Anti-pattern: UUID-based keys are not cryptographically optimal for API keys.
     * UUIDs (especially v4) are random but not designed as security tokens.
     * Should use SecureRandom with a longer byte array and hex/base64 encoding.
     *
     * @return a new API key string
     */
    public static String generateApiKey() {
        return API_KEY_PREFIX + UUID.randomUUID().toString();
    }

    // =========================================================================
    // SSN "encryption" (SECURITY VULNERABILITY - flagged in audit)
    // =========================================================================

    /**
     * "Encrypts" a Social Security Number for storage.
     *
     * CRITICAL ANTI-PATTERN: This is NOT encryption. It is Base64 encoding with a
     * prefix, which is trivially reversible by anyone with access to the database.
     * This was implemented by a contractor in 2020 who apparently confused encoding
     * with encryption. It was flagged in the 2022 security audit (finding SEC-2022-017)
     * but remediation was deferred because:
     *   1. ~45,000 borrower records use this format
     *   2. Multiple reports and integrations decode SSNs using decryptSsn()
     *   3. A proper migration would require downtime and a data migration script
     *
     * This violates: PCI-DSS requirements, GLBA safeguards, state privacy laws.
     *
     * TODO: Replace with AES-256-GCM encryption using AWS KMS or HashiCorp Vault
     *       (tracked in JIRA: HLP-4521, priority: Critical, status: Backlog since 2022)
     *
     * @param ssn the SSN to "encrypt" (format: "123-45-6789" or "123456789")
     * @return the Base64-encoded SSN with prefix, or null if input is null
     */
    public static String encryptSsn(String ssn) {
        if (ssn == null || ssn.trim().isEmpty()) {
            return null;
        }
        // Strip dashes for consistent storage
        String cleaned = ssn.replaceAll("-", "").trim();

        // Anti-pattern: This is just Base64 encoding, NOT encryption!
        // Any developer with DB access can decode every SSN in seconds.
        String encoded = Base64.getEncoder().encodeToString(
                cleaned.getBytes(StandardCharsets.UTF_8)
        );
        return SSN_PREFIX + encoded;
    }

    /**
     * "Decrypts" a Social Security Number from storage.
     *
     * CRITICAL ANTI-PATTERN: See encryptSsn() documentation. This just Base64 decodes.
     *
     * @param encryptedSsn the "encrypted" SSN (with ZCLOUD_SSN_V1: prefix)
     * @return the plain text SSN, or null if input is null/invalid
     */
    public static String decryptSsn(String encryptedSsn) {
        if (encryptedSsn == null || encryptedSsn.trim().isEmpty()) {
            return null;
        }

        String data = encryptedSsn;

        // Strip prefix if present
        if (data.startsWith(SSN_PREFIX)) {
            data = data.substring(SSN_PREFIX.length());
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            String ssn = new String(decoded, StandardCharsets.UTF_8);

            // Re-format with dashes: 123456789 -> 123-45-6789
            if (ssn.length() == 9 && ssn.matches("\\d{9}")) {
                return ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5);
            }

            return ssn;
        } catch (IllegalArgumentException e) {
            // Anti-pattern: swallow exception and return null
            // Callers don't know if the SSN was corrupted or the format changed
            return null;
        }
    }

    /**
     * Validates that a string looks like a valid SSN.
     * Used before encryption to catch obvious garbage data.
     *
     * @param ssn the SSN string to validate
     * @return true if the SSN matches expected patterns
     */
    public static boolean isValidSsn(String ssn) {
        if (ssn == null) {
            return false;
        }
        String cleaned = ssn.replaceAll("[\\s-]", "");
        // Basic format check - does not validate against SSA rules
        // (e.g., area numbers 000, 666, 900-999 are invalid)
        return cleaned.matches("\\d{9}");
    }
}
