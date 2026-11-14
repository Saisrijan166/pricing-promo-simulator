package com.pricingsim.validation.checks;

import static com.pricingsim.validation.checks.RegistryChecks.sql;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.pricing.PricingPolicy;
import com.pricingsim.validation.CheckCategory;
import com.pricingsim.validation.ValidationCheck;

/**
 * COVERAGE (PLAN.md §10). The simulation window is taken from the data (first and last price_history date), and each
 * product is active from {@code max(launch, window start)} to {@code min(discontinued − 1, window end)}.
 */
public final class CoverageChecks {

    private static final CheckCategory C = CheckCategory.COVERAGE;

    private CoverageChecks() {
    }

    public static List<ValidationCheck> all() {
        List<ValidationCheck> out = new ArrayList<>();
        out.add(daily("price_history", "price_date"));
        out.add(daily("inventory_snapshot", "snapshot_date"));
        out.add(monthlyCost());
        out.add(sql(C, "promotion", "Every promotion has at least one rule and one SKU",
                "SELECT p.promotion_id AS id FROM promotion p WHERE NOT EXISTS (SELECT 1 FROM promotion_rule r WHERE"
                        + " r.promotion_id = p.promotion_id) OR NOT EXISTS (SELECT 1 FROM promotion_product pp WHERE"
                        + " pp.promotion_id = p.promotion_id)"));
        out.add(sql(C, "product", "Every product has exactly one INITIAL decision, on its first active day",
                "SELECT p.sku_id AS id FROM product p LEFT JOIN (SELECT sku_id, COUNT(*) AS n, MIN(effective_date) AS"
                        + " d FROM price_decision WHERE trigger = 'INITIAL' GROUP BY sku_id) i ON i.sku_id = p.sku_id"
                        + " LEFT JOIN (SELECT sku_id, MIN(price_date) AS first_day FROM price_history GROUP BY sku_id) f"
                        + " ON f.sku_id = p.sku_id WHERE i.n IS NULL OR i.n <> 1 OR f.first_day IS NULL"
                        + " OR i.d <> f.first_day"));
        return out;
    }

    private record Window(LocalDate start, LocalDate end) {
        static Window of(JdbcTemplate jdbc) {
            return jdbc.queryForObject("SELECT MIN(price_date) AS s, MAX(price_date) AS e FROM price_history",
                    (rs, i) -> new Window(rs.getObject("s", LocalDate.class), rs.getObject("e", LocalDate.class)));
        }

        LocalDate from(LocalDate launch) {
            return launch.isAfter(start) ? launch : start;
        }

        LocalDate to(LocalDate discontinued) {
            return discontinued != null && discontinued.minusDays(1).isBefore(end) ? discontinued.minusDays(1) : end;
        }
    }

    /** One row per SKU per active day: count, first and last date match the active window. */
    private static ValidationCheck daily(String table, String dateColumn) {
        return new ValidationCheck.Computed() {
            @Override
            public CheckCategory category() {
                return C;
            }

            @Override
            public String table() {
                return table;
            }

            @Override
            public String description() {
                return "One " + table + " row per SKU per active day";
            }

            @Override
            public List<String> violations(JdbcTemplate jdbc, PricingPolicy policy) {
                Window w = Window.of(jdbc);
                List<String> bad = new ArrayList<>();
                if (w == null || w.start() == null) {
                    return bad;
                }
                jdbc.query("SELECT p.sku_id, p.launch_date, p.discontinued_date, x.n, x.first_day, x.last_day FROM"
                        + " product p LEFT JOIN (SELECT sku_id, COUNT(*) AS n, MIN(" + dateColumn + ") AS first_day,"
                        + " MAX(" + dateColumn + ") AS last_day FROM " + table + " GROUP BY sku_id) x ON x.sku_id ="
                        + " p.sku_id ORDER BY p.sku_id", rs -> {
                            LocalDate from = w.from(rs.getObject("launch_date", LocalDate.class));
                            LocalDate to = w.to(rs.getObject("discontinued_date", LocalDate.class));
                            long expected = to.isBefore(from) ? 0 : ChronoUnit.DAYS.between(from, to) + 1;
                            long n = rs.getLong("n");
                            boolean ok = n == expected && (expected == 0
                                    || (from.equals(rs.getObject("first_day", LocalDate.class))
                                    && to.equals(rs.getObject("last_day", LocalDate.class))));
                            if (!ok) {
                                bad.add(rs.getString("sku_id"));
                            }
                        });
                return bad;
            }
        };
    }

    /** One cost record per SKU per active month. */
    private static ValidationCheck monthlyCost() {
        return new ValidationCheck.Computed() {
            @Override
            public CheckCategory category() {
                return C;
            }

            @Override
            public String table() {
                return "cost_record";
            }

            @Override
            public String description() {
                return "One cost record per SKU per active month";
            }

            @Override
            public List<String> violations(JdbcTemplate jdbc, PricingPolicy policy) {
                Window w = Window.of(jdbc);
                List<String> bad = new ArrayList<>();
                if (w == null || w.start() == null) {
                    return bad;
                }
                jdbc.query("SELECT p.sku_id, p.launch_date, p.discontinued_date, x.n, x.first_month, x.last_month"
                        + " FROM product p LEFT JOIN (SELECT sku_id, COUNT(*) AS n, MIN(cost_month) AS first_month,"
                        + " MAX(cost_month) AS last_month FROM cost_record GROUP BY sku_id) x ON x.sku_id = p.sku_id"
                        + " ORDER BY p.sku_id", rs -> {
                            YearMonth from = YearMonth.from(w.from(rs.getObject("launch_date", LocalDate.class)));
                            YearMonth to = YearMonth.from(w.to(rs.getObject("discontinued_date", LocalDate.class)));
                            long expected = from.until(to, ChronoUnit.MONTHS) + 1;
                            boolean ok = rs.getLong("n") == expected && from.toString().equals(rs.getString("first_month"))
                                    && to.toString().equals(rs.getString("last_month"));
                            if (!ok) {
                                bad.add(rs.getString("sku_id"));
                            }
                        });
                return bad;
            }
        };
    }
}
