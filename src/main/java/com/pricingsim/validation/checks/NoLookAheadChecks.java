package com.pricingsim.validation.checks;

import static com.pricingsim.validation.checks.RegistryChecks.sql;

import java.util.List;

import com.pricingsim.validation.CheckCategory;
import com.pricingsim.validation.ValidationCheck;

/** NO_LOOK_AHEAD (PLAN.md §10): a decision uses only data visible before its decision_ts. */
public final class NoLookAheadChecks {

    private static final CheckCategory C = CheckCategory.NO_LOOK_AHEAD;

    private NoLookAheadChecks() {
    }

    public static List<ValidationCheck> all() {
        return List.of(
                sql(C, "price_decision_observation", "Considered observations were captured and ingested by decision_ts",
                        "SELECT b.decision_id || '|' || b.observation_id AS id FROM price_decision_observation b"
                                + " JOIN price_decision d ON d.decision_id = b.decision_id JOIN competitor_observation o"
                                + " ON o.observation_id = b.observation_id WHERE o.source_captured_at > d.decision_ts"
                                + " OR o.ingested_at > d.decision_ts"),
                sql(C, "price_decision", "decision_ts falls on the effective date (decisions take effect the same day)",
                        "SELECT decision_id AS id FROM price_decision WHERE CAST(decision_ts AS DATE) <> effective_date"),
                sql(C, "price_decision", "demand_7d / demand_28d = units sold in the 7 / 28 days before effective_date",
                        "SELECT d.decision_id AS id FROM price_decision d WHERE d.demand_7d_units <> " + sold(7)
                                + " OR d.demand_28d_units <> " + sold(28)),
                sql(C, "price_decision", "on_hand_units = opening + receipts of the effective date",
                        "SELECT d.decision_id AS id FROM price_decision d JOIN inventory_snapshot i ON i.sku_id = d.sku_id"
                                + " AND i.snapshot_date = d.effective_date WHERE d.on_hand_units <> i.opening_on_hand"
                                + " + i.receipts_units"));
    }

    static String sold(int days) {
        return "(SELECT COALESCE(SUM(i.units_sold), 0) FROM inventory_snapshot i WHERE i.sku_id = d.sku_id"
                + " AND i.snapshot_date >= d.effective_date - INTERVAL '" + days + "' DAY"
                + " AND i.snapshot_date < d.effective_date)";
    }

    static String days(int days) {
        return "(SELECT COUNT(*) FROM inventory_snapshot i WHERE i.sku_id = d.sku_id"
                + " AND i.snapshot_date >= d.effective_date - INTERVAL '" + days + "' DAY"
                + " AND i.snapshot_date < d.effective_date)";
    }
}
