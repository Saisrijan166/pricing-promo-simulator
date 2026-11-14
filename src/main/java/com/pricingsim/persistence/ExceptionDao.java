package com.pricingsim.persistence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

/** {@code detected_exception} queries with filters and stable paging (PLAN.md §13 {@code GET /exceptions}). */
public final class ExceptionDao {

    /** Optional filters; null means "any". Dates are inclusive. */
    public record Filter(String category, String issueType, String skuId, LocalDate from, LocalDate to) {
    }

    private final JdbcTemplate jdbc;

    public ExceptionDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public long count(Filter f) {
        List<Object> args = new ArrayList<>();
        return jdbc.queryForObject("SELECT COUNT(*) FROM detected_exception" + where(f, args), Long.class,
                args.toArray());
    }

    /** One page, sorted by event_date then exception_id (a total order, so pages never overlap). */
    public List<Map<String, Object>> page(Filter f, int page, int size) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT * FROM detected_exception" + where(f, args)
                + " ORDER BY event_date, exception_id LIMIT ? OFFSET ?";
        args.add(size);
        args.add((long) page * size);
        return Rows.normalize(jdbc.queryForList(sql, args.toArray()));
    }

    /** Findings about a set of entities (for the explain view). */
    public List<Map<String, Object>> forEntities(List<String> entityIds) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        String marks = String.join(", ", java.util.Collections.nCopies(entityIds.size(), "?"));
        return Rows.normalize(jdbc.queryForList("SELECT * FROM detected_exception WHERE entity_id IN (" + marks
                + ") ORDER BY event_date, exception_id", entityIds.toArray()));
    }

    private static String where(Filter f, List<Object> args) {
        List<String> clauses = new ArrayList<>();
        if (f.category() != null) {
            clauses.add("category = ?");
            args.add(f.category());
        }
        if (f.issueType() != null) {
            clauses.add("issue_type = ?");
            args.add(f.issueType());
        }
        if (f.skuId() != null) {
            clauses.add("sku_id = ?");
            args.add(f.skuId());
        }
        if (f.from() != null) {
            clauses.add("event_date >= ?");
            args.add(f.from());
        }
        if (f.to() != null) {
            clauses.add("event_date <= ?");
            args.add(f.to());
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }
}
