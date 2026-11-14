package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.DecisionSource;
import com.pricingsim.domain.enums.DecisionStatus;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.DecisionType;
import com.pricingsim.domain.enums.ReasonCode;

/**
 * The pricing engine (PLAN.md §8): {@code price(PricingContext) → PricingResult}, pure and deterministic. The generator
 * and {@code POST /pricing/simulate} use this same class, so re-running a stored decision's inputs reproduces it.
 *
 * <ol>
 *   <li>S1 inputs: the context snapshot.</li>
 *   <li>S2 floor = roundUp(max(landed / (1 − min_margin), MAP)).</li>
 *   <li>S3 ceiling = MRP; ceiling &lt; floor is a guardrail conflict: price = floor, ESCALATED (OPS_MANAGER approves).</li>
 *   <li>S4 competitor reference (fresh, confident, latest per competitor; median).</li>
 *   <li>S5 target = ref × (1 + position offset); else previous price; else MRP × 0.95 (INITIAL). A manual override
 *       replaces the target.</li>
 *   <li>S6 stockout → hold previous price and suppress markdowns; overstock → −3%; low cover with high velocity → +3%
 *       (overstock / low cover need 7 days of sales history).</li>
 *   <li>S7 step limit ±8% of the previous price (not for INITIAL or a manual override).</li>
 *   <li>S8 clamp to [floor, ceiling]; the floor always wins, so a cost change can move past the step limit.</li>
 *   <li>S9 price point (PricePointRounder).</li>
 *   <li>S10 markdown stacking (PromotionStackResolver) on the final list price.</li>
 *   <li>S11 net margin ≥ required (min_promo_margin if any markdown applies, else min_margin); otherwise drop the
 *       last-applied markdown and recompute.</li>
 *   <li>S12 decision type, escalation above 10% change, reason codes, primary reason.</li>
 * </ol>
 */
public final class PricingEngine {

    /** Primary reason when the list price changed (or for INITIAL): first match wins. */
    static final List<ReasonCode> CHANGE_PRECEDENCE = List.of(
            ReasonCode.GUARDRAIL_CONFLICT_ESCALATED,
            ReasonCode.MANUAL_OVERRIDE,
            ReasonCode.INITIAL_PRICE,
            ReasonCode.COST_CHANGE,
            ReasonCode.FLOOR_ENFORCED,
            ReasonCode.CEILING_ENFORCED,
            ReasonCode.STOCKOUT_HOLD,
            ReasonCode.LARGE_CHANGE_ESCALATED,
            ReasonCode.STEP_LIMIT_APPLIED,
            ReasonCode.OVERSTOCK_MARKDOWN,
            ReasonCode.LOW_COVER_INCREASE,
            ReasonCode.COMPETITOR_ALIGN,
            ReasonCode.COMPETITOR_DATA_STALE,
            ReasonCode.PRICE_POINT_ROUNDED,
            ReasonCode.NO_CHANGE);

    /** Primary reason when the list price held: why it held. */
    static final List<ReasonCode> HOLD_PRECEDENCE = List.of(
            ReasonCode.GUARDRAIL_CONFLICT_ESCALATED,
            ReasonCode.MANUAL_OVERRIDE,
            ReasonCode.STOCKOUT_HOLD,
            ReasonCode.COMPETITOR_DATA_STALE,
            ReasonCode.NO_CHANGE);

    /**
     * Deliberate rule violations, used only by anomaly injection to fabricate the PLAN.md §9 defects with consistent
     * arithmetic. The generator baseline and the API always use {@link #NONE}, which follows PLAN.md §8 exactly.
     *
     * @param forcedListPrice         record an analyst override at this list price, skipping the step limit, the clamp
     *                                and price-point rounding (PRICE_BELOW_FLOOR)
     * @param staleObservationsAsUsed observation IDs that pass the freshness test whatever their age
     *                                (STALE_OBSERVATION_AS_CURRENT)
     * @param marginOnListPrice       judge and store the margin on the list price instead of the net price
     *                                (MARGIN_BEFORE_DISCOUNT)
     */
    public record Faults(BigDecimal forcedListPrice, Set<String> staleObservationsAsUsed, boolean marginOnListPrice) {
        public static final Faults NONE = new Faults(null, java.util.Collections.emptySortedSet(), false);

        public Faults {
            staleObservationsAsUsed = java.util.Collections.unmodifiableSortedSet(
                    new java.util.TreeSet<>(staleObservationsAsUsed));
        }
    }

    private final PricingPolicy policy;

