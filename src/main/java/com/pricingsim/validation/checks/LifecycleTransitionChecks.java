package com.pricingsim.validation.checks;

import static com.pricingsim.validation.checks.RegistryChecks.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.pricingsim.validation.CheckCategory;
import com.pricingsim.validation.ValidationCheck;

/**
 * LIFECYCLE (PLAN.md §4.3, §10): every status event is an allowed transition that continues from the previous event
 * (monotonic timestamps), final statuses equal the last event, cancelled promotions are not applied after the
 * cancellation, and a superseded decision has a successor.
 */
public final class LifecycleTransitionChecks {

    private static final CheckCategory C = CheckCategory.LIFECYCLE;

    private LifecycleTransitionChecks() {
    }

    public static List<ValidationCheck> all() {
        List<ValidationCheck> out = new ArrayList<>();
        out.add(transitions("product_status_event", "sku_id", List.of("-:ACTIVE", "-:NEW", "NEW:ACTIVE",
                "ACTIVE:DISCONTINUED")));
        out.add(transitions("promotion_status_event", "promotion_id", List.of("-:DRAFT", "DRAFT:SCHEDULED",
                "SCHEDULED:ACTIVE", "ACTIVE:EXPIRED", "DRAFT:CANCELLED", "SCHEDULED:CANCELLED", "ACTIVE:CANCELLED")));
        out.add(transitions("price_decision_status_event", "decision_id", List.of("-:PROPOSED", "PROPOSED:APPROVED",
                "PROPOSED:ESCALATED", "ESCALATED:APPROVED", "APPROVED:APPLIED", "APPLIED:SUPERSEDED",
                "PROPOSED:REJECTED", "ESCALATED:REJECTED")));
        out.add(finalStatus("product", "sku_id", "product_status_event"));
        out.add(finalStatus("promotion", "promotion_id", "promotion_status_event"));
        out.add(finalStatus("price_decision", "decision_id", "price_decision_status_event"));
        out.add(sql(C, "product", "status DISCONTINUED exactly when discontinued_date is set",
                "SELECT sku_id AS id FROM product WHERE (status = 'DISCONTINUED') <> (discontinued_date IS NOT NULL)"));
        out.add(sql(C, "order_promotion", "No promotion applied to an order at or after its cancellation",
                "SELECT op.order_id || '|' || op.promotion_id AS id FROM order_promotion op JOIN customer_order o"
                        + " ON o.order_id = op.order_id JOIN promotion_status_event e ON e.promotion_id ="
                        + " op.promotion_id AND e.to_status = 'CANCELLED' WHERE op.application_status = 'APPLIED'"
                        + " AND e.event_ts <= o.order_ts"));
        out.add(sql(C, "price_decision_promotion", "No markdown applied by a decision at or after its cancellation",
                "SELECT b.decision_id || '|' || b.promotion_id AS id FROM price_decision_promotion b JOIN"
                        + " price_decision d ON d.decision_id = b.decision_id JOIN promotion_status_event e ON"
                        + " e.promotion_id = b.promotion_id AND e.to_status = 'CANCELLED' WHERE b.application_status ="
                        + " 'APPLIED' AND e.event_ts <= d.decision_ts"));
        out.add(sql(C, "price_decision", "A SUPERSEDED decision has a successor; only the latest per SKU is APPLIED",
                "SELECT d.decision_id AS id FROM price_decision d LEFT JOIN price_decision n ON"
                        + " n.previous_decision_id = d.decision_id WHERE (d.status = 'SUPERSEDED' AND n.decision_id IS"
                        + " NULL) OR (d.status = 'APPLIED' AND n.decision_id IS NOT NULL)"));
        out.add(sql(C, "price_decision", "previous_decision_id is null exactly for INITIAL decisions and points to"
                        + " the same SKU",
                "SELECT d.decision_id AS id FROM price_decision d LEFT JOIN price_decision p ON p.decision_id ="
                        + " d.previous_decision_id WHERE (d.trigger = 'INITIAL') <> (d.previous_decision_id IS NULL)"
                        + " OR (p.decision_id IS NOT NULL AND (p.sku_id <> d.sku_id"
                        + " OR p.effective_date >= d.effective_date))"));
        return out;
    }

    /** Allowed (from, to) pairs ({@code -} = null), continuity with the previous event, increasing timestamps. */
    private static ValidationCheck transitions(String table, String parent, List<String> allowed) {
        String pairs = allowed.stream().map(a -> {
            String[] ft = a.split(":");
            return ft[0].equals("-") ? "(from_status IS NULL AND to_status = '" + ft[1] + "')"
                    : "(from_status = '" + ft[0] + "' AND to_status = '" + ft[1] + "')";
        }).collect(Collectors.joining(" OR "));
        return sql(C, table, table + ": allowed transitions, each continuing from the previous event, timestamps"
                        + " increasing",
                "SELECT id FROM (SELECT event_id AS id, from_status, to_status, event_ts, LAG(to_status) OVER (PARTITION"
                        + " BY " + parent + " ORDER BY event_ts, event_id) AS prev_to, LAG(event_ts) OVER (PARTITION BY "
                        + parent + " ORDER BY event_ts, event_id) AS prev_ts, ROW_NUMBER() OVER (PARTITION BY " + parent
                        + " ORDER BY event_ts, event_id) AS rn FROM " + table + ") x WHERE NOT (" + pairs + ")"
                        + " OR (rn = 1 AND from_status IS NOT NULL) OR (rn > 1 AND (from_status IS NULL"
                        + " OR from_status <> prev_to OR event_ts <= prev_ts))");
    }

    private static ValidationCheck finalStatus(String table, String key, String events) {
        return sql(C, table, table + ".status equals the last " + events + ".to_status",
                "SELECT t." + key + " AS id FROM " + table + " t LEFT JOIN (SELECT " + key + ", to_status, ROW_NUMBER()"
                        + " OVER (PARTITION BY " + key + " ORDER BY event_ts DESC, event_id DESC) AS rn FROM " + events
                        + ") e ON e." + key + " = t." + key + " AND e.rn = 1 WHERE e.to_status IS NULL"
                        + " OR e.to_status <> t.status");
    }
}
