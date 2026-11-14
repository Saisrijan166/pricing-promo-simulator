package com.pricingsim.detection.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.DetectedException;
import com.pricingsim.detection.DetectionRule;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.pricing.PricingPolicy;

/**
 * PROMO_STACK_VIOLATION: re-runs the stacking rules (PLAN.md §8 S10) on each order's APPLIED promotions: an EXCLUSIVE
 * promotion together with another, an excluded pair, more promotions than the smallest MAX_STACK_COUNT, a combined
 * discount above the smallest MAX_COMBINED_DISCOUNT_PCT, or a MIN_ORDER_QTY above the quantity. Rules count only on
 * dates inside their effective window; policy defaults apply when no applied promotion has the rule.
 */
public final class PromoStackViolationDetector implements DetectionRule {

    private record Rule(String type, String target, BigDecimal param, LocalDate from, LocalDate to) {
        boolean on(LocalDate d) {
            return !d.isBefore(from) && !d.isAfter(to);
        }
    }

    @Override
    public IssueType issueType() {
        return IssueType.PROMO_STACK_VIOLATION;
    }

    @Override
    public String description() {
        return "Order's APPLIED promotions break exclusivity, excluded pairs, stack limit, discount cap or min quantity";
    }

    @Override
    public List<DetectedException> detect(JdbcTemplate jdbc, PricingPolicy policy) {
        Map<String, List<Rule>> rules = new TreeMap<>();
        jdbc.query("SELECT promotion_id, rule_type, target_promotion_id, param_numeric, effective_from, effective_to"
                + " FROM promotion_rule ORDER BY rule_id", rs -> {
                    rules.computeIfAbsent(rs.getString("promotion_id"), k -> new ArrayList<>()).add(new Rule(
                            rs.getString("rule_type"), rs.getString("target_promotion_id"),
                            rs.getBigDecimal("param_numeric"), rs.getObject("effective_from", LocalDate.class),
                            rs.getObject("effective_to", LocalDate.class)));
                });
        List<DetectedException> out = new ArrayList<>();
        Order[] current = {null};
        jdbc.query("SELECT o.order_id, o.sku_id, o.order_date, o.quantity, o.gross_amount, op.promotion_id,"
                + " op.discount_amount FROM customer_order o JOIN order_promotion op ON op.order_id = o.order_id"
                + " WHERE op.application_status = 'APPLIED' ORDER BY o.order_id, op.sequence_no", rs -> {
                    String orderId = rs.getString("order_id");
                    if (current[0] == null || !current[0].orderId.equals(orderId)) {
                        check(current[0], rules, policy, out);
                        current[0] = new Order(orderId, rs.getString("sku_id"),
                                rs.getObject("order_date", LocalDate.class), rs.getInt("quantity"),
                                rs.getBigDecimal("gross_amount"));
                    }
                    current[0].promotions.add(rs.getString("promotion_id"));
                    current[0].discount = current[0].discount.add(rs.getBigDecimal("discount_amount"));
                });
        check(current[0], rules, policy, out);
        return out;
    }

    private static final class Order {
        final String orderId;
        final String skuId;
        final LocalDate date;
        final int quantity;
        final BigDecimal gross;
        final List<String> promotions = new ArrayList<>();
        BigDecimal discount = BigDecimal.ZERO;

        Order(String orderId, String skuId, LocalDate date, int quantity, BigDecimal gross) {
            this.orderId = orderId;
            this.skuId = skuId;
            this.date = date;
            this.quantity = quantity;
            this.gross = gross;
        }
    }

    private void check(Order o, Map<String, List<Rule>> rules, PricingPolicy policy, List<DetectedException> out) {
        if (o == null) {
            return;
        }
        String violation = violation(o, rules, policy);
        if (violation != null) {
            out.add(DetectedException.of(issueType(), "customer_order", o.orderId, o.skuId, o.date,
                    List.copyOf(o.promotions), String.join("|", o.promotions), null, violation));
        }
    }

    static String violation(Order o, Map<String, List<Rule>> rules, PricingPolicy policy) {
        int maxStack = Integer.MAX_VALUE;
        BigDecimal cap = null;
        boolean anyStackRule = false;
        for (String id : o.promotions) {
            for (Rule r : rules.getOrDefault(id, List.of())) {
                if (!r.on(o.date)) {
                    continue;
                }
                switch (r.type()) {
                    case "EXCLUSIVE" -> {
                        if (o.promotions.size() > 1) {
                            return id + " is EXCLUSIVE but " + o.promotions.size() + " promotions applied";
                        }
                    }
                    case "EXCLUDES_PROMOTION" -> {
                        if (o.promotions.contains(r.target())) {
                            return id + " excludes " + r.target() + " but both applied";
                        }
                    }
                    case "MAX_STACK_COUNT" -> {
                        anyStackRule = true;
                        maxStack = Math.min(maxStack, r.param().intValue());
                    }
                    case "MAX_COMBINED_DISCOUNT_PCT" -> cap = cap == null ? r.param() : cap.min(r.param());
                    case "MIN_ORDER_QTY" -> {
                        if (BigDecimal.valueOf(o.quantity).compareTo(r.param()) < 0) {
                            return id + " needs quantity " + r.param().stripTrailingZeros().toPlainString();
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        if (!anyStackRule) {
            maxStack = policy.defaultMaxStack();
        }
        if (o.promotions.size() > maxStack) {
            return o.promotions.size() + " promotions applied, stack limit " + maxStack;
        }
        BigDecimal limit = cap == null ? policy.defaultMaxCombinedDiscount() : cap;
        if (o.discount.compareTo(limit.multiply(o.gross)) > 0) {
            return "combined discount " + o.discount.toPlainString() + " above cap " + limit.toPlainString();
        }
        return null;
    }
}
