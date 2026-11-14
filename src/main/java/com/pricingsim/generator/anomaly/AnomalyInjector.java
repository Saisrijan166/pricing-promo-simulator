package com.pricingsim.generator.anomaly;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.domain.enums.ObservationUsage;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.CustomerOrder;
import com.pricingsim.domain.model.PriceHistory;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.rng.RandomStreams;
import com.pricingsim.generator.sim.PriceSetter;
import com.pricingsim.generator.sim.SimulationHooks;
import com.pricingsim.generator.sim.SkuState;
import com.pricingsim.pricing.CompetitorReferenceResolver;
import com.pricingsim.pricing.PricePointRounder;
import com.pricingsim.pricing.PricingContext;
import com.pricingsim.pricing.PricingEngine;
import com.pricingsim.pricing.PricingResult;
import com.pricingsim.pricing.PromotionStackResolver;

/**
 * Injects the planned anomalies during the real run (PLAN.md §9). Each hook receives the valid baseline record, changes
 * the minimum needed, keeps every derived value consistent, and records a ground-truth label. The labels go to
 * {@code labels/} only; injected records carry no flag and look like ordinary records.
 *
 * <table>
 *   <caption>Injections</caption>
 *   <tr><th>Type</th><th>Change</th></tr>
 *   <tr><td>PRICE_BELOW_FLOOR</td><td>The weekly review is recorded as an analyst MANUAL_OVERRIDE at the price point
 *       at or below floor × U(0.85, 0.97); the price-history days it covers and their orders use that price.</td></tr>
 *   <tr><td>PROMO_STACK_VIOLATION</td><td>A coupon rejected for a stacking conflict is APPLIED; discount, net and
 *       margin of the order are recomputed.</td></tr>
 *   <tr><td>STALE_OBSERVATION_AS_CURRENT</td><td>The first EXCLUDED_STALE observation is USED; the reference, counts,
 *       target and final price follow it.</td></tr>
 *   <tr><td>MARGIN_BEFORE_DISCOUNT</td><td>The margin is judged on the list price, so the markdown rejected for margin
 *       is applied; net and shelf price follow, and margin_pct is stored on the list basis.</td></tr>
 *   <tr><td>PRICE_CHANGE_WITHOUT_REASON</td><td>On a mid-week day without a decision the list price moves ±3–8% (the
 *       direction that stays inside [floor, MRP]) until the next decision; those days carry no reason and no decision.
 *       Their orders use that price.</td></tr>
 * </table>
 */
public final class AnomalyInjector implements SimulationHooks {

    private final AnomalyPlan plan;
    private final RandomStreams streams;
    private AnomalyPlan.Target pendingOrder;
    private String forcedObservation;
    private final com.pricingsim.pricing.PricingPolicy policy;
    private final Map<String, Builder> byDecisionId = new TreeMap<>();
    private final Map<String, Builder> openSpanBySku = new TreeMap<>();
    private final TreeSet<String> injectedKeys = new TreeSet<>();

    /** Label under construction (related IDs can grow while the anomaly's days are simulated). */
    private static final class Builder {
        IssueType type;
        String entityType;
        String entityId;
        String skuId;
        LocalDate day;
        final List<String> related = new ArrayList<>();
        String field;
        String baseline;
        String injected;
    }

    public AnomalyInjector(AnomalyPlan plan, RandomStreams streams, com.pricingsim.pricing.PricingPolicy policy) {
        this.policy = policy;
        this.plan = plan;
        this.streams = streams;
    }

    // --- Decisions: PRICE_BELOW_FLOOR, STALE_OBSERVATION_AS_CURRENT, MARGIN_BEFORE_DISCOUNT --------------------------

