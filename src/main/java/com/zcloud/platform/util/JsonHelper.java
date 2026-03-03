package com.zcloud.platform.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON helper utility for the HomeLend Pro platform.
 * Provides JSON serialization, deserialization, pretty printing, and field extraction.
 *
 * Anti-patterns present:
 * - Entirely redundant: Spring Boot auto-configures a Jackson ObjectMapper bean
 *   that is available for injection everywhere. This class duplicates that capability.
 * - Creates its OWN ObjectMapper with its OWN configuration, which may differ from
 *   Spring's auto-configured one (e.g., different date formats, different feature flags).
 *   This causes subtle serialization inconsistencies between REST responses (Spring's mapper)
 *   and internal JSON operations (this mapper).
 * - extractField() uses naive string manipulation instead of proper JSON parsing,
 *   which breaks on nested objects, escaped quotes, arrays, and many edge cases.
 * - Static utility class pattern prevents proper dependency injection and testing.
 *
 * Used by: Notification payloads, webhook serialization, audit log detail fields,
 * MLS data import/export, document metadata storage, loan application JSON snapshots.
 *
 * @author Platform Team (2020)
 * @author Multiple contributors
 */
public final class JsonHelper {

    // Anti-pattern: This ObjectMapper has DIFFERENT configuration than Spring's
    // auto-configured bean. For example, Spring might use ISO dates while this
    // one could serialize dates differently depending on JavaTimeModule registration order.
    private static final ObjectMapper objectMapper;

    static {
        objectMapper = new ObjectMapper();
        // Register Java 8 date/time support
        objectMapper.registerModule(new JavaTimeModule());
        // Don't fail on unknown properties (lenient deserialization)
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Write dates as strings, not timestamps
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        // Don't fail on empty beans
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    private JsonHelper() {
        // Prevent instantiation
    }

    // =========================================================================
    // Serialization (redundant with Spring's ObjectMapper)
    // =========================================================================

    /**
     * Serializes an object to a JSON string.
     *
     * Anti-pattern: This is a thin wrapper around ObjectMapper.writeValueAsString()
     * that exists solely to avoid importing Jackson directly in calling code.
     * Instead of injecting Spring's ObjectMapper, ~150 classes import JsonHelper.
     *
     * @param object the object to serialize
     * @return JSON string representation
     * @throws RuntimeException wrapping JsonProcessingException (anti-pattern:
     *         converts checked exception to unchecked, losing specific error info)
     */
    public static String toJson(Object object) {
        if (object == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            // Anti-pattern: wraps in RuntimeException, losing the specific exception type.
            // Callers catch RuntimeException broadly, making error handling imprecise.
            throw new RuntimeException("Failed to serialize object to JSON: " + object.getClass().getSimpleName(), e);
        }
    }

    /**
     * Deserializes a JSON string to an object of the specified type.
     *
     * Anti-pattern: Same wrapper concern as toJson(). Also, the generic type
     * parameter means callers often get ClassCastException at runtime if the
     * JSON structure doesn't match the target class.
     *
     * @param json the JSON string to deserialize
     * @param clazz the target class
     * @param <T> the target type
     * @return deserialized object
     * @throws RuntimeException wrapping JsonProcessingException
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            // Anti-pattern: wraps in RuntimeException, same as above
            throw new RuntimeException("Failed to deserialize JSON to " + clazz.getSimpleName() + ": " + truncate(json, 200), e);
        }
    }

    // =========================================================================
    // Pretty printing
    // =========================================================================

    /**
     * Takes a JSON string (compact or otherwise) and reformats it with indentation.
     * Used for: admin dashboard JSON display, debug logging, audit log viewer.
     *
     * Anti-pattern: Parses and re-serializes the entire JSON just to add whitespace.
     * For large loan application snapshots (which can be 50KB+), this is wasteful.
     *
     * @param json the JSON string to pretty-print
     * @return pretty-printed JSON string, or the original string if parsing fails
     */
    public static String prettyPrint(String json) {
        if (json == null || json.trim().isEmpty()) {
            return json;
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (JsonProcessingException e) {
            // Anti-pattern: silently returns original string if JSON is invalid.
            // Caller has no idea the input was malformed.
            return json;
        }
    }

    // =========================================================================
    // Field extraction (the worst anti-pattern in this class)
    // =========================================================================

    /**
     * Extracts a top-level field value from a JSON string using string manipulation.
     *
     * CRITICAL ANTI-PATTERN: This method uses indexOf/substring to find a field value
     * instead of parsing the JSON properly. This is fragile and breaks in many cases:
     *
     *   1. Nested objects: {"address": {"city": "Denver"}} - searching for "city" might
     *      find it inside a nested object when you wanted a top-level field.
     *   2. Escaped quotes: {"note": "He said \"hello\""} - the quote detection breaks.
     *   3. Arrays: {"tags": ["residential", "luxury"]} - returns garbage.
     *   4. Numbers/booleans: {"price": 450000} - returns with trailing comma or brace.
     *   5. Field name substrings: {"cityName": "X", "city": "Y"} - might match wrong field.
     *   6. Null values: {"field": null} - returns the string "null" instead of actual null.
     *
     * This was written by a junior developer who "didn't want to add Jackson as a dependency"
     * even though Jackson was already on the classpath via Spring Boot.
     *
     * Known bugs filed: HLP-3201, HLP-3455, HLP-3782, HLP-4010
     *
     * @param json the JSON string to search
     * @param field the field name to extract
     * @return the field value as a string, or null if not found
     */
    public static String extractField(String json, String field) {
        if (json == null || field == null) {
            return null;
        }

        // Anti-pattern: string-based JSON "parsing" that breaks in countless edge cases
        String searchKey = "\"" + field + "\"";
        int keyIndex = json.indexOf(searchKey);

        if (keyIndex == -1) {
            return null;
        }

        // Find the colon after the key
        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex == -1) {
            return null;
        }

        // Skip whitespace after the colon
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
            valueStart++;
        }

        if (valueStart >= json.length()) {
            return null;
        }

        char firstChar = json.charAt(valueStart);

        // If the value is a quoted string
        if (firstChar == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd == -1) {
                return null;
            }
            // Anti-pattern: doesn't handle escaped quotes inside the value!
            // e.g., "description": "5\" tall fence" would break here
            return json.substring(valueStart + 1, valueEnd);
        }

        // If the value is not quoted (number, boolean, null)
        // Find the next comma, closing brace, or closing bracket
        int valueEnd = valueStart;
        while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (c == ',' || c == '}' || c == ']') {
                break;
            }
            valueEnd++;
        }

        String value = json.substring(valueStart, valueEnd).trim();

        // Anti-pattern: returns the string "null" for JSON null values
        // instead of returning Java null. Callers do string.equals("null") checks
        // scattered throughout the codebase.
        return value;
    }

    /**
     * Checks if a string is valid JSON.
     * Used by: webhook payload validation, audit log detail validation.
     *
     * @param json the string to validate
     * @return true if the string is valid JSON
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        try {
            objectMapper.readValue(json, Object.class);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Merges two JSON strings at the top level.
     * Fields from the second JSON overwrite fields from the first.
     * Used by: partial update operations on loan applications and property metadata.
     *
     * Anti-pattern: round-trips through Java Maps using unchecked casts.
     *
     * @param baseJson the base JSON string
     * @param overrideJson the override JSON string
     * @return merged JSON string
     */
    @SuppressWarnings("unchecked")
    public static String merge(String baseJson, String overrideJson) {
        if (baseJson == null || baseJson.trim().isEmpty()) {
            return overrideJson;
        }
        if (overrideJson == null || overrideJson.trim().isEmpty()) {
            return baseJson;
        }
        try {
            java.util.Map<String, Object> base = objectMapper.readValue(baseJson, java.util.Map.class);
            java.util.Map<String, Object> override = objectMapper.readValue(overrideJson, java.util.Map.class);
            base.putAll(override);
            return objectMapper.writeValueAsString(base);
        } catch (JsonProcessingException e) {
            // Anti-pattern: returns baseJson on failure, silently losing the override data
            return baseJson;
        }
    }

    /**
     * Truncates a string for safe inclusion in log messages and error messages.
     * Prevents huge JSON payloads from flooding logs.
     *
     * @param str the string to truncate
     * @param maxLength the maximum length
     * @return the truncated string with "..." appended if truncated
     */
    private static String truncate(String str, int maxLength) {
        if (str == null) {
            return "null";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "...";
    }
}
