package com.pricingsim.persistence;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC row maps with driver-independent value types: timestamps become UTC {@link OffsetDateTime}, dates become
 * {@link java.time.LocalDate} (PostgreSQL and H2 return different JDBC types for the same column).
 */
public final class Rows {

    private Rows() {
    }

    public static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), value(v)));
        return out;
    }

    public static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        return rows.stream().map(Rows::normalize).toList();
    }

    static Object value(Object v) {
        if (v instanceof Timestamp t) {
            return OffsetDateTime.ofInstant(t.toInstant(), ZoneOffset.UTC);
        }
        if (v instanceof OffsetDateTime o) {
            return o.withOffsetSameInstant(ZoneOffset.UTC);
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return v;
    }
}
