package com.pricingsim.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.DecisionSource;
import com.pricingsim.domain.enums.DecisionStatus;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.DecisionType;
import com.pricingsim.domain.enums.PricePosition;
import com.pricingsim.domain.enums.ReasonCode;

/**
 * Engine behaviour per PLAN.md §8. Defaults (see {@link Contexts}): MRP 199, landed 100, min margin 0.20 → floor
 * 125.00, previous price 169, 50 on hand, cover 25 days, velocity 1.0. Every {@link ReasonCode} must be produced by at
 * least one test here.
 */
class PricingEngineTest {

    private static final PricingEngine ENGINE = new PricingEngine(PricingPolicy.defaults());
    private static final Set<ReasonCode> SEEN = EnumSet.noneOf(ReasonCode.class);

    private static PricingResult price(Contexts c) {
        PricingResult r = ENGINE.price(c.build());
        SEEN.addAll(r.reasonCodes());
        r.promotions().forEach(e -> SEEN.add(e.reason()));
        return r;
    }

    @AfterAll
    static void everyReasonCodeIsReachable() {
        assertThat(SEEN).containsExactlyInAnyOrder(ReasonCode.values());
    }

    @Test
    void initialPriceWithoutCompetitors() {
        PricingResult r = price(Contexts.initial());
        // 199 × 0.95 = 189.05 → nearest price point 189.
        assertThat(r.targetPrice()).isEqualByComparingTo("189.05");
        assertThat(r.finalListPrice()).isEqualByComparingTo("189.00");
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.INITIAL_PRICE, ReasonCode.PRICE_POINT_ROUNDED);
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.INITIAL_PRICE);
        assertThat(r.decisionType()).isEqualTo(DecisionType.HOLD);
        assertThat(r.status()).isEqualTo(DecisionStatus.APPROVED);
    }

    @Test
    void holdWhenNothingChanges() {
        PricingResult r = price(Contexts.weekly());
        assertThat(r.finalListPrice()).isEqualByComparingTo("169.00");
        assertThat(r.decisionType()).isEqualTo(DecisionType.HOLD);
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.NO_CHANGE);
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.NO_CHANGE);
    }

    @Test
    void competitorAlignDecrease() {
        PricingResult r = price(Contexts.weekly().observation(Competitor.COMP_ALPHA, "159.00", 2));
        assertThat(r.competitor().referencePrice()).isEqualByComparingTo("159.00");
        assertThat(r.finalListPrice()).isEqualByComparingTo("159.00");
        assertThat(r.decisionType()).isEqualTo(DecisionType.DECREASE);
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.COMPETITOR_ALIGN);
    }

    @Test
    void staleCompetitorDataHolds() {
        PricingResult r = price(Contexts.weekly().observation(Competitor.COMP_ALPHA, "139.00", 80));
        assertThat(r.competitor().referencePrice()).isNull();
        assertThat(r.competitor().staleCount()).isEqualTo(1);
        assertThat(r.finalListPrice()).isEqualByComparingTo("169.00");
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.COMPETITOR_DATA_STALE, ReasonCode.NO_CHANGE);
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.COMPETITOR_DATA_STALE);
    }

    @Test
    void stepLimitBoundsTheMove() {
        // ref 129; limit 169 × 0.92 = 155.48 → point 159.
        PricingResult r = price(Contexts.weekly().observation(Competitor.COMP_ALPHA, "129.00", 2));
        assertThat(r.candidatePrice()).isEqualByComparingTo("155.48");
        assertThat(r.finalListPrice()).isEqualByComparingTo("159.00");
        assertThat(r.reasonCodes()).contains(ReasonCode.STEP_LIMIT_APPLIED);
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.STEP_LIMIT_APPLIED);
    }

    @Test
    void floorEnforced() {
        // ref 110, prev 119 → 110 inside the step limit, raised to the floor 125 → point 129.
        PricingResult r = price(Contexts.weekly().prev("119.00").observation(Competitor.COMP_ALPHA, "110.00", 2));
        assertThat(r.candidatePrice()).isEqualByComparingTo("125.00");
        assertThat(r.finalListPrice()).isEqualByComparingTo("129.00");
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.FLOOR_ENFORCED);
    }

    @Test
    void ceilingEnforced() {
        // PREMIUM: 195 × 1.05 = 204.75 → above MRP 199.
        PricingResult r = price(Contexts.weekly().prev("195.00").position(PricePosition.PREMIUM)
                .observation(Competitor.COMP_ALPHA, "195.00", 2));
        assertThat(r.finalListPrice()).isEqualByComparingTo("199.00");
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.CEILING_ENFORCED);
    }

    @Test
    void pricePointRoundingAlone() {
        PricingResult r = price(Contexts.weekly().prev("170.00"));
        assertThat(r.finalListPrice()).isEqualByComparingTo("169.00");
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.PRICE_POINT_ROUNDED);
        assertThat(r.decisionType()).isEqualTo(DecisionType.DECREASE);
    }

    @Test
    void overstockMarkdown() {
        // cover = 400 × 28 / 56 = 200 > 60 → 169 × 0.97 = 163.93 → 159.
        PricingResult r = price(Contexts.weekly().onHand(400));
        assertThat(r.signals().daysOfCover()).isEqualByComparingTo("200.00");
        assertThat(r.finalListPrice()).isEqualByComparingTo("159.00");
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.OVERSTOCK_MARKDOWN);
    }

    @Test
    void lowCoverWithHighVelocityIncreases() {
        // velocity (21/7)/(56/28) = 1.5, cover 10 × 28 / 56 = 5 → 169 × 1.03 = 174.07 → 179.
        PricingResult r = price(Contexts.weekly().onHand(10).sales(21, 7, 56, 28));
        assertThat(r.signals().velocityRatio()).isEqualByComparingTo("1.5000");
        assertThat(r.finalListPrice()).isEqualByComparingTo("179.00");
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.LOW_COVER_INCREASE);
        assertThat(r.decisionType()).isEqualTo(DecisionType.INCREASE);
    }

    @Test
    void noDemandAdjustmentWithoutEnoughHistory() {
        PricingResult r = price(Contexts.weekly().onHand(400).sales(2, 3, 2, 3));
        assertThat(r.reasonCodes()).doesNotContain(ReasonCode.OVERSTOCK_MARKDOWN);
        assertThat(r.finalListPrice()).isEqualByComparingTo("169.00");
    }

    @Test
    void stockoutHoldsPriceAndSuppressesMarkdowns() {
        PricingResult r = price(Contexts.weekly().onHand(0).observation(Competitor.COMP_ALPHA, "149.00", 2)
                .promotions(Offers.markdownPct("PROMO-2026-000001", "0.10").build()));
        assertThat(r.finalListPrice()).isEqualByComparingTo("169.00");
        assertThat(r.expectedNetPrice()).isEqualByComparingTo("169.00");
        assertThat(r.promotions()).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo(ReasonCode.PROMO_SUPPRESSED_STOCKOUT));
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.STOCKOUT_HOLD);
    }

    @Test
    void costChangeLargeIncreaseIsEscalated() {
        // landed 150 → floor 187.50 → 189; Δ 20/169 = 11.8% > 10%.
        PricingResult r = price(Contexts.weekly().trigger(DecisionTrigger.COST_CHANGE).landed("150.00"));
        assertThat(r.finalListPrice()).isEqualByComparingTo("189.00");
        assertThat(r.reasonCodes()).contains(ReasonCode.COST_CHANGE, ReasonCode.FLOOR_ENFORCED,
                ReasonCode.LARGE_CHANGE_ESCALATED);
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.COST_CHANGE);
        assertThat(r.status()).isEqualTo(DecisionStatus.ESCALATED);
        assertThat(r.guardrailConflict()).isFalse();
    }

    @Test
    void guardrailConflictPricesAtFloorAndEscalates() {
        // landed 170 → floor 212.50 > MRP 199.
        PricingResult r = price(Contexts.weekly().trigger(DecisionTrigger.COST_CHANGE).landed("170.00"));
        assertThat(r.guardrailConflict()).isTrue();
        assertThat(r.finalListPrice()).isEqualByComparingTo("212.50");
        assertThat(r.status()).isEqualTo(DecisionStatus.ESCALATED);
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.GUARDRAIL_CONFLICT_ESCALATED);
        assertThat(r.marginPct()).isEqualByComparingTo("0.2000");
    }

    @Test
    void manualOverrideSkipsTheStepLimitButNotGuardrails() {
        PricingResult r = price(Contexts.weekly().manual("120.00"));
        assertThat(r.decisionSource()).isEqualTo(DecisionSource.MANUAL_OVERRIDE);
        assertThat(r.finalListPrice()).as("raised to floor 125 → point 129").isEqualByComparingTo("129.00");
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.MANUAL_OVERRIDE);
        PricingResult hold = price(Contexts.weekly().manual("171.00"));
        assertThat(hold.finalListPrice()).isEqualByComparingTo("169.00");
        assertThat(hold.primaryReasonCode()).isEqualTo(ReasonCode.MANUAL_OVERRIDE);
    }

    @Test
    void promotionsAppliedAndRejected() {
        PricingResult r = price(Contexts.weekly().promotions(
                Offers.markdownPct("PROMO-2026-000001", "0.10").priority(1).maxStack(3).maxCombined("0.30").build(),
                Offers.markdownPct("PROMO-2026-000002", "0.05").priority(2).exclusive().build(),
                Offers.markdownPct("PROMO-2026-000003", "0.05").priority(3).excludes("PROMO-2026-000001").build(),
                Offers.markdownPct("PROMO-2026-000004", "0.25").priority(4).maxStack(3).build(),
                Offers.markdownPct("PROMO-2026-000005", "0.05").priority(5).maxStack(3).minQty(2).build(),
                Offers.markdownAmount("PROMO-2026-000006", "5.00").priority(6).maxStack(3).build(),
                Offers.markdownAmount("PROMO-2026-000007", "5.00").priority(7).maxStack(2).build()));
        assertThat(r.promotions()).extracting(PromotionStackResolver.Evaluation::reason).containsExactly(
                ReasonCode.PROMO_APPLIED,
                ReasonCode.PROMO_REJECTED_EXCLUSIVE,
                ReasonCode.PROMO_REJECTED_EXCLUDED_PAIR,
                ReasonCode.PROMO_REJECTED_DISCOUNT_CAP,
                ReasonCode.PROMO_REJECTED_MIN_QTY,
                ReasonCode.PROMO_APPLIED,
                ReasonCode.PROMO_REJECTED_STACK_LIMIT);
        // 169 → 16.90 → 152.10 → 5.00 → 147.10
        assertThat(r.expectedNetPrice()).isEqualByComparingTo("147.10");
        assertThat(r.markdownDiscountPct()).isEqualByComparingTo("0.1296");
        assertThat(r.requiredMarginPct()).isEqualByComparingTo("0.1000");
        assertThat(r.appliedMarkdowns()).hasSize(2);
    }

    @Test
    void marginFailureDropsTheLastAppliedMarkdown() {
        PricingResult r = price(Contexts.weekly().promotions(
                Offers.markdownPct("PROMO-2026-000001", "0.10").priority(1).maxStack(3).build(),
                Offers.markdownPct("PROMO-2026-000002", "0.30").priority(2).maxStack(3).maxCombined("0.45").build()));
        // Both: 169 → 152.10 → 106.47, margin 6.1% < 10% → drop PROMO-2 → 152.10, margin 34.3%.
        assertThat(r.promotions()).extracting(PromotionStackResolver.Evaluation::reason)
                .containsExactly(ReasonCode.PROMO_APPLIED, ReasonCode.PROMO_REJECTED_MARGIN);
        assertThat(r.expectedNetPrice()).isEqualByComparingTo("152.10");
        assertThat(r.marginPct()).isEqualByComparingTo("0.3425");
    }

    @Test
    void ineligibleMarkdownsAreNotConsidered() {
        PricingResult r = price(Contexts.weekly().promotions(
                Offers.couponPct("PROMO-2026-000001", "0.10").build(),
                Offers.markdownPct("PROMO-2026-000002", "0.10")
                        .window(Contexts.DAY.plusDays(1), Contexts.DAY.plusDays(7)).build(),
                Offers.markdownPct("PROMO-2026-000003", "0.10").cancelledAt(Contexts.TS).build()));
        assertThat(r.promotions()).isEmpty();
    }

    @Test
    void explanationHasTwelveParseableSteps() throws Exception {
        PricingResult r = price(Contexts.weekly().observation(Competitor.COMP_BETA, "149.00", 3)
                .promotions(Offers.markdownPct("PROMO-2026-000001", "0.10").build()));
        assertThat(r.explanation()).extracting(ExplanationStep::step)
                .containsExactly("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11", "S12");
        JsonNode json = new ObjectMapper().readTree(r.explanationJson());
        assertThat(json.isArray()).isTrue();
        assertThat(json).hasSize(12);
        assertThat(json.get(1).get("output").asText()).isEqualTo("125.00");
        assertThat(r.ruleVersion()).isEqualTo("pricing-rules-v1");
        assertThat(r.reasonCodesJoined()).isEqualTo(String.join("|", r.reasonCodes().stream().map(Enum::name).toList()));
    }

    /** The worked example in docs/pricing_rules.md; keep the two in step. */
    @Test
    void workedExampleFromPricingRulesDoc() {
        java.time.Instant ts = Contexts.TS;
        PricingResult r = price(Contexts.weekly().mrp("499.00").landed("300.00").minMargin("0.2500")
                .minPromoMargin("0.1500").prev("459.00")
                .observation(Competitor.COMP_ALPHA, "419.00", ts.minusSeconds(6 * 3600), "0.920")
                .observation(Competitor.COMP_ALPHA, "429.00", ts.minusSeconds(50 * 3600), "0.950")
                .observation(Competitor.COMP_BETA, "409.00", ts.minusSeconds(30 * 3600), "0.880")
                .observation(Competitor.COMP_GAMMA, "389.00", ts.minusSeconds(90 * 3600), "0.990")
                .promotions(
                        Offers.markdownPct("PROMO-2026-000101", "0.10").priority(10).maxStack(3).maxCombined("0.40")
                                .build(),
                        Offers.markdownAmount("PROMO-2026-000102", "30.00").priority(20).maxStack(3).build(),
                        Offers.markdownPct("PROMO-2026-000103", "0.05").priority(30).exclusive().build()));
        assertThat(r.floorPrice()).isEqualByComparingTo("400.00");
        assertThat(r.competitor().referencePrice()).isEqualByComparingTo("414.00");
        assertThat(r.competitor().assessments()).extracting(a -> a.usage().name()).containsExactly(
                "USED", "EXCLUDED_SUPERSEDED", "USED", "EXCLUDED_STALE");
        assertThat(r.targetPrice()).isEqualByComparingTo("414.00");
        assertThat(r.candidatePrice()).isEqualByComparingTo("422.28");
        assertThat(r.finalListPrice()).isEqualByComparingTo("419.00");
        assertThat(r.promotions()).extracting(PromotionStackResolver.Evaluation::reason).containsExactly(
                ReasonCode.PROMO_APPLIED, ReasonCode.PROMO_REJECTED_MARGIN, ReasonCode.PROMO_REJECTED_EXCLUSIVE);
        assertThat(r.expectedNetPrice()).isEqualByComparingTo("377.10");
        assertThat(r.markdownDiscountPct()).isEqualByComparingTo("0.1000");
        assertThat(r.marginPct()).isEqualByComparingTo("0.2045");
        assertThat(r.requiredMarginPct()).isEqualByComparingTo("0.1500");
        assertThat(r.decisionType()).isEqualTo(DecisionType.DECREASE);
        assertThat(r.status()).isEqualTo(DecisionStatus.APPROVED);
        assertThat(r.primaryReasonCode()).isEqualTo(ReasonCode.STEP_LIMIT_APPLIED);
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.COMPETITOR_ALIGN, ReasonCode.STEP_LIMIT_APPLIED,
                ReasonCode.PRICE_POINT_ROUNDED, ReasonCode.PROMO_APPLIED, ReasonCode.PROMO_REJECTED_MARGIN,
                ReasonCode.PROMO_REJECTED_EXCLUSIVE);
    }

    @Test
    void deterministicForEqualInputs() {
        Contexts c = Contexts.weekly().observation(Competitor.COMP_ALPHA, "159.00", 5)
                .observation(Competitor.COMP_BETA, "149.00", 50)
                .promotions(Offers.markdownPct("PROMO-2026-000001", "0.10").build());
        PricingResult a = ENGINE.price(c.build());
        PricingResult b = ENGINE.price(c.build());
        assertThat(a).isEqualTo(b);
        assertThat(a.explanationJson()).isEqualTo(b.explanationJson());
        assertThat(List.of(a.reasonCodes())).isEqualTo(List.of(b.reasonCodes()));
    }
}
