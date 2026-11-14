package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import com.pricingsim.domain.enums.ActorRole;
import com.pricingsim.domain.enums.DecisionStatus;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.PriceDecision;
import com.pricingsim.domain.model.PriceDecisionObservation;
import com.pricingsim.domain.model.PriceDecisionPromotion;
import com.pricingsim.domain.model.PriceDecisionStatusEvent;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.GenerationContext;
import com.pricingsim.generator.master.PromotionCatalog;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.pricing.CompetitorReferenceResolver;
import com.pricingsim.pricing.PricingContext;
import com.pricingsim.pricing.PricingEngine;
import com.pricingsim.pricing.PricingResult;
import com.pricingsim.pricing.PromotionStackResolver;

/**
 * Prices through {@link PricingEngine} decisions (PLAN.md §2 assumption 7, §7.2 step 5.2–5.4).
 *
 * <p>Decision days, in this precedence when several apply: INITIAL on the SKU's first active day; COST_CHANGE on the
 * 1st of a month whose landed cost changed; STOCKOUT_EVENT on the day after closing stock first hits 0; WEEKLY_REVIEW
 * every Monday (1% of weekly reviews are a legitimate analyst MANUAL_OVERRIDE within ±5% of the previous price). On
 * other days the decision in effect continues. Decisions are made at 06:00Z and take effect that day.
 *
 * <p>Each decision writes {@code price_decision}, {@code price_decision_observation}, {@code price_decision_promotion}
 * and its status events (PROPOSED → APPROVED → APPLIED, or PROPOSED → ESCALATED → APPROVED → APPLIED with an
 * OPS_MANAGER for guardrail conflicts and a MERCH_ANALYST for large changes; the previous decision becomes
 * SUPERSEDED). A decision row is held until it is superseded or the run ends, because its final status is not known
 * earlier; status events are numbered at the end of each day in (event_ts, decision_id) order.
 */
public final class EnginePriceSetter implements PriceSetter {

    static final LocalTimeOfDay DECISION_TIME = new LocalTimeOfDay(6);
    static final double MANUAL_OVERRIDE_SHARE = 0.01;
    static final double MANUAL_MAX_MOVE = 0.05;
    static final int APPROVAL_MIN_MINUTES = 10;
    static final int APPROVAL_MAX_MINUTES = 120;

    /** Hour of day (UTC) at which decisions are computed. */
    record LocalTimeOfDay(int hour) {
        Instant on(LocalDate day) {
            return day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(hour * 3600L);
        }
    }

    /** Decision counts for the run summary. */
    public static final class Stats {
        long decisions;
        long escalated;
        long manualOverrides;
        long superseded;
        final Map<DecisionTrigger, Long> byTrigger = new EnumMap<>(DecisionTrigger.class);
        final Map<ReasonCode, Long> byReason = new EnumMap<>(ReasonCode.class);

        public long decisions() {
            return decisions;
        }

        public long escalated() {
            return escalated;
        }

        public long manualOverrides() {
            return manualOverrides;
        }

        public Map<DecisionTrigger, Long> byTrigger() {
            return byTrigger;
        }

        /** Decisions whose reason codes include {@code code}. */
        public long withReason(ReasonCode code) {
            return byReason.getOrDefault(code, 0L);
        }
    }

    private final GenerationContext ctx;
    private final PricingEngine engine;
    private final Map<String, List<CompetitorObservation>> observations;
    private final PromotionCatalog promotions;
    private final RowSink sink;
    private final SimulationHooks hooks;
    private final List<PriceDecisionStatusEvent> dayEvents = new ArrayList<>();
    private final Map<String, SkuState> heldBySku = new java.util.TreeMap<>();
    private final Stats stats = new Stats();

    public EnginePriceSetter(GenerationContext ctx, PricingEngine engine,
                             Map<String, List<CompetitorObservation>> observations, PromotionCatalog promotions,
                             RowSink sink, SimulationHooks hooks) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.promotions = Objects.requireNonNull(promotions, "promotions");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public Stats stats() {
        return stats;
    }