    @Override
    public PricingEngine.Faults decisionFaults(DecisionTrigger trigger, PricingContext context, PricingResult baseline) {
        AnomalyPlan.Target t = plan.dayTarget(context.skuId(), context.effectiveDate());
        if (t == null) {
            return null;
        }
        return switch (t.type()) {
            case PRICE_BELOW_FLOOR -> {
                Random r = streams.of("anomaly-inject", t.type() + "|" + t.skuId());
                double factor = Distributions.uniform(r, 0.85, 0.97);
                BigDecimal raw = baseline.floorPrice().multiply(new BigDecimal(factor)).setScale(2, RoundingMode.DOWN);
                BigDecimal point = PricePointRounder.pointAtOrBelow(raw);
                BigDecimal forced = (point != null ? point : raw).setScale(2, RoundingMode.UNNECESSARY);
                yield new PricingEngine.Faults(forced, java.util.Collections.emptySortedSet(), false);
            }
            case STALE_OBSERVATION_AS_CURRENT -> {
                String stale = CandidateCollector.forceableStale(baseline, policy);
                if (stale == null) {
                    throw new IllegalStateException("Planned stale-observation anomaly no longer eligible: " + t);
                }
                forcedObservation = stale;
                yield new PricingEngine.Faults(null, new TreeSet<>(List.of(stale)), false);
            }
            case MARGIN_BEFORE_DISCOUNT -> new PricingEngine.Faults(null, java.util.Collections.emptySortedSet(), true);
            default -> null; // PRICE_CHANGE_WITHOUT_REASON targets non-decision days
        };
    }

    @Override
    public void afterDecision(PricingContext context, PricingResult baseline, PricingResult result, String decisionId) {
        AnomalyPlan.Target t = plan.dayTarget(context.skuId(), context.effectiveDate());
        if (t == null || result == baseline) {
            return;
        }
        Builder b = start(t, "price_decision", decisionId);
        b.related.add(context.costRecordId());
        switch (t.type()) {
            case PRICE_BELOW_FLOOR -> {
                b.field = "final_list_price";
                b.baseline = baseline.finalListPrice().toPlainString();
                b.injected = result.finalListPrice().toPlainString();
                byDecisionId.put(decisionId, b); // covered price-history rows are added as they are written
            }
            case STALE_OBSERVATION_AS_CURRENT -> {
                String used = forcedObservation;
                boolean isUsed = result.competitor().assessments().stream()
                        .anyMatch(a -> a.observationId().equals(used) && a.usage() == ObservationUsage.USED);
                if (!isUsed) {
                    throw new IllegalStateException("Forced observation " + used + " was not used for " + t);
                }
                b.related.add(used);
                b.field = "price_decision_observation.usage";
                b.baseline = ObservationUsage.EXCLUDED_STALE.name();
                b.injected = ObservationUsage.USED.name();
            }
            case MARGIN_BEFORE_DISCOUNT -> {
                baseline.promotions().stream().filter(e -> e.reason() == ReasonCode.PROMO_REJECTED_MARGIN)
                        .forEach(e -> b.related.add(e.promotionId()));
                // baseline_value: the correct (net-basis) margin of the stored net price; injected: the stored
                // list-basis margin.
                b.field = "margin_pct";
                b.baseline = com.pricingsim.pricing.MarginCalculator.netMargin(result.expectedNetPrice(),
                        context.landedCost()).toPlainString();
                b.injected = result.marginPct().toPlainString();
            }
            default -> throw new IllegalStateException("Unexpected decision anomaly " + t);
        }
        finish(t, b);
    }

    // --- Non-decision days: PRICE_CHANGE_WITHOUT_REASON --------------------------------------------------------------

    @Override
    public PriceSetter.DailyPrice nonDecisionDay(SkuState state, LocalDate day, PriceSetter.DailyPrice current) {
        AnomalyPlan.Target t = plan.dayTarget(state.skuId(), day);
        if (t == null || t.type() != IssueType.PRICE_CHANGE_WITHOUT_REASON) {
            return null;
        }
        Random r = streams.of("anomaly-inject", t.type() + "|" + t.skuId());
        double move = Distributions.uniform(r, 0.03, 0.08);
        boolean up = Distributions.bernoulli(r, 0.5);
        BigDecimal list = current.listPrice();
        BigDecimal floor = state.currentDecision().floorPrice();
        BigDecimal mrp = state.product().mrp();
        BigDecimal candidate = rogue(list, move, up);
        if (candidate.compareTo(mrp) > 0 || candidate.compareTo(floor) < 0) {
            candidate = rogue(list, move, !up); // the other direction is feasible (checked when planning)
        }
        if (candidate.compareTo(mrp) > 0 || candidate.compareTo(floor) < 0) {
            throw new IllegalStateException("No feasible price move for " + t);
        }
        List<PromotionStackResolver.Applied> markdowns = PromotionStackResolver.discounts(candidate, BigDecimal.ZERO,
                current.markdowns().stream().map(PromotionStackResolver.Applied::offer).toList());
        BigDecimal shelf = candidate.subtract(PromotionStackResolver.total(markdowns));

        Builder b = start(t, "price_history", IdFactory.priceHistory(t.skuId(), day));
        b.related.add(current.sourceDecisionId());
        b.field = "list_price";
        b.baseline = list.toPlainString();
        b.injected = candidate.toPlainString();
        openSpanBySku.put(t.skuId(), b);
        finish(t, b);
        return new PriceSetter.DailyPrice(candidate, shelf, null, null, markdowns);
    }

