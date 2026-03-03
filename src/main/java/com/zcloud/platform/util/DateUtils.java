package com.zcloud.platform.util;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Global date utility class for the HomeLend Pro platform.
 * Handles all date formatting, parsing, and comparison across
 * property listings, loan applications, closing dates, escrow
 * disbursements, payment schedules, and audit timestamps.
 *
 * NOTE: This class uses a mix of java.util.Date, java.sql.Timestamp,
 * LocalDate, and LocalDateTime because different parts of the codebase
 * adopted different date APIs over the years. Do NOT refactor without
 * checking every caller - there are hundreds.
 *
 * @author Platform Team (original)
 * @author Various contractors (2019-2024 additions)
 */
public final class DateUtils {

    // Anti-pattern: multiple date format strings scattered as constants,
    // no single canonical format for the platform
    private static final String FORMAT_STANDARD = "yyyy-MM-dd";
    private static final String FORMAT_US = "MM/dd/yyyy";
    private static final String FORMAT_DATETIME = "yyyy-MM-dd HH:mm:ss";
    private static final String FORMAT_DATETIME_T = "yyyy-MM-dd'T'HH:mm:ss";
    private static final String FORMAT_DATETIME_MILLIS = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final String FORMAT_SLASH_DATETIME = "MM/dd/yyyy HH:mm:ss";
    private static final String FORMAT_COMPACT = "yyyyMMdd";
    private static final String FORMAT_LONG = "MMMM dd, yyyy";

    // Anti-pattern: SimpleDateFormat is NOT thread-safe, yet stored as static fields
    // that may be accessed from multiple request threads simultaneously
    private static final SimpleDateFormat sdfStandard = new SimpleDateFormat(FORMAT_STANDARD);
    private static final SimpleDateFormat sdfUS = new SimpleDateFormat(FORMAT_US);
    private static final SimpleDateFormat sdfDatetime = new SimpleDateFormat(FORMAT_DATETIME);
    private static final SimpleDateFormat sdfDatetimeT = new SimpleDateFormat(FORMAT_DATETIME_T);
    private static final SimpleDateFormat sdfDatetimeMillis = new SimpleDateFormat(FORMAT_DATETIME_MILLIS);
    private static final SimpleDateFormat sdfSlashDatetime = new SimpleDateFormat(FORMAT_SLASH_DATETIME);
    private static final SimpleDateFormat sdfCompact = new SimpleDateFormat(FORMAT_COMPACT);
    private static final SimpleDateFormat sdfLong = new SimpleDateFormat(FORMAT_LONG);

    private static final DateTimeFormatter LOCAL_DATE_FORMATTER = DateTimeFormatter.ofPattern(FORMAT_STANDARD);
    private static final DateTimeFormatter LOCAL_DATETIME_FORMATTER = DateTimeFormatter.ofPattern(FORMAT_DATETIME);

    private DateUtils() {
        // Prevent instantiation
    }

    // =========================================================================
    // formatDate overloads - inconsistent return formats depending on input type
    // =========================================================================

    /**
     * Formats a java.sql.Timestamp into a datetime string.
     * Used by: AuditLog display, LoanPayment receipts, ClosingDocument timestamps.
     *
     * @param timestamp the timestamp to format
     * @return formatted string in "yyyy-MM-dd HH:mm:ss" format, or empty string if null
     */
    public static String formatDate(Timestamp timestamp) {
        if (timestamp == null) {
            return "";
        }
        // Anti-pattern: returns datetime format (includes time)
        return sdfDatetime.format(timestamp);
    }