    public PricingEngine(PricingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public PricingPolicy policy() {
        return policy;
    }

    public PricingResult price(PricingContext ctx) {
        return price(ctx, Faults.NONE);
    }

    /** Prices with deliberate faults (anomaly injection only); {@code Faults.NONE} is the normal engine. */
    public PricingResult price(PricingContext ctx, Faults faults) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(faults, "faults");
        Set<ReasonCode> reasons = new LinkedHashSet<>();
        List<ExplanationStep> steps = new ArrayList<>(12);
        BigDecimal prev = ctx.prevListPrice();
        BigDecimal forced = faults.forcedListPrice();
        boolean manual = ctx.manualOverride() || forced != null;

        // S1 Inputs
        steps.add(step("S1", "Inputs", map("trigger", ctx.trigger().name(),
                        "observations", Integer.toString(ctx.observations().size()),
                        "promotions", Integer.toString(ctx.promotions().size())),
                "snapshot", "visible at decision_ts"));
        if (ctx.trigger() == DecisionTrigger.INITIAL) {
            reasons.add(ReasonCode.INITIAL_PRICE);
        }
        if (ctx.trigger() == DecisionTrigger.COST_CHANGE) {
            reasons.add(ReasonCode.COST_CHANGE);
        }

        // S2 Floor, S3 Ceiling
        GuardrailCalculator.Guardrails g = GuardrailCalculator.guardrails(ctx.landedCost(), ctx.minMarginPct(),
                ctx.mapPrice(), ctx.mrp());
        steps.add(step("S2", "Floor", map("landed", str(ctx.landedCost()), "min_margin", str(ctx.minMarginPct()),
                "map", str(ctx.mapPrice())), str(g.floor()), "max(landed/(1-min_margin), map)"));
        boolean conflict = g.conflict();
        if (conflict) {
            reasons.add(ReasonCode.GUARDRAIL_CONFLICT_ESCALATED);
        }
        steps.add(step("S3", "Ceiling", map("mrp", str(ctx.mrp())), str(g.ceiling()),
                conflict ? "conflict: MRP below floor, escalate" : "MRP"));

        // S4 Competitor reference
        CompetitorReferenceResolver.Result comp = CompetitorReferenceResolver.resolve(ctx.observations(),
                ctx.decisionTs(), policy, faults.staleObservationsAsUsed());
        if (comp.dataStale()) {
            reasons.add(ReasonCode.COMPETITOR_DATA_STALE);
        }
        steps.add(step("S4", "Competitor ref", map("considered", Integer.toString(comp.assessments().size()),
                        "used", Integer.toString(comp.freshCount()), "stale", Integer.toString(comp.staleCount())),
                str(comp.referencePrice()), comp.referencePrice() != null ? "median of used"
                        : comp.dataStale() ? "all stale" : "none usable"));

        // S5 Target
        BigDecimal target;
        String s5;
        if (manual) {
            target = money(forced != null ? forced : ctx.manualTargetPrice());
            reasons.add(ReasonCode.MANUAL_OVERRIDE);
            s5 = "manual override";
        } else if (comp.referencePrice() != null) {
            target = money(comp.referencePrice().multiply(BigDecimal.ONE.add(policy.offsetFor(ctx.pricePosition()))));
            reasons.add(ReasonCode.COMPETITOR_ALIGN);
            s5 = "ref x (1 + " + ctx.pricePosition() + " offset)";
        } else if (prev != null) {
            target = prev;
            s5 = "keep previous";
        } else {
            target = money(ctx.mrp().multiply(policy.initialTargetRatio()));
            s5 = "MRP x " + policy.initialTargetRatio();
        }
        steps.add(step("S5", "Target", map("position", ctx.pricePosition().name()), str(target), s5));

        // S6 Demand and inventory
        DemandSignalCalculator.Signals signals = DemandSignalCalculator.signals(ctx.onHandUnits(), ctx.demand7dUnits(),
                ctx.salesDays7(), ctx.demand28dUnits(), ctx.salesDays28());
        boolean stockout = ctx.onHandUnits() == 0;
        String s6;
        if (stockout) {
            if (!manual && prev != null) {
                target = prev;
            }
            reasons.add(ReasonCode.STOCKOUT_HOLD);
            s6 = "stockout: hold, suppress markdowns";
        } else if (manual) {
            s6 = "manual: none";
        } else if (signals.historyDays() < policy.minHistoryDays()) {
            s6 = "history < " + policy.minHistoryDays() + " days: none";
        } else if (signals.daysOfCover().compareTo(policy.overstockDays()) > 0) {
            target = money(target.multiply(BigDecimal.ONE.add(policy.overstockAdj())));
            reasons.add(ReasonCode.OVERSTOCK_MARKDOWN);
            s6 = "overstock: x (1 " + policy.overstockAdj() + ")";
        } else if (signals.daysOfCover().compareTo(policy.lowCoverDays()) < 0
                && signals.velocityRatio().compareTo(policy.highVelocityRatio()) >= 0) {
            target = money(target.multiply(BigDecimal.ONE.add(policy.lowCoverAdj())));
            reasons.add(ReasonCode.LOW_COVER_INCREASE);
            s6 = "low cover, high velocity: x (1 + " + policy.lowCoverAdj() + ")";
        } else {
            s6 = "none";
        }
        steps.add(step("S6", "Demand/inventory", map("on_hand", Integer.toString(ctx.onHandUnits()),
                        "cover", str(signals.daysOfCover()), "velocity", str(signals.velocityRatio()),
                        "history_days", Integer.toString(signals.historyDays())), str(target), s6));

        // S7 Step limit
        BigDecimal stepped = target;
        String s7 = "n/a";
        if (prev != null && !manual) {
            BigDecimal lo = money(prev.multiply(BigDecimal.ONE.subtract(policy.maxStepChangePct())));
            BigDecimal hi = money(prev.multiply(BigDecimal.ONE.add(policy.maxStepChangePct())));
            stepped = target.max(lo).min(hi);
            if (stepped.compareTo(target) != 0) {
                reasons.add(ReasonCode.STEP_LIMIT_APPLIED);
                s7 = "limited to +/-" + policy.maxStepChangePct();
            } else {
                s7 = "within +/-" + policy.maxStepChangePct();
            }
        }
        steps.add(step("S7", "Step limit", map("prev", str(prev)), str(stepped), s7));

        // S8 Clamp
        BigDecimal candidate = forced != null ? stepped : g.clamp(stepped);
        String s8 = "inside";
        if (forced != null) {
            s8 = "inside"; // the faulty override path skips the clamp but reports as usual
        } else if (stepped.compareTo(g.floor()) < 0 || conflict) {
            reasons.add(ReasonCode.FLOOR_ENFORCED);
            s8 = "raised to floor";
        } else if (stepped.compareTo(g.ceiling()) > 0) {
            reasons.add(ReasonCode.CEILING_ENFORCED);
            s8 = "lowered to MRP";
        }
        steps.add(step("S8", "Clamp", map(), str(candidate), s8));

        // S9 Price point
        BigDecimal finalList;
        String s9;
        if (forced != null) {
            finalList = candidate;
            s9 = PricePointRounder.isPricePoint(candidate) ? "already a point" : "no point fits";
        } else if (conflict) {
            finalList = candidate;
            s9 = "conflict: floor unrounded";
        } else {
            PricePointRounder.Result rounded = PricePointRounder.round(candidate, g.floor(), g.ceiling());
            finalList = rounded.price();
            if (rounded.rounded()) {
                reasons.add(ReasonCode.PRICE_POINT_ROUNDED);
                s9 = "rounded";
            } else {
                s9 = PricePointRounder.isPricePoint(candidate) ? "already a point" : "no point fits";
            }
        }
        steps.add(step("S9", "Price point", map(), str(finalList), s9));

        // S10 Promotions (markdowns only; coupons are resolved per order)
        List<PromotionOffer> markdowns = ctx.promotions().stream()
                .filter(PromotionOffer::isMarkdown)
                .filter(o -> o.eligible(ctx.effectiveDate(), ctx.decisionTs(), Channel.ALL))
                .toList();
        PromotionStackResolver.Resolution resolution = PromotionStackResolver.resolve(new PromotionStackResolver.Request(
                finalList, List.of(), markdowns, ctx.effectiveDate(), 1, stockout, policy));
        steps.add(step("S10", "Promotions", map("eligible", Integer.toString(markdowns.size())),
                Integer.toString(resolution.applied().size()) + " applied",
                stockout ? "suppressed: stockout" : "by (priority, promotion_id)"));

        // S11 Margin on net
        List<PromotionStackResolver.Evaluation> evaluations = new ArrayList<>(resolution.evaluations());
        List<PromotionOffer> applied = new ArrayList<>(evaluations.stream()
                .filter(e -> e.status() == ApplicationStatus.APPLIED).map(PromotionStackResolver.Evaluation::offer)
                .toList());
        List<PromotionStackResolver.Applied> discounts = PromotionStackResolver.discounts(finalList, BigDecimal.ZERO,
                applied);
        BigDecimal net = finalList.subtract(PromotionStackResolver.total(discounts));
        BigDecimal required = applied.isEmpty() ? ctx.minMarginPct() : ctx.minPromoMarginPct();
        while (!applied.isEmpty() && !MarginCalculator.meetsMargin(faults.marginOnListPrice() ? finalList : net,
                ctx.landedCost(), required)) {
            PromotionOffer dropped = applied.remove(applied.size() - 1);
            for (int i = 0; i < evaluations.size(); i++) {
                PromotionStackResolver.Evaluation e = evaluations.get(i);
                if (e.promotionId().equals(dropped.promotionId())) {
                    evaluations.set(i, new PromotionStackResolver.Evaluation(e.offer(), e.sequenceNo(),
                            ApplicationStatus.REJECTED, ReasonCode.PROMO_REJECTED_MARGIN, zero()));
                }
            }
            discounts = PromotionStackResolver.discounts(finalList, BigDecimal.ZERO, applied);
            net = finalList.subtract(PromotionStackResolver.total(discounts));
            required = applied.isEmpty() ? ctx.minMarginPct() : ctx.minPromoMarginPct();
        }
        // Final per-promotion discounts after any removal.
        for (int i = 0; i < evaluations.size(); i++) {
            PromotionStackResolver.Evaluation e = evaluations.get(i);
            if (e.status() == ApplicationStatus.APPLIED) {
                BigDecimal d = discounts.stream().filter(a -> a.offer().promotionId().equals(e.promotionId()))
                        .map(PromotionStackResolver.Applied::discount).findFirst().orElseThrow();
                evaluations.set(i, new PromotionStackResolver.Evaluation(e.offer(), e.sequenceNo(), e.status(),
                        e.reason(), d));
            }
        }
        for (PromotionStackResolver.Evaluation e : evaluations) {
            reasons.add(e.reason());
        }
        BigDecimal marginPct = faults.marginOnListPrice()
                ? finalList.subtract(ctx.landedCost()).divide(finalList, 4, RoundingMode.HALF_UP)
                : MarginCalculator.netMargin(net, ctx.landedCost());
        BigDecimal markdownPct = finalList.subtract(net).divide(finalList, 4, RoundingMode.HALF_UP);
        long dropped = evaluations.stream().filter(e -> e.reason() == ReasonCode.PROMO_REJECTED_MARGIN).count();
        steps.add(step("S11", "Net margin", map("net", str(net), "required", str(required)), str(marginPct),
                dropped == 0 ? "(net - landed) / net" : dropped + " markdown(s) dropped for margin"));

        // S12 Outcome
        DecisionType type = DecisionType.HOLD;
        boolean large = false;
        if (prev != null) {
            int sign = finalList.compareTo(prev);
            type = sign > 0 ? DecisionType.INCREASE : sign < 0 ? DecisionType.DECREASE : DecisionType.HOLD;
            BigDecimal change = finalList.subtract(prev).abs();
            large = change.compareTo(prev.multiply(policy.autoApproveMaxChangePct())) > 0;
            if (large) {
                reasons.add(ReasonCode.LARGE_CHANGE_ESCALATED);
            }
            if (type == DecisionType.HOLD) {
                reasons.add(ReasonCode.NO_CHANGE);
            }
        }
        DecisionStatus status = conflict || large ? DecisionStatus.ESCALATED : DecisionStatus.APPROVED;
        List<ReasonCode> codes = new ArrayList<>(reasons);
        if (codes.isEmpty()) {
            codes.add(ReasonCode.NO_CHANGE);
        }
        List<ReasonCode> precedence = type == DecisionType.HOLD && prev != null ? HOLD_PRECEDENCE : CHANGE_PRECEDENCE;
        ReasonCode primary = precedence.stream().filter(codes::contains).findFirst().orElse(codes.get(0));
        steps.add(step("S12", "Outcome", map(), type + " " + status, primary.name()));

        return new PricingResult(g.floor(), g.ceiling(), conflict, comp, signals, target, candidate, finalList,
                evaluations, discounts, markdownPct, net, marginPct, required, type,
                manual ? DecisionSource.MANUAL_OVERRIDE : DecisionSource.ENGINE, status, primary, codes, steps);
    }

    private static ExplanationStep step(String id, String rule, Map<String, String> inputs, String output,
                                        String message) {
        return new ExplanationStep(id, rule, inputs, output, message);
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String str(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }
}
