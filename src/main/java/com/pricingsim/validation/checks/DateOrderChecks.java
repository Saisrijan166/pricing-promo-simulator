package com.pricingsim.validation.checks;

import static com.pricingsim.validation.checks.RegistryChecks.sql;

import java.util.ArrayList;
import java.util.List;

import com.pricingsim.validation.CheckCategory;
import com.pricingsim.validation.ValidationCheck;

/** DATE_ORDER (PLAN.md §10): effective periods, windows, timestamps and the product active window. */
public final class DateOrderChecks {

    private static final CheckCategory C = CheckCategory.DATE_ORDER;

    private DateOrderChecks() {
    }

    public static List<ValidationCheck> all() {
        List<ValidationCheck> out = new ArrayList<>();
        out.add(sql(C, "cost_record", "Cost periods per SKU have no gap or overlap (effective_to = next effective_from;"
                        + " null only on the latest)",
                "SELECT id FROM (SELECT cost_record_id AS id, effective_to, LEAD(effective_from) OVER (PARTITION BY"
                        + " sku_id ORDER BY effective_from) AS next_from FROM cost_record) x WHERE (next_from IS NULL"
                        + " AND effective_to IS NOT NULL) OR (next_from IS NOT NULL AND (effective_to IS NULL OR"
                        + " effective_to <> next_from))"));
        out.add(sql(C, "cost_record", "effective_from < effective_to",
                "SELECT cost_record_id AS id FROM cost_record WHERE effective_to IS NOT NULL"
                        + " AND effective_to <= effective_from"));
        out.add(sql(C, "promotion", "Promotion start_date <= end_date",
                "SELECT promotion_id AS id FROM promotion WHERE start_date > end_date"));
        out.add(sql(C, "promotion_rule", "Rule effective window lies inside its promotion window",
                "SELECT r.rule_id AS id FROM promotion_rule r JOIN promotion p ON p.promotion_id = r.promotion_id"
                        + " WHERE r.effective_from < p.start_date OR r.effective_to > p.end_date"
                        + " OR r.effective_from > r.effective_to"));
        out.add(sql(C, "promotion", "Promotion created before its start date",
                "SELECT promotion_id AS id FROM promotion WHERE CAST(created_at AS DATE) >= start_date"));
        out.add(sql(C, "promotion_product", "Promotion SKU added no later than the promotion start",
                "SELECT pp.promotion_id || '|' || pp.sku_id AS id FROM promotion_product pp JOIN promotion p"
                        + " ON p.promotion_id = pp.promotion_id WHERE CAST(pp.added_at AS DATE) > p.start_date"));
        out.add(sql(C, "product", "Product created no later than launch, discontinued after launch",
                "SELECT sku_id AS id FROM product WHERE CAST(created_at AS DATE) > launch_date"
                        + " OR (discontinued_date IS NOT NULL AND discontinued_date <= launch_date)"));
        out.add(sql(C, "competitor_observation", "ingested_at >= source_captured_at",
                "SELECT observation_id AS id FROM competitor_observation WHERE ingested_at < source_captured_at"));
        out.add(sql(C, "customer_order", "order_date = UTC date of order_ts",
                "SELECT order_id AS id FROM customer_order WHERE order_date <> CAST(order_ts AS DATE)"));
        out.add(activeWindow("price_history", "price_history_id", "price_date"));
        out.add(activeWindow("inventory_snapshot", "inventory_snapshot_id", "snapshot_date"));
        out.add(activeWindow("customer_order", "order_id", "order_date"));
        out.add(activeWindow("price_decision", "decision_id", "effective_date"));
        return out;
    }

    /** Rows dated before launch or on/after discontinuation (no orders or decisions after discontinuation). */
    private static ValidationCheck activeWindow(String table, String id, String dateColumn) {
        return sql(C, table, table + "." + dateColumn + " inside the product active window [launch, discontinued)",
                "SELECT x." + id + " AS id FROM " + table + " x JOIN product p ON p.sku_id = x.sku_id WHERE x."
                        + dateColumn + " < p.launch_date OR (p.discontinued_date IS NOT NULL AND x." + dateColumn
                        + " >= p.discontinued_date)");
    }
}