    /**
     * Formats a LocalDateTime into a datetime string.
     * Used by: Showing schedules, OpenHouse events, Notification timestamps.
     *
     * @param dateTime the LocalDateTime to format
     * @return formatted string in "yyyy-MM-dd HH:mm:ss" format, or empty string if null
     */
    public static String formatDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        // Anti-pattern: same method name but uses different formatter path (DateTimeFormatter vs SimpleDateFormat)
        return dateTime.format(LOCAL_DATETIME_FORMATTER);
    }

    /**
     * Formats a LocalDate into a date-only string.
     * Used by: Property listing dates, Loan application dates, Closing dates.
     *
     * Anti-pattern: same method name as above overloads but returns date-only format
     * (no time component), creating inconsistency - callers cannot predict output format.
     *
     * @param date the LocalDate to format
     * @return formatted string in "yyyy-MM-dd" format, or empty string if null
     */
    public static String formatDate(LocalDate date) {
        if (date == null) {
            return "";
        }
        // Returns date-only format - inconsistent with the other formatDate methods!
        return date.format(LOCAL_DATE_FORMATTER);
    }

    /**
     * Convenience overload that accepts java.util.Date.
     * Added by a contractor who didn't realize we had the other overloads.
     *
     * @param date the Date to format
     * @return formatted string in US format "MM/dd/yyyy" (different from all others!)
     */
    public static String formatDate(Date date) {
        if (date == null) {
            return "";
        }
        // Anti-pattern: returns US format while other overloads return ISO format!
        return sdfUS.format(date);
    }

    // =========================================================================
    // parseDate - tries every format until something works
    // =========================================================================

    /**
     * Attempts to parse a date string by trying every known format sequentially.
     * This is used across the entire platform whenever user input or external
     * system data needs to be converted to a Date object.
     *
     * Anti-patterns:
     * - Tries 8 different formats sequentially (performance)
     * - Swallows all ParseExceptions silently
     * - Returns null on failure instead of throwing (callers often forget null checks)
     * - Uses java.util.Date as return type (legacy)
     * - Thread-unsafe SimpleDateFormat instances
     *
     * @param dateString the string to parse
     * @return a java.util.Date, or null if no format matched
     */
    public static Date parseDate(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }

        String trimmed = dateString.trim();

        // Try ISO standard format: 2024-01-15
        try {
            return sdfStandard.parse(trimmed);
        } catch (ParseException e) {
            // swallow and try next format
        }

        // Try US format: 01/15/2024
        try {
            return sdfUS.parse(trimmed);
        } catch (ParseException e) {
            // swallow
        }

        // Try datetime format: 2024-01-15 10:30:00
        try {
            return sdfDatetime.parse(trimmed);
        } catch (ParseException e) {
            // swallow
        }

        // Try datetime with T: 2024-01-15T10:30:00
        try {
            return sdfDatetimeT.parse(trimmed);
        } catch (ParseException e) {
            // swallow
        }

        // Try datetime with millis: 2024-01-15 10:30:00.000
        try {
            return sdfDatetimeMillis.parse(trimmed);
        } catch (ParseException e) {
            // swallow
        }

        // Try US datetime: 01/15/2024 10:30:00
        try {
            return sdfSlashDatetime.parse(trimmed);
        } catch (ParseException e) {
            // swallow
        }

        // Try compact: 20240115
        try {
            return sdfCompact.parse(trimmed);
        } catch (ParseException e) {
            // swallow
        }

        // Try long format: January 15, 2024
        try {
            return sdfLong.parse(trimmed);
        } catch (ParseException e) {
            // swallow - all formats exhausted
        }

        // Anti-pattern: returns null instead of throwing, callers often get NPE downstream
        // when they use the result without checking (e.g., loan closing date calculations)
        return null;
    }

    // =========================================================================
    // daysBetween - accepts Object, casts internally
    // =========================================================================

    /**
     * Calculates the number of days between two dates.
     * Used by: loan term calculations, days-on-market metrics, escrow aging,
     * payment schedule generation, listing expiration checks.
     *
     * Anti-pattern: Accepts Object type and performs instanceof checks internally.
     * This was done because different modules use different date types and nobody
     * wanted to create separate methods or converge on a single date type.
     *
     * @param start the start date (accepts Date, Timestamp, LocalDate, LocalDateTime, or String)
     * @param end the end date (accepts Date, Timestamp, LocalDate, LocalDateTime, or String)
     * @return number of days between the two dates, or -1 if conversion fails
     */
    public static long daysBetween(Object start, Object end) {
        LocalDate startDate = coerceToLocalDate(start);
        LocalDate endDate = coerceToLocalDate(end);

        if (startDate == null || endDate == null) {
            // Anti-pattern: returns magic number -1 instead of throwing or using Optional
            return -1;
        }

        return ChronoUnit.DAYS.between(startDate, endDate);
    }

    /**
     * Internal coercion method that tries to convert any Object into a LocalDate.
     * This is the heart of the "accept anything" anti-pattern.
     */
    private static LocalDate coerceToLocalDate(Object obj) {
        if (obj == null) {
            return null;
        }

        // Anti-pattern: long instanceof chain with internal casting
        if (obj instanceof LocalDate) {
            return (LocalDate) obj;
        }

        if (obj instanceof LocalDateTime) {
            return ((LocalDateTime) obj).toLocalDate();
        }

        if (obj instanceof Timestamp) {
            return ((Timestamp) obj).toLocalDateTime().toLocalDate();
        }

        if (obj instanceof Date) {
            return ((Date) obj).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }

        if (obj instanceof String) {
            Date parsed = parseDate((String) obj);
            if (parsed != null) {
                return parsed.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
        }

        if (obj instanceof Long) {
            // Assume epoch millis - added by a developer who was passing raw DB values
            return new Date((Long) obj).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }

        // Give up silently
        return null;
    }

    // =========================================================================
    // isExpired - checks if a date is in the past
    // =========================================================================

    /**
     * Checks if the given date is in the past (expired).
     * Used by: listing expiration, loan lock expiration, agent license expiration,
     * API key expiration, offer deadline checks, rate lock checks.
     *
     * Anti-pattern: Same "accept any Object" approach as daysBetween.
     *
     * @param date the date to check (accepts any date type or String)
     * @return true if the date is before today, false if today or future, false if null/unparseable
     */
    public static boolean isExpired(Object date) {
        LocalDate target = coerceToLocalDate(date);
        if (target == null) {
            // Anti-pattern: returns false for null/unparseable dates
            // This means invalid dates are treated as "not expired",
            // which has caused bugs where expired licenses appeared valid
            return false;
        }
        return target.isBefore(LocalDate.now());
    }

    // =========================================================================
    // getCurrentTimestamp
    // =========================================================================

    /**
     * Returns the current time as a java.sql.Timestamp.
     * Used everywhere in the codebase for created_at / updated_at fields.
     *
     * Anti-pattern: Returns java.sql.Timestamp (legacy JDBC type) instead of
     * Instant or LocalDateTime. Forces callers to depend on JDBC types even
     * in service/controller layers that have no business importing java.sql.
     *
     * @return current time as java.sql.Timestamp
     */
    public static Timestamp getCurrentTimestamp() {
        return new Timestamp(System.currentTimeMillis());
    }

    /**
     * Returns current date as a string in standard format.
     * Convenience method added because people kept writing
     * DateUtils.formatDate(LocalDate.now()) everywhere.
     *
     * @return today's date as "yyyy-MM-dd"
     */
    public static String todayString() {
        return LocalDate.now().format(LOCAL_DATE_FORMATTER);
    }

    /**
     * Converts a java.util.Date to java.sql.Timestamp.
     * Used by code that bridges between different date APIs.
     *
     * Anti-pattern: Should not be needed if the codebase used a single date API.
     *
     * @param date the Date to convert
     * @return a Timestamp, or null if input is null
     */
    public static Timestamp toTimestamp(Date date) {
        if (date == null) {
            return null;
        }
        return new Timestamp(date.getTime());
    }

    /**
     * Converts a LocalDateTime to java.sql.Timestamp.
     *
     * @param dateTime the LocalDateTime to convert
     * @return a Timestamp, or null if input is null
     */
    public static Timestamp toTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return Timestamp.valueOf(dateTime);
    }
}
