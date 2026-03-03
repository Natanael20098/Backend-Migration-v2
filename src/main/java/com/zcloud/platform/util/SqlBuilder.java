package com.zcloud.platform.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hand-built SQL query builder for the HomeLend Pro platform.
 * Constructs SQL SELECT statements through method chaining with string concatenation.
 *
 * Anti-patterns present:
 * - SQL INJECTION VULNERABILITY: The and() method concatenates user-supplied values
 *   directly into the SQL string without parameterization. This is exploitable.
 * - Reinvents the wheel: JPA/Hibernate Criteria API, Spring Data Specifications,
 *   JOOQ, or even JdbcTemplate's parameterized queries already solve this problem safely.
 * - Mutable builder with no thread safety (the same builder instance could be modified
 *   concurrently if shared across threads, which has happened in cached report generators).
 * - The static executeQuery() method couples query building to query execution,
 *   mixing concerns.
 * - No support for JOINs, subqueries, GROUP BY, HAVING, or aggregate functions,
 *   so developers end up concatenating raw SQL fragments onto the build() output anyway.
 *
 * History: This was written in 2019 when the team "didn't want the overhead of an ORM"
 * for reporting queries. It was supposed to be temporary. It now has 200+ call sites
 * across the reporting, search, and analytics modules.
 *
 * Known security incidents:
 * - INC-2023-041: SQL injection via property search "city" field (patched with input validation
 *   in the controller, but the root cause here was never fixed)
 * - INC-2024-003: SQL injection via agent name search (same root cause)
 *
 * @author Backend Team (2019)
 * @author Various contributors
 */
public class SqlBuilder {

    private final StringBuilder sql;
    private boolean hasWhere;
    private boolean hasOrderBy;
    private boolean hasLimit;
    private String currentTable;

    /**
     * Creates a new SqlBuilder instance.
     * Each query should use a fresh builder instance.
     *
     * Anti-pattern: No enforcement that a new instance is used per query.
     * Some call sites reuse builder instances across loop iterations,
     * causing WHERE clauses to accumulate.
     */
    public SqlBuilder() {
        this.sql = new StringBuilder();
        this.hasWhere = false;
        this.hasOrderBy = false;
        this.hasLimit = false;
    }

    // =========================================================================
    // SELECT clause
    // =========================================================================

    /**
     * Begins a SELECT statement with the specified columns.
     *
     * @param columns the column names to select (e.g., "id", "address", "price")
     *                Pass "*" for all columns.
     * @return this builder for method chaining
     */
    public SqlBuilder select(String... columns) {
        sql.append("SELECT ");
        if (columns == null || columns.length == 0) {
            sql.append("*");
        } else {
            // Anti-pattern: no validation of column names.
            // Callers sometimes pass expressions like "COUNT(*)" or "price * 1.05 AS adjusted_price"
            // which works by accident since we're just concatenating strings.
            sql.append(String.join(", ", columns));
        }
        return this;
    }

    // =========================================================================
    // FROM clause
    // =========================================================================

    /**
     * Appends the FROM clause with the specified table name.
     *
     * Anti-pattern: no validation or quoting of the table name.
     * If a table name contained a space or special character, the SQL would break.
     *
     * @param table the table name
     * @return this builder for method chaining
     */
    public SqlBuilder from(String table) {
        this.currentTable = table;
        sql.append(" FROM ").append(table);
        return this;
    }

    // =========================================================================
    // WHERE clause
    // =========================================================================

    /**
     * Appends a WHERE condition (or AND if WHERE already exists).
     * The condition is appended as-is, which means it can contain anything.
     *
     * Anti-pattern: accepts raw SQL fragments. Callers sometimes build the condition
     * string with user input concatenated in: where("city = '" + userInput + "'")
     * which is a SQL injection vector.
     *
     * @param condition the SQL condition (e.g., "status = 'ACTIVE'")
     * @return this builder for method chaining
     */
    public SqlBuilder where(String condition) {
        if (!hasWhere) {
            sql.append(" WHERE ").append(condition);
            hasWhere = true;
        } else {
            sql.append(" AND ").append(condition);
        }
        return this;
    }

    // =========================================================================
    // AND with field/operator/value (SQL INJECTION VULNERABILITY)
    // =========================================================================

