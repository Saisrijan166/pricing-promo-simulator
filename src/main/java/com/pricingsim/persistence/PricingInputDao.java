package com.pricingsim.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.FundingSource;
import com.pricingsim.domain.enums.ObservationSource;
import com.pricingsim.domain.enums.PricePosition;
import com.pricingsim.domain.enums.PromoType;
import com.pricingsim.domain.enums.PromotionStatus;
import com.pricingsim.domain.enums.RuleType;
import com.pricingsim.domain.enums.StackGroup;
import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.Promotion;
import com.pricingsim.domain.model.PromotionRule;
import com.pricingsim.pricing.PricingContext;
import com.pricingsim.pricing.PromotionOffer;
import com.pricingsim.service.NotFoundException;

/**
 * Assembles a {@link PricingContext} for a SKU and date from the loaded data, with the same definitions the generator
 * used (PLAN.md §8 S1, docs/pricing_rules.md), so pricing a stored decision's date reproduces that decision:
 * <ul>
 *   <li>decision time 06:00Z on the date; cost record of the date's month;</li>
 *   <li>trigger: the stored decision's trigger on that date, else INITIAL without earlier price history, else
 *       WEEKLY_REVIEW; a stored MANUAL_OVERRIDE supplies its target price as the analyst's price;</li>
 *   <li>previous list price = the previous day's price_history row;</li>
 *   <li>on hand = opening + receipts of the date (else the latest closing); sales over the 7 / 28 days before;</li>
 *   <li>every observation captured and ingested by the decision time; every markdown that includes the SKU, with
 *       its rules and cancellation time (coupons are resolved per order, not by decisions).</li>
 * </ul>
 * Read-only.
 */
public final class PricingInputDao {

    /** What-if replacements; any null field keeps the stored data. */
    public record Overrides(BigDecimal landedCost, Integer onHandUnits, List<CompetitorObservation> observations,
                            List<String> promotionIds) {
        public static final Overrides NONE = new Overrides(null, null, null, null);
    }

    private final JdbcTemplate jdbc;

    public PricingInputDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public static Instant decisionTs(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(6 * 3600L);
    }

    public PricingContext load(String skuId, LocalDate day, Overrides o) {
        List<Map<String, Object>> products = jdbc.queryForList("SELECT * FROM product WHERE sku_id = ?", skuId);
        if (products.isEmpty()) {
            throw new NotFoundException("Unknown SKU " + skuId);
        }
        Map<String, Object> p = Rows.normalize(products.get(0));
        String month = YearMonth.from(day).toString();
        List<Map<String, Object>> costs = jdbc.queryForList(
                "SELECT cost_record_id, landed_cost FROM cost_record WHERE sku_id = ? AND cost_month = ?", skuId, month);
        if (costs.isEmpty()) {
            throw new NotFoundException("No cost record for " + skuId + " in " + month
                    + " (the date is outside the SKU's loaded data)");
        }
        Instant ts = decisionTs(day);

        List<Map<String, Object>> stored = jdbc.queryForList("SELECT trigger, decision_source, target_price FROM"
                + " price_decision WHERE sku_id = ? AND effective_date = ?", skuId, day);
        List<Map<String, Object>> prevRow = jdbc.queryForList("SELECT list_price FROM price_history WHERE sku_id = ?"
                + " AND price_date = ?", skuId, day.minusDays(1));
        BigDecimal prev = prevRow.isEmpty() ? null : (BigDecimal) prevRow.get(0).get("list_price");
        DecisionTrigger trigger = !stored.isEmpty() ? DecisionTrigger.valueOf((String) stored.get(0).get("trigger"))
                : prev == null ? DecisionTrigger.INITIAL : DecisionTrigger.WEEKLY_REVIEW;
        if (trigger == DecisionTrigger.INITIAL) {
            prev = null;
        } else if (prev == null) {
            throw new NotFoundException("No price history for " + skuId + " on " + day.minusDays(1));
        }
        BigDecimal manual = !stored.isEmpty() && "MANUAL_OVERRIDE".equals(stored.get(0).get("decision_source"))
                ? (BigDecimal) stored.get(0).get("target_price") : null;
        List<String> prevDecision = jdbc.queryForList("SELECT decision_id FROM price_decision WHERE sku_id = ? AND"
                + " effective_date < ? ORDER BY effective_date DESC LIMIT 1", String.class, skuId, day);

        int onHand = o.onHandUnits() != null ? o.onHandUnits() : onHand(skuId, day);
        int[] sales = sales(skuId, day);
        List<CompetitorObservation> observations = o.observations() != null ? o.observations()
                : observations(skuId, ts);
        List<PromotionOffer> promotions = promotions(skuId).stream().filter(PromotionOffer::isMarkdown).toList();
        if (o.promotionIds() != null) {
            List<String> known = promotions.stream().map(PromotionOffer::promotionId).toList();
            for (String id : o.promotionIds()) {
                if (!known.contains(id)) {
                    throw new NotFoundException("Markdown promotion " + id + " does not include " + skuId);
                }
            }
            promotions = promotions.stream().filter(x -> o.promotionIds().contains(x.promotionId())).toList();
        }
        BigDecimal landed = o.landedCost() != null ? o.landedCost() : (BigDecimal) costs.get(0).get("landed_cost");
        return new PricingContext(skuId, day, ts, trigger, (BigDecimal) p.get("mrp"), (BigDecimal) p.get("map_price"),
                (BigDecimal) p.get("min_margin_pct"), (BigDecimal) p.get("min_promo_margin_pct"),
                PricePosition.valueOf((String) p.get("price_position")), (String) costs.get(0).get("cost_record_id"),
                landed, prev, trigger == DecisionTrigger.INITIAL || prevDecision.isEmpty() ? null : prevDecision.get(0),
                onHand, sales[0], sales[1], sales[2], sales[3], observations, promotions, manual);
    }