    @Override
    public DailyPrice priceFor(SkuState state, LocalDate day, CostRecord cost, int stockAtDecision) {
        DecisionTrigger trigger = trigger(state, day, cost);
        if (trigger == null) {
            DailyPrice replacement = hooks.nonDecisionDay(state, day, state.currentPrice());
            if (replacement != null) {
                state.setCurrentPrice(replacement);
            }
            return state.currentPrice();
        }
        Product product = state.product();
        Random r = state.decisionRandom();
        BigDecimal manualTarget = null;
        if (trigger == DecisionTrigger.WEEKLY_REVIEW) {
            boolean manual = Distributions.bernoulli(r, MANUAL_OVERRIDE_SHARE);
            double move = Distributions.uniform(r, -MANUAL_MAX_MOVE, MANUAL_MAX_MOVE);
            if (manual) {
                manualTarget = state.listPrice().multiply(BigDecimal.ONE.add(new BigDecimal(move)))
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }
        Instant ts = DECISION_TIME.on(day);
        PriceDecision previous = state.currentDecision();
        PricingContext context = new PricingContext(product.skuId(), day, ts, trigger, product.mrp(),
                product.mapPrice(), product.minMarginPct(), product.minPromoMarginPct(), product.pricePosition(),
                cost.costRecordId(), cost.landedCost(), state.listPrice(),
                previous == null ? null : previous.decisionId(), stockAtDecision,
                state.unitsSoldLast(7), state.historyDaysLast(7), state.unitsSoldLast(28), state.historyDaysLast(28),
                visibleObservations(product.skuId(), ts), promotions.markdownsFor(product.skuId()), manualTarget);
        PricingResult baseline = engine.price(context);
        PricingEngine.Faults faults = hooks.decisionFaults(trigger, context, baseline);
        PricingResult result = faults == null ? baseline : engine.price(context, faults);

        String decisionId = ctx.nextId(IdFactory.Sequence.DECISION, day.getYear());
        hooks.afterDecision(context, baseline, result, decisionId);
        Instant appliedAt = statusEvents(decisionId, result, ts, r);
        if (previous != null) {
            dayEvents.add(new PriceDecisionStatusEvent(null, previous.decisionId(), DecisionStatus.APPLIED,
                    DecisionStatus.SUPERSEDED, appliedAt, ActorRole.SYSTEM, "Superseded by " + decisionId));
            sink.accept(TableRegistry.PRICE_DECISION, withStatus(previous, DecisionStatus.SUPERSEDED));
            stats.superseded++;
        }
        writeBridges(decisionId, result);

        PriceDecision decision = new PriceDecision(decisionId, product.skuId(), ts, day, trigger,
                result.decisionSource(), context.previousDecisionId(), cost.costRecordId(), cost.landedCost(),
                context.prevListPrice(), result.floorPrice(), result.ceilingPrice(),
                result.competitor().referencePrice(), result.competitor().freshCount(),
                result.competitor().staleCount(), stockAtDecision, context.demand7dUnits(),
                context.demand28dUnits(), result.signals().velocityRatio(), result.signals().daysOfCover(),
                result.targetPrice(), result.finalListPrice(), result.markdownDiscountPct(),
                result.expectedNetPrice(), result.marginPct(), result.requiredMarginPct(), result.decisionType(),
                result.primaryReasonCode(), result.reasonCodesJoined(), result.explanationJson(),
                result.ruleVersion(), DecisionStatus.APPLIED);
        state.setCurrentDecision(decision);
        heldBySku.put(product.skuId(), state);
        DailyPrice price = new DailyPrice(result.finalListPrice(), result.expectedNetPrice(),
                result.primaryReasonCode(), decisionId, result.appliedMarkdowns());
        state.setCurrentPrice(price);

        stats.decisions++;
        stats.byTrigger.merge(trigger, 1L, Long::sum);
        for (ReasonCode code : result.reasonCodes()) {
            stats.byReason.merge(code, 1L, Long::sum);
        }
        if (result.escalated()) {
            stats.escalated++;
        }
        if (manualTarget != null) {
            stats.manualOverrides++;
        }
        return price;
    }

    /** The trigger for today, or null when the current decision continues. */
    DecisionTrigger trigger(SkuState state, LocalDate day, CostRecord cost) {
        if (state.currentDecision() == null) {
            return DecisionTrigger.INITIAL;
        }
        if (day.getDayOfMonth() == 1 && cost.changePct() != null && cost.changePct().signum() != 0
                && !cost.costRecordId().equals(state.currentDecision().costRecordId())) {
            return DecisionTrigger.COST_CHANGE;
        }
        Integer before = state.closingBeforeYesterday();
        if (state.hasClosingHistory() && state.onHand() == 0 && (before == null || before > 0)) {
            return DecisionTrigger.STOCKOUT_EVENT;
        }
        if (day.getDayOfWeek() == DayOfWeek.MONDAY) {
            return DecisionTrigger.WEEKLY_REVIEW;
        }
        return null;
    }

    /** Observations already captured and ingested by {@code ts}; the resolver re-applies the same rule. */
    private List<CompetitorObservation> visibleObservations(String skuId, Instant ts) {
        List<CompetitorObservation> all = observations.getOrDefault(skuId, List.of());
        List<CompetitorObservation> visible = new ArrayList<>();
        for (CompetitorObservation o : all) {
            if (o.sourceCapturedAt().isAfter(ts)) {
                break; // sorted by capture time
            }
            if (!o.ingestedAt().isAfter(ts)) {
                visible.add(o);
            }
        }
        return visible;
    }

    /** Adds the decision's own status events; returns the APPLIED timestamp. */
    private Instant statusEvents(String decisionId, PricingResult result, Instant ts, Random r) {
        boolean manual = result.reasonCodes().contains(ReasonCode.MANUAL_OVERRIDE);
        ActorRole proposer = manual ? ActorRole.MERCH_ANALYST : ActorRole.SYSTEM;
        dayEvents.add(event(decisionId, null, DecisionStatus.PROPOSED, ts, proposer,
                manual ? "Analyst price override" : "Proposed by engine (" + result.primaryReasonCode() + ")"));
        Instant approvedAt;
        if (result.escalated()) {
            boolean conflict = result.guardrailConflict();
            dayEvents.add(event(decisionId, DecisionStatus.PROPOSED, DecisionStatus.ESCALATED, ts.plusSeconds(1),
                    ActorRole.SYSTEM, conflict ? "Guardrail conflict: floor above MRP" : "Change above auto-approve limit"));
            approvedAt = ts.plusSeconds(60L * Distributions.uniformInt(r, APPROVAL_MIN_MINUTES, APPROVAL_MAX_MINUTES));
            dayEvents.add(event(decisionId, DecisionStatus.ESCALATED, DecisionStatus.APPROVED, approvedAt,
                    conflict ? ActorRole.OPS_MANAGER : ActorRole.MERCH_ANALYST, "Approved after review"));
        } else {
            approvedAt = ts.plusSeconds(1);
            dayEvents.add(event(decisionId, DecisionStatus.PROPOSED, DecisionStatus.APPROVED, approvedAt,
                    manual ? ActorRole.MERCH_ANALYST : ActorRole.SYSTEM,
                    manual ? "Approved by analyst" : "Auto-approved within limits"));
        }
        Instant appliedAt = approvedAt.plusSeconds(1);
        dayEvents.add(event(decisionId, DecisionStatus.APPROVED, DecisionStatus.APPLIED, appliedAt, ActorRole.SYSTEM,
                "Price applied"));
        return appliedAt;
    }

    private void writeBridges(String decisionId, PricingResult result) {
        for (CompetitorReferenceResolver.Assessment a : result.competitor().assessments()) {
            sink.accept(TableRegistry.PRICE_DECISION_OBSERVATION,
                    new PriceDecisionObservation(decisionId, a.observationId(), a.usage(), a.ageHours()));
        }
        for (PromotionStackResolver.Evaluation e : result.promotions()) {
            sink.accept(TableRegistry.PRICE_DECISION_PROMOTION, new PriceDecisionPromotion(decisionId,
                    e.promotionId(), e.status(), e.reason(), result.effectiveDiscountPct(e)));
        }
    }

    @Override
    public void endOfDay(LocalDate day) {
        dayEvents.sort(Comparator.comparing(PriceDecisionStatusEvent::eventTs)
                .thenComparing(PriceDecisionStatusEvent::decisionId));
        for (PriceDecisionStatusEvent e : dayEvents) {
            int year = e.eventTs().atZone(ZoneOffset.UTC).getYear();
            sink.accept(TableRegistry.PRICE_DECISION_STATUS_EVENT, new PriceDecisionStatusEvent(
                    ctx.nextId(IdFactory.Sequence.DECISION_EVENT, year), e.decisionId(), e.fromStatus(), e.toStatus(),
                    e.eventTs(), e.actorRole(), e.note()));
        }
        dayEvents.clear();
    }

    @Override
    public void finish() {
        for (SkuState state : heldBySku.values()) {
            sink.accept(TableRegistry.PRICE_DECISION, state.currentDecision());
        }
        heldBySku.clear();
    }

    private static PriceDecisionStatusEvent event(String decisionId, DecisionStatus from, DecisionStatus to,
                                                  Instant ts, ActorRole actor, String note) {
        return new PriceDecisionStatusEvent(null, decisionId, from, to, ts, actor, note);
    }

    private static PriceDecision withStatus(PriceDecision d, DecisionStatus status) {
        return new PriceDecision(d.decisionId(), d.skuId(), d.decisionTs(), d.effectiveDate(), d.trigger(),
                d.decisionSource(), d.previousDecisionId(), d.costRecordId(), d.landedCost(), d.prevListPrice(),
                d.floorPrice(), d.ceilingPrice(), d.competitorRefPrice(), d.competitorFreshCount(),
                d.competitorStaleCount(), d.onHandUnits(), d.demand7dUnits(), d.demand28dUnits(), d.velocityRatio(),
                d.daysOfCover(), d.targetPrice(), d.finalListPrice(), d.markdownDiscountPct(), d.expectedNetPrice(),
                d.marginPct(), d.requiredMarginPct(), d.decisionType(), d.primaryReasonCode(), d.reasonCodes(),
                d.explanation(), d.ruleVersion(), status);
    }
}