    /**
     * Appends an AND condition with a field, operator, and value.
     *
     * CRITICAL ANTI-PATTERN - SQL INJECTION VULNERABILITY:
     * This method concatenates the value directly into the SQL string!
     *
     * Example of exploitation:
     *   builder.and("city", "=", "Denver' OR '1'='1")
     *   Produces: ... AND city = 'Denver' OR '1'='1'
     *   This bypasses the intended filter and returns ALL rows.
     *
     * Worse example:
     *   builder.and("city", "=", "'; DROP TABLE properties; --")
     *   Produces: ... AND city = ''; DROP TABLE properties; --'
     *
     * The correct approach would be to use parameterized queries (PreparedStatement)
     * or JdbcTemplate's parameter binding (? placeholders with Object[] args).
     *
     * @param field the column name
     * @param operator the comparison operator ("=", "!=", ">", "<", ">=", "<=", "LIKE")
     * @param value the value to compare against - CONCATENATED DIRECTLY INTO SQL!
     * @return this builder for method chaining
     */
    public SqlBuilder and(String field, String operator, Object value) {
        if (!hasWhere) {
            sql.append(" WHERE ");
            hasWhere = true;
        } else {
            sql.append(" AND ");
        }

        sql.append(field).append(" ").append(operator).append(" ");

        // Anti-pattern: DIRECT STRING CONCATENATION OF VALUES INTO SQL!
        // This is the textbook definition of SQL injection vulnerability.
        if (value instanceof String) {
            // "Escaping" with single quotes around the value, but no actual escaping
            // of single quotes within the value itself!
            sql.append("'").append(value).append("'");
        } else if (value == null) {
            // Anti-pattern: "= NULL" is not valid SQL; should be "IS NULL"
            // This silently produces incorrect queries.
            sql.append("NULL");
        } else {
            // Numbers, booleans, etc. - concatenated directly
            sql.append(value);
        }

        return this;
    }

    /**
     * Appends an IN clause for matching against multiple values.
     * Used for: searching properties by multiple statuses, filtering by multiple agent IDs.
     *
     * Anti-pattern: Same SQL injection vulnerability as and() - values are concatenated directly.
     *
     * @param field the column name
     * @param values the list of values for the IN clause
     * @return this builder for method chaining
     */
    public SqlBuilder in(String field, List<?> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }

        if (!hasWhere) {
            sql.append(" WHERE ");
            hasWhere = true;
        } else {
            sql.append(" AND ");
        }

        sql.append(field).append(" IN (");
        List<String> formatted = new ArrayList<>();
        for (Object val : values) {
            if (val instanceof String) {
                // Anti-pattern: no escaping of single quotes in values!
                formatted.add("'" + val + "'");
            } else {
                formatted.add(String.valueOf(val));
            }
        }
        sql.append(String.join(", ", formatted));
        sql.append(")");

