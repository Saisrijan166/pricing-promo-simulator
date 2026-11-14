package com.pricingsim.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

import javax.sql.DataSource;

/** The two databases the pipeline runs on: PostgreSQL (Docker) and H2 in PostgreSQL mode (tests). */
public enum Dialect {
    POSTGRES,
    H2;

    public static Dialect of(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (product.contains("postgres")) {
                return POSTGRES;
            }
            if (product.contains("h2")) {
                return H2;
            }
            throw new IllegalStateException("Unsupported database: " + product);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot reach the database: " + e.getMessage(), e);
        }
    }

    /** SQL boolean expression: {@code column} matches the regular expression {@code regex}. */
    public String regexMatch(String column, String regex) {
        String literal = "'" + regex.replace("'", "''") + "'";
        return this == POSTGRES ? column + " ~ " + literal : "REGEXP_LIKE(" + column + ", " + literal + ")";
    }
}
