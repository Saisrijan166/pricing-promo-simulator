package com.pricingsim.detection.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.DetectedException;
import com.pricingsim.domain.enums.IssueType;

/**
 * Operational exceptions (PLAN.md §10, §13 exception queue): situations that are valid but need attention.
 * <ul>
 *   <li>COMPETITOR_DATA_STALE: decisions that had competitor data but none of it fresh;</li>
 *   <li>GUARDRAIL_CONFLICT_ESCALATED: decisions whose floor exceeded MRP;</li>
 *   <li>STOCKOUT: SKU-days with {@code stockout_flag};</li>
 *   <li>COUPON_REJECTED: coupons a customer presented that the stack resolver rejected.</li>
 * </ul>
 */
public final class OperationalExceptionCollector {

    private OperationalExceptionCollector() {
    }

    public static List<DetectedException> collect(JdbcTemplate jdbc) {
        List<DetectedException> out = new ArrayList<>();
        jdbc.query("SELECT decision_id, sku_id, effective_date, competitor_stale_count, final_list_price, reason_codes"
                + " FROM price_decision WHERE reason_codes LIKE '%COMPETITOR_DATA_STALE%'"
                + " OR reason_codes LIKE '%GUARDRAIL_CONFLICT_ESCALATED%' ORDER BY decision_id", rs -> {
                    String codes = rs.getString("reason_codes");
                    LocalDate day = rs.getObject("effective_date", LocalDate.class);
                    if (codes.contains("COMPETITOR_DATA_STALE")) {
                        out.add(DetectedException.of(IssueType.COMPETITOR_DATA_STALE, "price_decision",
                                rs.getString("decision_id"), rs.getString("sku_id"), day, List.of(),
                                Integer.toString(rs.getInt("competitor_stale_count")), "0",
                                "all considered competitor observations older than 72 h"));
                    }
                    if (codes.contains("GUARDRAIL_CONFLICT_ESCALATED")) {
                        out.add(DetectedException.of(IssueType.GUARDRAIL_CONFLICT_ESCALATED, "price_decision",
                                rs.getString("decision_id"), rs.getString("sku_id"), day, List.of(),
                                rs.getBigDecimal("final_list_price").toPlainString(), null,
                                "floor above MRP; priced at the floor and escalated"));
                    }
                });
        jdbc.query("SELECT inventory_snapshot_id, sku_id, snapshot_date, closing_on_hand, lost_sales_units"
                + " FROM inventory_snapshot WHERE stockout_flag = TRUE ORDER BY inventory_snapshot_id", rs -> {
                    out.add(DetectedException.of(IssueType.STOCKOUT, "inventory_snapshot",
                            rs.getString("inventory_snapshot_id"), rs.getString("sku_id"),
                            rs.getObject("snapshot_date", LocalDate.class), List.of(),
                            Integer.toString(rs.getInt("lost_sales_units")), "0",
                            "closing " + rs.getInt("closing_on_hand") + ", lost sales " + rs.getInt("lost_sales_units")));
                });
        jdbc.query("SELECT op.order_id, op.promotion_id, op.rejection_reason, o.sku_id, o.order_date FROM"
                + " order_promotion op JOIN customer_order o ON o.order_id = op.order_id WHERE op.application_status ="
                + " 'REJECTED' AND op.coupon_presented = TRUE ORDER BY op.order_id, op.promotion_id", rs -> {
                    out.add(DetectedException.of(IssueType.COUPON_REJECTED, "order_promotion",
                            rs.getString("order_id") + "|" + rs.getString("promotion_id"), rs.getString("sku_id"),
                            rs.getObject("order_date", LocalDate.class), List.of(rs.getString("promotion_id")),
                            rs.getString("rejection_reason"), null, "coupon rejected: " + rs.getString("rejection_reason")));
                });
        return out;
    }
}