        return this;
    }

    /**
     * Appends a LIKE condition for pattern matching.
     * Convenience method used by property and agent search features.
     *
     * Anti-pattern: Same SQL injection vulnerability. Also, does not escape
     * SQL LIKE wildcards (%, _) in the value, so user input containing these
     * characters alters the search pattern.
     *
     * @param field the column name
     * @param pattern the LIKE pattern (caller must include % wildcards)
     * @return this builder for method chaining
     */
    public SqlBuilder like(String field, String pattern) {
        return and(field, "LIKE", pattern);
    }

    // =========================================================================
    // ORDER BY clause
    // =========================================================================

    /**
     * Appends an ORDER BY clause.
     *
     * Anti-pattern: direction parameter is not validated. If a caller passes
     * user input as the direction, it could inject SQL.
     * e.g., orderBy("price", "ASC; DROP TABLE properties; --")
     *
     * @param field the column to sort by
     * @param direction "ASC" or "DESC"
     * @return this builder for method chaining
     */
    public SqlBuilder orderBy(String field, String direction) {
        if (!hasOrderBy) {
            sql.append(" ORDER BY ");
            hasOrderBy = true;
        } else {
            sql.append(", ");
        }
        // Anti-pattern: no validation that direction is actually "ASC" or "DESC"
        sql.append(field).append(" ").append(direction);
        return this;
    }

    // =========================================================================
    // LIMIT clause
    // =========================================================================

    /**
     * Appends a LIMIT clause to restrict the number of rows returned.
     * Used by: paginated property listings, search results, report previews.
     *
     * @param limit the maximum number of rows
     * @return this builder for method chaining
     */
    public SqlBuilder limit(int limit) {
        if (!hasLimit) {
            sql.append(" LIMIT ").append(limit);
            hasLimit = true;
        }
        return this;
    }

    /**
     * Appends LIMIT with OFFSET for pagination.
     * Used by: property search pagination, loan application list pagination.
     *
     * @param limit the maximum number of rows
     * @param offset the number of rows to skip
     * @return this builder for method chaining
     */
    public SqlBuilder limit(int limit, int offset) {
        if (!hasLimit) {
            sql.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
            hasLimit = true;
        }
        return this;
    }

    // =========================================================================
    // Build
    // =========================================================================

    /**
     * Returns the constructed SQL string.
     *
     * Anti-pattern: No validation that the SQL is well-formed. It's possible to call
     * build() with only a WHERE clause and no SELECT/FROM, producing invalid SQL.
     * The error only surfaces at query execution time (late failure).
     *
     * @return the SQL query string
     */
    public String build() {
        return sql.toString();
    }

    /**
     * Returns the SQL string. Alias for build() for debugging convenience.
     *
     * @return the SQL query string
     */
    @Override
    public String toString() {
        return build();
    }

    // =========================================================================
    // Static query execution (coupling concern)
    // =========================================================================

    /**
     * Executes a raw SQL query using Spring's JdbcTemplate and returns the results.
     *
     * Anti-patterns:
     * - Static method that accepts JdbcTemplate as a parameter (service locator smell).
     *   The JdbcTemplate should be injected into a repository/DAO class, not passed
     *   to a static utility method.
     * - Executes raw SQL strings with no parameterization (any SQL injection in the
     *   string built by this class will be executed directly against the database).
     * - Returns List<Map<String, Object>> which is weakly typed. Callers must cast
     *   values and handle column names as strings, leading to runtime errors.
     * - No error handling: SQLExceptions propagate as Spring DataAccessExceptions
     *   with no additional context about which query failed or why.
     *
     * @param jdbcTemplate the JdbcTemplate to execute the query with
     * @param sql the SQL query string to execute
     * @return list of rows, each represented as a column-name to value map
     */
    public static List<Map<String, Object>> executeQuery(JdbcTemplate jdbcTemplate, String sql) {
        // Anti-pattern: executes the raw SQL string directly with no parameterization!
        // Any SQL injection payloads in the sql string will be executed against the database.
        return jdbcTemplate.queryForList(sql);
    }

    /**
     * Convenience method that builds the SQL from this builder and executes it.
     *
     * @param jdbcTemplate the JdbcTemplate to execute with
     * @return list of result rows as maps
     */
    public List<Map<String, Object>> buildAndExecute(JdbcTemplate jdbcTemplate) {
        return executeQuery(jdbcTemplate, build());
    }

    // =========================================================================
    // Static factory methods for common queries (convenience anti-pattern)
    // =========================================================================

    /**
     * Creates a simple "SELECT * FROM table WHERE id = value" query.
     * Used as a shortcut by lazy callers who don't want to chain methods.
     *
     * Anti-pattern: Same SQL injection risk as and(). The id value is concatenated directly.
     *
     * @param table the table name
     * @param id the ID value
     * @return the SQL string
     */
    public static String findById(String table, Object id) {
        SqlBuilder builder = new SqlBuilder();
        builder.select("*").from(table).and("id", "=", id);
        return builder.build();
    }

    /**
     * Creates a "SELECT * FROM table" query with optional ordering.
     *
     * @param table the table name
     * @param orderByField the field to order by (can be null for no ordering)
     * @param direction "ASC" or "DESC" (ignored if orderByField is null)
     * @return the SQL string
     */
    public static String findAll(String table, String orderByField, String direction) {
        SqlBuilder builder = new SqlBuilder();
        builder.select("*").from(table);
        if (orderByField != null && !orderByField.isEmpty()) {
            builder.orderBy(orderByField, direction != null ? direction : "ASC");
        }
        return builder.build();
    }
}