    private static BigDecimal rogue(BigDecimal list, double move, boolean up) {
        BigDecimal factor = BigDecimal.ONE.add(new BigDecimal(up ? move : -move));
        return list.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public void afterPriceHistory(PriceHistory row) {
        if (row.sourceDecisionId() != null) {
            Builder below = byDecisionId.get(row.sourceDecisionId());
            if (below != null) {
                below.related.add(row.priceHistoryId());
            }
            openSpanBySku.remove(row.skuId()); // a decision ends an unexplained span
        } else {
            Builder span = openSpanBySku.get(row.skuId());
            if (span != null && !span.entityId.equals(row.priceHistoryId())) {
                span.related.add(row.priceHistoryId());
            }
        }
    }

    // --- Orders: PROMO_STACK_VIOLATION -------------------------------------------------------------------------------

    @Override
    public PromotionStackResolver.Evaluation couponToForce(String skuId, LocalDate day, int orderIndex,
                                                           PromotionStackResolver.Resolution resolution,
                                                           BigDecimal gross, BigDecimal seededTotal) {
        AnomalyPlan.Target t = plan.orderTarget(skuId, day, orderIndex);
        if (t == null) {
            return null;
        }
        PromotionStackResolver.Evaluation e = com.pricingsim.generator.sim.OrderGenerator.forceableConflict(
                resolution, gross, seededTotal);
        if (e == null) {
            throw new IllegalStateException("Planned order anomaly no longer eligible: " + t);
        }
        pendingOrder = t;
        return e;
    }

    @Override
    public void afterOrder(CustomerOrder order, String forcedPromotionId) {
        if (forcedPromotionId == null) {
            return;
        }
        AnomalyPlan.Target target = pendingOrder;
        pendingOrder = null;
        Builder b = start(target, "customer_order", order.orderId());
        b.related.add(forcedPromotionId);
        b.related.add(order.priceHistoryId());
        b.field = "order_promotion.application_status";
        b.baseline = ApplicationStatus.REJECTED.name();
        b.injected = ApplicationStatus.APPLIED.name();
        finish(target, b);
    }

    // --- Labels -------------------------------------------------------------------------------------------------------

    private Builder start(AnomalyPlan.Target t, String entityType, String entityId) {
        Builder b = new Builder();
        b.type = t.type();
        b.entityType = entityType;
        b.entityId = entityId;
        b.skuId = t.skuId();
        b.day = t.day();
        return b;
    }

    private final List<Builder> builders = new ArrayList<>();

    private void finish(AnomalyPlan.Target t, Builder b) {
        if (!injectedKeys.add(t.type() + "|" + t.skuId() + "|" + t.day() + "|" + t.orderIndex())) {
            throw new IllegalStateException("Anomaly injected twice: " + t);
        }
        builders.add(b);
    }

    /**
     * The labels, sorted by (issue_type, entity_id) with GT IDs in that order (year of the event date). Fails if any
     * planned target was not injected.
     */
    public List<GroundTruthRecord> groundTruth() {
        if (injectedKeys.size() != plan.size()) {
            throw new IllegalStateException("Injected " + injectedKeys.size() + " of " + plan.size()
                    + " planned anomalies");
        }
        List<GroundTruthRecord> out = new ArrayList<>();
        for (Builder b : builders) {
            out.add(new GroundTruthRecord(null, b.type, b.entityType, b.entityId, b.skuId, b.day, b.related, b.field,
                    b.baseline, b.injected, GroundTruthRecord.violatedRule(b.type), true,
                    GroundTruthRecord.expectedAction(b.type)));
        }
        out.sort(Comparator.comparing(GroundTruthRecord::issueType).thenComparing(GroundTruthRecord::entityId));
        Map<Integer, Long> counters = new TreeMap<>();
        List<GroundTruthRecord> numbered = new ArrayList<>(out.size());
        for (GroundTruthRecord r : out) {
            int year = r.eventDate().getYear();
            long n = counters.merge(year, 1L, Long::sum);
            numbered.add(r.withId(IdFactory.groundTruth(year, n)));
        }
        return List.copyOf(numbered);
    }
}
