package com.pricingsim.validation;

import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.pricing.PricingPolicy;

/** One validation check. The runner assigns the check ID ({@code PK-001} …) from the category and order. */
public interface ValidationCheck {

    CheckCategory category();

    /** Main table checked (rows_checked counts it). */
    String table();

    String description();

    /** Violating IDs (all of them, any order). */
    List<String> violations(JdbcTemplate jdbc, PricingPolicy policy);

    /**
     * A check written as SQL: {@code violationSql} selects one column {@code id} per violating row. Counting and
     * sampling happen in the database, so a check over millions of rows never ships them to Java.
     */
    record Sql(CheckCategory category, String table, String description, String violationSql)
            implements ValidationCheck {

        public Sql {
            Objects.requireNonNull(violationSql, "violationSql");
        }

        @Override
        public List<String> violations(JdbcTemplate jdbc, PricingPolicy policy) {
            throw new UnsupportedOperationException("SQL checks are counted in the database");
        }

        public long count(JdbcTemplate jdbc) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM (" + violationSql + ") v", Long.class);
        }

        public List<String> sample(JdbcTemplate jdbc, int n) {
            return jdbc.queryForList("SELECT id FROM (" + violationSql + ") v ORDER BY id LIMIT " + n, String.class);
        }
    }

    /** A check computed in Java (coverage arithmetic, business-rule detectors). */
    interface Computed extends ValidationCheck {
    }
}
