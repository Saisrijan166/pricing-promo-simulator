package com.pricingsim.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only queries behind {@code /decisions} and {@code /products/{sku}/price-history} (PLAN.md §13). */
public final class DecisionQueryDao {

    private final JdbcTemplate jdbc;

    public DecisionQueryDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<Map<String, Object>> decision(String decisionId) {
        return jdbc.queryForList("SELECT * FROM price_decision WHERE decision_id = ?", decisionId).stream()
                .findFirst().map(Rows::normalize);
    }

    public Map<String, Object> costRecord(String costRecordId) {
        return Rows.normalize(jdbc.queryForMap("SELECT * FROM cost_record WHERE cost_record_id = ?", costRecordId));
    }

    public Map<String, Object> product(String skuId) {
        return Rows.normalize(jdbc.queryForMap("SELECT sku_id, product_name, brand, category, mrp, map_price,"
                + " min_margin_pct, min_promo_margin_pct, price_position, status FROM product WHERE sku_id = ?", skuId));
    }

    /** Observations the decision considered, with usage and age. */
    public List<Map<String, Object>> observations(String decisionId) {
        return Rows.normalize(jdbc.queryForList("SELECT b.observation_id, o.competitor, o.observed_price,"
                + " o.source_captured_at, o.ingested_at, o.source_type, o.match_confidence, b.usage, b.age_hours FROM"
                + " price_decision_observation b JOIN competitor_observation o ON o.observation_id = b.observation_id"
                + " WHERE b.decision_id = ? ORDER BY b.observation_id", decisionId));
    }

    /** Markdowns the decision considered, with outcome and their rules. */
    public List<Map<String, Object>> promotions(String decisionId) {
        List<Map<String, Object>> rows = Rows.normalize(jdbc.queryForList("SELECT b.promotion_id, p.promotion_name,"
                + " p.promo_type, p.discount_pct, p.discount_amount, p.priority, p.start_date, p.end_date,"
                + " b.application_status, b.reason_code, b.discount_pct_effective FROM price_decision_promotion b JOIN"
                + " promotion p ON p.promotion_id = b.promotion_id WHERE b.decision_id = ? ORDER BY p.priority,"
                + " b.promotion_id", decisionId));
        for (Map<String, Object> r : rows) {
            r.put("rules", Rows.normalize(jdbc.queryForList("SELECT rule_id, rule_type, target_promotion_id,"
                    + " param_numeric FROM promotion_rule WHERE promotion_id = ? ORDER BY rule_id", r.get("promotion_id"))));
        }
        return rows;
    }

    public List<Map<String, Object>> statusEvents(String decisionId) {
        return Rows.normalize(jdbc.queryForList("SELECT event_id, from_status, to_status, event_ts, actor_role, note"
                + " FROM price_decision_status_event WHERE decision_id = ? ORDER BY event_ts, event_id", decisionId));
    }

    /** The price_history rows this decision set. */
    public List<Map<String, Object>> priceHistoryOf(String decisionId) {
        return Rows.normalize(jdbc.queryForList("SELECT price_history_id, price_date, list_price, shelf_price,"
                + " previous_list_price, change_reason FROM price_history WHERE source_decision_id = ?"
                + " ORDER BY price_date", decisionId));
    }

    public Optional<String> successor(String decisionId) {
        return jdbc.queryForList("SELECT decision_id FROM price_decision WHERE previous_decision_id = ?", String.class,
                decisionId).stream().findFirst();
    }

    public boolean productExists(String skuId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE sku_id = ?", Long.class, skuId) > 0;
    }

    /** Daily prices with source decision and reason, inclusive date range (either bound may be null). */
    public List<Map<String, Object>> priceHistory(String skuId, LocalDate from, LocalDate to) {
        return Rows.normalize(jdbc.queryForList("SELECT ph.price_history_id, ph.price_date, ph.list_price,"
                + " ph.shelf_price, ph.previous_list_price, ph.change_reason, ph.source_decision_id, ph.cost_record_id,"
                + " d.trigger AS decision_trigger, d.primary_reason_code AS decision_primary_reason FROM price_history ph"
                + " LEFT JOIN price_decision d ON d.decision_id = ph.source_decision_id WHERE ph.sku_id = ?"
                + " AND ph.price_date >= ? AND ph.price_date <= ? ORDER BY ph.price_date", skuId,
                from == null ? LocalDate.of(1900, 1, 1) : from, to == null ? LocalDate.of(9999, 12, 31) : to));
    }
}
