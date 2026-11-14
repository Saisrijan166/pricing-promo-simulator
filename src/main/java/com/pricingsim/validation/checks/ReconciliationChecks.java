package com.pricingsim.validation.checks;

import static com.pricingsim.validation.checks.RegistryChecks.sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.pricing.DemandSignalCalculator;
import com.pricingsim.pricing.GuardrailCalculator;
import com.pricingsim.pricing.PricingPolicy;
import com.pricingsim.validation.CheckCategory;
import com.pricingsim.validation.ValidationCheck;

/**
 * RECONCILIATION (PLAN.md §10): every derived value equals its recomputation from source records. Arithmetic that is
 * stored rounded (percentages, the shelf price from a 4-dp markdown) is compared with the rounding tolerance; exact
 * sums are compared exactly.
 */
public final class ReconciliationChecks {

    private static final CheckCategory C = CheckCategory.RECONCILIATION;

    private ReconciliationChecks() {
    }

    public static List<ValidationCheck> all() {
        List<ValidationCheck> out = new ArrayList<>();
        // cost_record
        out.add(sql(C, "cost_record", "landed_cost = unit_cost + freight_cost",
                "SELECT cost_record_id AS id FROM cost_record WHERE landed_cost <> unit_cost + freight_cost"));
        out.add(sql(C, "cost_record", "change_pct = landed / previous landed - 1 (4 dp), null on the first record",
                "SELECT id FROM (SELECT cost_record_id AS id, change_pct, landed_cost, LAG(landed_cost) OVER"
                        + " (PARTITION BY sku_id ORDER BY effective_from) AS prev FROM cost_record) x WHERE"
                        + " (prev IS NULL AND change_pct IS NOT NULL) OR (prev IS NOT NULL AND (change_pct IS NULL OR"
                        + " ABS(change_pct - (landed_cost / prev - 1)) > 0.00006))"));
        // inventory_snapshot
        out.add(sql(C, "inventory_snapshot", "closing = opening + receipts - sold + adjustment; lost = demand - sold;"
                        + " stockout_flag = (closing = 0 or lost > 0); 0 <= sold <= demand; adjustment <= 0",
                "SELECT inventory_snapshot_id AS id FROM inventory_snapshot WHERE closing_on_hand <> opening_on_hand"
                        + " + receipts_units - units_sold + adjustment_units OR lost_sales_units <> demand_units"
                        + " - units_sold OR stockout_flag <> (closing_on_hand = 0 OR lost_sales_units > 0)"
                        + " OR units_sold < 0 OR units_sold > demand_units OR adjustment_units > 0"
                        + " OR closing_on_hand < 0 OR on_order_units < 0"));
        out.add(sql(C, "inventory_snapshot", "opening_on_hand = previous day's closing_on_hand",
                "SELECT id FROM (SELECT inventory_snapshot_id AS id, opening_on_hand, LAG(closing_on_hand) OVER"
                        + " (PARTITION BY sku_id ORDER BY snapshot_date) AS prev FROM inventory_snapshot) x"
                        + " WHERE prev IS NOT NULL AND opening_on_hand <> prev"));
        out.add(sql(C, "inventory_snapshot", "units_sold = sum of FULFILLED order quantity for the SKU-day",
                "SELECT i.inventory_snapshot_id AS id FROM inventory_snapshot i LEFT JOIN (SELECT sku_id, order_date,"
                        + " SUM(quantity) AS qty FROM customer_order WHERE order_status = 'FULFILLED' GROUP BY sku_id,"
                        + " order_date) o ON o.sku_id = i.sku_id AND o.order_date = i.snapshot_date"
                        + " WHERE i.units_sold <> COALESCE(o.qty, 0)"));
        out.add(inventoryDaysOfCover());
        // customer_order
        out.add(sql(C, "customer_order", "gross = qty x unit_list_price; net = gross - discount > 0;"
                        + " gross_margin = net - qty x unit_landed_cost",
                "SELECT order_id AS id FROM customer_order WHERE gross_amount <> quantity * unit_list_price"
                        + " OR net_amount <> gross_amount - discount_amount OR net_amount <= 0"
                        + " OR gross_margin_amount <> net_amount - quantity * unit_landed_cost"));
        out.add(sql(C, "customer_order", "discount_amount = sum of APPLIED order_promotion discounts",
                "SELECT o.order_id AS id FROM customer_order o LEFT JOIN (SELECT order_id, SUM(discount_amount) AS d"
                        + " FROM order_promotion WHERE application_status = 'APPLIED' GROUP BY order_id) p"
                        + " ON p.order_id = o.order_id WHERE o.discount_amount <> COALESCE(p.d, 0)"));
        out.add(sql(C, "customer_order", "unit_list_price = price_history.list_price; unit_landed_cost ="
                        + " cost_record.landed_cost; same cost record as the day's price",
                "SELECT o.order_id AS id FROM customer_order o JOIN price_history ph ON ph.price_history_id ="
                        + " o.price_history_id JOIN cost_record c ON c.cost_record_id = o.cost_record_id WHERE"
                        + " o.unit_list_price <> ph.list_price OR o.unit_landed_cost <> c.landed_cost"
                        + " OR o.cost_record_id <> ph.cost_record_id"));
        out.add(sql(C, "order_promotion", "Rejected coupons carry a reason and no discount; applied ones no reason",
                "SELECT order_id || '|' || promotion_id AS id FROM order_promotion WHERE (application_status ="
                        + " 'REJECTED' AND (rejection_reason IS NULL OR discount_amount <> 0)) OR (application_status ="
                        + " 'APPLIED' AND rejection_reason IS NOT NULL)"));
        // price_history
        out.add(sql(C, "price_history", "previous_list_price = previous day's list_price (null on the first day)",
                "SELECT id FROM (SELECT price_history_id AS id, previous_list_price, LAG(list_price) OVER (PARTITION BY"
                        + " sku_id ORDER BY price_date) AS prev FROM price_history) x WHERE (prev IS NULL AND"
                        + " previous_list_price IS NOT NULL) OR (prev IS NOT NULL AND (previous_list_price IS NULL OR"
                        + " previous_list_price <> prev))"));
        out.add(sql(C, "price_history", "list and shelf price equal the source decision's final_list_price and"
                        + " expected_net_price; shelf <= list",
                "SELECT ph.price_history_id AS id FROM price_history ph LEFT JOIN price_decision d ON d.decision_id ="
                        + " ph.source_decision_id WHERE ph.shelf_price > ph.list_price OR (d.decision_id IS NOT NULL AND"
                        + " (ph.list_price <> d.final_list_price OR ph.shelf_price <> d.expected_net_price"
                        + " OR d.sku_id <> ph.sku_id OR d.effective_date > ph.price_date))"));
        out.add(sql(C, "price_history", "cost_record_id is the SKU's cost record for the month of price_date",
                "SELECT ph.price_history_id AS id FROM price_history ph JOIN cost_record c ON c.cost_record_id ="
                        + " ph.cost_record_id WHERE c.sku_id <> ph.sku_id OR c.cost_month <> TO_CHAR(ph.price_date,"
                        + " 'YYYY-MM')"));
        // price_decision
        out.add(sql(C, "price_decision", "landed_cost = cost record landed_cost; ceiling = MRP; cost record of the"
                        + " effective month",
                "SELECT d.decision_id AS id FROM price_decision d JOIN cost_record c ON c.cost_record_id ="
                        + " d.cost_record_id JOIN product p ON p.sku_id = d.sku_id WHERE d.landed_cost <> c.landed_cost"
                        + " OR d.ceiling_price <> p.mrp OR c.sku_id <> d.sku_id"
                        + " OR c.cost_month <> TO_CHAR(d.effective_date, 'YYYY-MM')"));
        out.add(sql(C, "price_decision", "competitor fresh / stale counts = USED / EXCLUDED_STALE bridge rows",
                "SELECT d.decision_id AS id FROM price_decision d LEFT JOIN (SELECT decision_id, SUM(CASE WHEN usage ="
                        + " 'USED' THEN 1 ELSE 0 END) AS used, SUM(CASE WHEN usage = 'EXCLUDED_STALE' THEN 1 ELSE 0"
                        + " END) AS stale FROM price_decision_observation GROUP BY decision_id) b ON b.decision_id ="
                        + " d.decision_id WHERE d.competitor_fresh_count <> COALESCE(b.used, 0)"
                        + " OR d.competitor_stale_count <> COALESCE(b.stale, 0)"));
        out.add(sql(C, "price_decision", "expected_net = final x (1 - markdown) within rounding; decision_type"
                        + " matches the sign of final - prev",
                "SELECT decision_id AS id FROM price_decision WHERE ABS(expected_net_price - final_list_price * (1 -"
                        + " markdown_discount_pct)) > final_list_price * 0.00005 + 0.005 OR expected_net_price >"
                        + " final_list_price OR decision_type <> CASE WHEN prev_list_price IS NULL THEN 'HOLD' WHEN"
                        + " final_list_price > prev_list_price THEN 'INCREASE' WHEN final_list_price < prev_list_price"
                        + " THEN 'DECREASE' ELSE 'HOLD' END"));
        out.add(sql(C, "price_decision", "required_margin = min_promo_margin when a markdown applied, else min_margin",
                "SELECT d.decision_id AS id FROM price_decision d JOIN product p ON p.sku_id = d.sku_id WHERE"
                        + " d.required_margin_pct <> CASE WHEN d.expected_net_price < d.final_list_price"
                        + " THEN p.min_promo_margin_pct ELSE p.min_margin_pct END"));
        out.add(decisionFloorAndSignals());
        return out;
    }

