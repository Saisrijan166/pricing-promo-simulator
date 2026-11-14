package com.pricingsim.generator.anomaly;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.DecisionSource;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.domain.enums.ObservationUsage;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.generator.sim.OrderGenerator;
import com.pricingsim.generator.sim.PriceSetter;
import com.pricingsim.generator.sim.SimulationHooks;
import com.pricingsim.generator.sim.SkuState;
import com.pricingsim.pricing.PricingContext;
import com.pricingsim.pricing.PricingEngine;
import com.pricingsim.pricing.PricingResult;
import com.pricingsim.pricing.PromotionOffer;
import com.pricingsim.pricing.PromotionStackResolver;

/**
 * Records, during a baseline dry run, every record eligible for each anomaly type (PLAN.md §9 "eligible target"). It
 * changes nothing.
 * <ol>
 *   <li>PRICE_BELOW_FLOOR: an engine WEEKLY_REVIEW decision without a guardrail conflict.</li>
 *   <li>PROMO_STACK_VIOLATION: an order where a coupon was rejected for a stacking conflict (exclusive, excluded pair,
 *       stack limit or cap) and forcing it still leaves a positive net amount.</li>
 *   <li>STALE_OBSERVATION_AS_CURRENT: a decision with an EXCLUDED_STALE observation that would be USED if treated as
 *       fresh (confidence ≥ 0.80, stored age above 72.00 h).</li>
 *   <li>MARGIN_BEFORE_DISCOUNT: a decision that rejected a markdown with PROMO_REJECTED_MARGIN, where re-applying it
 *       leaves a positive net price.</li>
 *   <li>PRICE_CHANGE_WITHOUT_REASON: a Tuesday–Thursday without a decision, where a ±8% move fits inside
 *       [floor, MRP] in at least one direction.</li>
 * </ol>
 */
public final class CandidateCollector implements SimulationHooks {

    static final BigDecimal MAX_ROGUE_MOVE = new BigDecimal("0.08");

    private final Map<IssueType, List<AnomalyPlan.Target>> candidates = new EnumMap<>(IssueType.class);
    private final com.pricingsim.pricing.PricingPolicy policy;

    public CandidateCollector(com.pricingsim.pricing.PricingPolicy policy) {
        this.policy = policy;
    }

    /**
     * The first EXCLUDED_STALE observation (by ID) that becomes USED when treated as fresh: its match confidence passes
     * and its stored age (2 dp) is visibly above the freshness window. Null if there is none.
     */
    static String forceableStale(PricingResult baseline, com.pricingsim.pricing.PricingPolicy policy) {
        return baseline.competitor().assessments().stream()
                .filter(a -> a.usage() == ObservationUsage.EXCLUDED_STALE)
                .filter(a -> a.observation().matchConfidence().compareTo(policy.minMatchConfidence()) >= 0)
                .filter(a -> a.ageHours().compareTo(BigDecimal.valueOf(policy.freshnessHours())) > 0)
                .map(com.pricingsim.pricing.CompetitorReferenceResolver.Assessment::observationId)
                .findFirst().orElse(null);
    }

    public Map<IssueType, List<AnomalyPlan.Target>> candidates() {
        return candidates;
    }

    private void add(IssueType type, String sku, LocalDate day, int orderIndex) {
        candidates.computeIfAbsent(type, t -> new ArrayList<>()).add(new AnomalyPlan.Target(type, sku, day, orderIndex));
    }

    @Override
    public PricingEngine.Faults decisionFaults(DecisionTrigger trigger, PricingContext context, PricingResult baseline) {
        String sku = context.skuId();
        LocalDate day = context.effectiveDate();
        if (trigger == DecisionTrigger.WEEKLY_REVIEW && baseline.decisionSource() == DecisionSource.ENGINE
                && !baseline.guardrailConflict()) {
            add(IssueType.PRICE_BELOW_FLOOR, sku, day, -1);
        }
        if (forceableStale(baseline, policy) != null) {
            add(IssueType.STALE_OBSERVATION_AS_CURRENT, sku, day, -1);
        }
        if (marginReapplicable(baseline)) {
            add(IssueType.MARGIN_BEFORE_DISCOUNT, sku, day, -1);
        }
        return null;
    }

    /** A markdown was dropped for margin and re-applying every stacked markdown keeps the net price positive. */
    static boolean marginReapplicable(PricingResult baseline) {
        if (baseline.promotions().stream().noneMatch(e -> e.reason() == ReasonCode.PROMO_REJECTED_MARGIN)) {
            return false;
        }
        List<PromotionOffer> offers = baseline.promotions().stream()
                .filter(e -> e.status() == ApplicationStatus.APPLIED || e.reason() == ReasonCode.PROMO_REJECTED_MARGIN)
                .map(PromotionStackResolver.Evaluation::offer).toList();
        BigDecimal list = baseline.finalListPrice();
        return list.subtract(PromotionStackResolver.total(PromotionStackResolver.discounts(list, BigDecimal.ZERO,
                offers))).signum() > 0;
    }

    @Override
    public PriceSetter.DailyPrice nonDecisionDay(SkuState state, LocalDate day, PriceSetter.DailyPrice current) {
        DayOfWeek dow = day.getDayOfWeek();
        boolean midWeek = dow == DayOfWeek.TUESDAY || dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.THURSDAY;
        if (midWeek && current.sourceDecisionId() != null && rogueFeasible(current.listPrice(),
                state.currentDecision().floorPrice(), state.product().mrp())) {
            add(IssueType.PRICE_CHANGE_WITHOUT_REASON, state.skuId(), day, -1);
        }
        return null;
    }

    static boolean rogueFeasible(BigDecimal list, BigDecimal floor, BigDecimal mrp) {
        boolean up = list.multiply(BigDecimal.ONE.add(MAX_ROGUE_MOVE)).compareTo(mrp) <= 0;
        boolean down = list.multiply(BigDecimal.ONE.subtract(MAX_ROGUE_MOVE)).compareTo(floor) >= 0;
        return up || down;
    }

    @Override
    public PromotionStackResolver.Evaluation couponToForce(String skuId, LocalDate day, int orderIndex,
                                                           PromotionStackResolver.Resolution resolution,
                                                           BigDecimal gross, BigDecimal seededTotal) {
        if (OrderGenerator.forceableConflict(resolution, gross, seededTotal) != null) {
            add(IssueType.PROMO_STACK_VIOLATION, skuId, day, orderIndex);
        }
        return null;
    }
}