    private int onHand(String skuId, LocalDate day) {
        List<Integer> today = jdbc.queryForList("SELECT opening_on_hand + receipts_units FROM inventory_snapshot"
                + " WHERE sku_id = ? AND snapshot_date = ?", Integer.class, skuId, day);
        if (!today.isEmpty()) {
            return today.get(0);
        }
        List<Integer> last = jdbc.queryForList("SELECT closing_on_hand FROM inventory_snapshot WHERE sku_id = ? AND"
                + " snapshot_date < ? ORDER BY snapshot_date DESC LIMIT 1", Integer.class, skuId, day);
        return last.isEmpty() ? 0 : last.get(0);
    }

    /** {d7, n7, d28, n28}: units sold and days of history in the 7 / 28 days before {@code day}. */
    private int[] sales(String skuId, LocalDate day) {
        int[] out = new int[4];
        int i = 0;
        for (int window : new int[] {7, 28}) {
            Map<String, Object> r = jdbc.queryForMap("SELECT COALESCE(SUM(units_sold), 0) AS u, COUNT(*) AS n FROM"
                    + " inventory_snapshot WHERE sku_id = ? AND snapshot_date >= ? AND snapshot_date < ?", skuId,
                    day.minusDays(window), day);
            out[i++] = ((Number) r.get("u")).intValue();
            out[i++] = ((Number) r.get("n")).intValue();
        }
        return out;
    }

    private List<CompetitorObservation> observations(String skuId, Instant ts) {
        return jdbc.query("SELECT * FROM competitor_observation WHERE sku_id = ? AND source_captured_at <= ?"
                        + " AND ingested_at <= ? ORDER BY source_captured_at, observation_id",
                (rs, n) -> new CompetitorObservation(rs.getString("observation_id"), rs.getString("sku_id"),
                        Competitor.valueOf(rs.getString("competitor")), rs.getBigDecimal("observed_price"),
                        instant(rs, "source_captured_at"), instant(rs, "ingested_at"),
                        ObservationSource.valueOf(rs.getString("source_type")), rs.getBoolean("competitor_in_stock"),
                        rs.getBigDecimal("match_confidence")),
                skuId, OffsetDateTime.ofInstant(ts, ZoneOffset.UTC), OffsetDateTime.ofInstant(ts, ZoneOffset.UTC));
    }

    /** Every promotion that includes the SKU, with rules and cancellation time, sorted by promotion ID. */
    public List<PromotionOffer> promotions(String skuId) {
        List<Promotion> promotions = jdbc.query("SELECT p.* FROM promotion p JOIN promotion_product pp ON"
                + " pp.promotion_id = p.promotion_id WHERE pp.sku_id = ? ORDER BY p.promotion_id", (rs, n) ->
                new Promotion(rs.getString("promotion_id"), rs.getString("promotion_name"),
                        PromoType.valueOf(rs.getString("promo_type")), StackGroup.valueOf(rs.getString("stack_group")),
                        rs.getBigDecimal("discount_pct"), rs.getBigDecimal("discount_amount"),
                        rs.getString("coupon_code"), rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class), rs.getInt("priority"),
                        Channel.valueOf(rs.getString("channel")), FundingSource.valueOf(rs.getString("funding_source")),
                        PromotionStatus.valueOf(rs.getString("status")), instant(rs, "created_at")), skuId);
        Map<String, List<PromotionRule>> rules = new TreeMap<>();
        jdbc.query("SELECT r.* FROM promotion_rule r JOIN promotion_product pp ON pp.promotion_id = r.promotion_id"
                + " WHERE pp.sku_id = ? ORDER BY r.rule_id", rs -> {
                    rules.computeIfAbsent(rs.getString("promotion_id"), k -> new ArrayList<>()).add(new PromotionRule(
                            rs.getString("rule_id"), rs.getString("promotion_id"),
                            RuleType.valueOf(rs.getString("rule_type")), rs.getString("target_promotion_id"),
                            rs.getBigDecimal("param_numeric"), rs.getObject("effective_from", LocalDate.class),
                            rs.getObject("effective_to", LocalDate.class)));
                }, skuId);
        Map<String, Instant> cancelled = new TreeMap<>();
        jdbc.query("SELECT e.promotion_id, e.event_ts FROM promotion_status_event e JOIN promotion_product pp ON"
                + " pp.promotion_id = e.promotion_id WHERE pp.sku_id = ? AND e.to_status = 'CANCELLED'", rs -> {
                    cancelled.put(rs.getString("promotion_id"), instant(rs, "event_ts"));
                }, skuId);
        return promotions.stream().map(p -> new PromotionOffer(p, rules.getOrDefault(p.promotionId(), List.of()),
                cancelled.get(p.promotionId()))).toList();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