    /** Floor, velocity and days of cover recomputed with the engine's own calculators. */
    private static ValidationCheck decisionFloorAndSignals() {
        return new ValidationCheck.Computed() {
            @Override
            public CheckCategory category() {
                return C;
            }

            @Override
            public String table() {
                return "price_decision";
            }

            @Override
            public String description() {
                return "floor_price, velocity_ratio and days_of_cover recomputed from cost, product and sales history";
            }

            @Override
            public List<String> violations(JdbcTemplate jdbc, PricingPolicy policy) {
                List<String> bad = new ArrayList<>();
                jdbc.query("SELECT d.decision_id, d.floor_price, d.velocity_ratio, d.days_of_cover, d.on_hand_units,"
                        + " d.demand_7d_units, d.demand_28d_units, c.landed_cost, p.min_margin_pct, p.map_price, "
                        + NoLookAheadChecks.days(7) + " AS n7, " + NoLookAheadChecks.days(28) + " AS n28"
                        + " FROM price_decision d JOIN cost_record c ON c.cost_record_id = d.cost_record_id"
                        + " JOIN product p ON p.sku_id = d.sku_id", rs -> {
                            BigDecimal floor = GuardrailCalculator.floor(rs.getBigDecimal("landed_cost"),
                                    rs.getBigDecimal("min_margin_pct"), rs.getBigDecimal("map_price"));
                            DemandSignalCalculator.Signals s = DemandSignalCalculator.signals(rs.getInt("on_hand_units"),
                                    rs.getInt("demand_7d_units"), rs.getInt("n7"), rs.getInt("demand_28d_units"),
                                    rs.getInt("n28"));
                            if (floor.compareTo(rs.getBigDecimal("floor_price")) != 0
                                    || s.velocityRatio().compareTo(rs.getBigDecimal("velocity_ratio")) != 0
                                    || s.daysOfCover().compareTo(rs.getBigDecimal("days_of_cover")) != 0) {
                                bad.add(rs.getString("decision_id"));
                            }
                        });
                return bad;
            }
        };
    }

