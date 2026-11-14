package com.pricingsim.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Realism metrics with their PLAN.md §7.5 bands, plus descriptive statistics, computed from the loaded data. A metric
 * outside its band is WARN.
 */
public final class DatasetMetricsCalculator {

    private DatasetMetricsCalculator() {
    }

    /** One realism metric. */
    public record Metric(String name, double value, double bandLow, double bandHigh, String unit) {
        public String status() {
            return value >= bandLow && value <= bandHigh ? "OK" : "WARN";
        }
    }

    public static List<Metric> realism(JdbcTemplate jdbc) {
        List<Metric> out = new ArrayList<>();
        long skuDays = count(jdbc, "SELECT COUNT(*) FROM inventory_snapshot");
        out.add(new Metric("stockout_sku_day_rate",
                ratio(count(jdbc, "SELECT COUNT(*) FROM inventory_snapshot WHERE stockout_flag = TRUE"), skuDays),
                0.02, 0.06, "share of SKU-days"));
        out.add(new Metric("observations_stale_at_decision",
                ratio(count(jdbc, "SELECT COUNT(*) FROM price_decision_observation WHERE usage = 'EXCLUDED_STALE'"),
                        count(jdbc, "SELECT COUNT(*) FROM price_decision_observation")),
                0.05, 0.15, "share of considered observations"));
        long prc = count(jdbc, "SELECT COUNT(*) FROM price_history");
        out.add(new Metric("sku_days_under_markdown",
                ratio(count(jdbc, "SELECT COUNT(*) FROM price_history WHERE shelf_price < list_price"), prc),
                0.15, 0.30, "share of SKU-days"));
        out.add(new Metric("coupon_presentations_rejected",
                ratio(count(jdbc, "SELECT COUNT(*) FROM order_promotion WHERE coupon_presented = TRUE"
                                + " AND application_status = 'REJECTED'"),
                        count(jdbc, "SELECT COUNT(*) FROM order_promotion WHERE coupon_presented = TRUE")),
                0.10, 0.30, "share of presented coupons"));
        BigDecimal[] margin = jdbc.queryForObject("SELECT COALESCE(SUM(gross_margin_amount), 0), COALESCE(SUM("
                        + "net_amount), 0) FROM customer_order WHERE order_status = 'FULFILLED'",
                (rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)});
        out.add(new Metric("average_realised_gross_margin",
                margin[1].signum() == 0 ? 0 : margin[0].doubleValue() / margin[1].doubleValue(), 0.15, 0.40,
                "gross margin / net revenue"));
        long changes = count(jdbc, "SELECT COUNT(*) FROM price_history WHERE previous_list_price IS NOT NULL"
                + " AND list_price <> previous_list_price");
        out.add(new Metric("list_price_changes_per_sku_month", prc == 0 ? 0 : changes / (prc / 30.4375), 0.5, 3.0,
                "changes per SKU per 30.4 days"));
        List<Long> units = jdbc.queryForList("SELECT SUM(units_sold) AS u FROM inventory_snapshot GROUP BY sku_id"
                + " ORDER BY u DESC", Long.class);
        long total = units.stream().mapToLong(Long::longValue).sum();
        long top = units.stream().limit(units.size() / 5).mapToLong(Long::longValue).sum();
        out.add(new Metric("top_20pct_skus_share_of_units", ratio(top, total), 0.55, 0.85, "share of units"));
        return out;
    }

    /** Decision mix: counts by trigger, decision type, status and primary reason. */
    public static Map<String, Map<String, Long>> decisionMix(JdbcTemplate jdbc) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (String column : List.of("trigger", "decision_type", "status", "decision_source", "primary_reason_code")) {
            Map<String, Long> m = new LinkedHashMap<>();
            jdbc.query("SELECT " + column + " AS k, COUNT(*) AS n FROM price_decision GROUP BY " + column
                    + " ORDER BY " + column, rs -> {
                        m.put(rs.getString("k"), rs.getLong("n"));
                    });
            out.put(column, m);
        }
        return out;
    }

    /** Units, net revenue and gross margin of FULFILLED orders by category. */
    public static Map<String, BigDecimal[]> byCategory(JdbcTemplate jdbc) {
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        jdbc.query("SELECT p.category, SUM(o.quantity) AS units, SUM(o.net_amount) AS revenue,"
                + " SUM(o.gross_margin_amount) AS margin FROM customer_order o JOIN product p ON p.sku_id = o.sku_id"
                + " WHERE o.order_status = 'FULFILLED' GROUP BY p.category ORDER BY p.category", rs -> {
                    out.put(rs.getString("category"), new BigDecimal[] {BigDecimal.valueOf(rs.getLong("units")),
                            rs.getBigDecimal("revenue"), rs.getBigDecimal("margin")});
                });
        return out;
    }

    static long count(JdbcTemplate jdbc, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    static double ratio(long a, long b) {
        return b == 0 ? 0 : a / (double) b;
    }
}