    /** Inventory days of cover over the SKU's trailing 28 snapshot rows (the generator's own formula). */
    private static ValidationCheck inventoryDaysOfCover() {
        return new ValidationCheck.Computed() {
            @Override
            public CheckCategory category() {
                return C;
            }

            @Override
            public String table() {
                return "inventory_snapshot";
            }

            @Override
            public String description() {
                return "days_of_cover = closing / max(average units sold over the trailing 28 rows, 0.1)";
            }

            @Override
            public List<String> violations(JdbcTemplate jdbc, PricingPolicy policy) {
                List<String> bad = new ArrayList<>();
                String[] sku = {null};
                Deque<Integer> window = new ArrayDeque<>();
                int[] sum = {0};
                jdbc.query("SELECT inventory_snapshot_id, sku_id, units_sold, closing_on_hand, days_of_cover"
                        + " FROM inventory_snapshot ORDER BY sku_id, snapshot_date", rs -> {
                            if (!rs.getString("sku_id").equals(sku[0])) {
                                sku[0] = rs.getString("sku_id");
                                window.clear();
                                sum[0] = 0;
                            }
                            if (window.size() == 28) {
                                sum[0] -= window.removeFirst();
                            }
                            int sold = rs.getInt("units_sold");
                            window.addLast(sold);
                            sum[0] += sold;
                            BigDecimal expected = DemandSignalCalculator.daysOfCover(rs.getInt("closing_on_hand"),
                                    sum[0], window.size());
                            if (expected.compareTo(rs.getBigDecimal("days_of_cover")) != 0) {
                                bad.add(rs.getString("inventory_snapshot_id"));
                            }
                        });
                return bad;
            }
        };
    }
}
