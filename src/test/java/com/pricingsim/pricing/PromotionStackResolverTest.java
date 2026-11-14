package com.pricingsim.pricing;

import static com.pricingsim.pricing.Offers.JAN_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.ReasonCode;

class PromotionStackResolverTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 12);
    private static final PricingPolicy POLICY = PricingPolicy.defaults();

    private static PromotionStackResolver.Resolution resolve(String base, List<PromotionOffer> candidates) {
        return resolve(base, List.of(), candidates, 1, false);
    }

    private static PromotionStackResolver.Resolution resolve(String base, List<PromotionStackResolver.Applied> seeded,
                                                             List<PromotionOffer> candidates, int qty,
                                                             boolean suppressed) {
        return PromotionStackResolver.resolve(new PromotionStackResolver.Request(new BigDecimal(base), seeded,
                candidates, DAY, qty, suppressed, POLICY));
    }

    private static ReasonCode reasonOf(PromotionStackResolver.Resolution r, String id) {
        return r.evaluations().stream().filter(e -> e.promotionId().equals(id)).findFirst().orElseThrow().reason();
    }

    private static Offers md(String id, String pct) {
        return Offers.markdownPct(id, pct).maxStack(3).maxCombined("0.45");
    }

    @Test
    void appliesInPriorityThenIdOrder() {
        PromotionOffer a = md("PROMO-2026-000002", "0.10").priority(20).build();
        PromotionOffer b = md("PROMO-2026-000001", "0.05").priority(20).build();
        PromotionOffer c = md("PROMO-2026-000003", "0.05").priority(10).build();
        PromotionStackResolver.Resolution r = resolve("100.00", List.of(a, b, c));
        assertThat(r.evaluations()).extracting(PromotionStackResolver.Evaluation::promotionId)
                .containsExactly("PROMO-2026-000003", "PROMO-2026-000001", "PROMO-2026-000002");
        assertThat(r.evaluations()).extracting(PromotionStackResolver.Evaluation::sequenceNo).containsExactly(1, 2, 3);
        // 100 → 5.00 → 95.00 → 4.75 → 90.25 → 9.03 (9.025 HALF_UP) → 81.22
        assertThat(r.net()).isEqualByComparingTo("81.22");
        assertThat(r.applied()).extracting(PromotionStackResolver.Applied::discount)
                .containsExactly(new BigDecimal("5.00"), new BigDecimal("4.75"), new BigDecimal("9.03"));
    }

    @Test
    void exclusiveFirstBlocksLaterOnes() {
        PromotionOffer excl = md("PROMO-2026-000001", "0.10").priority(1).exclusive().build();
        PromotionOffer other = md("PROMO-2026-000002", "0.05").priority(2).build();
        PromotionStackResolver.Resolution r = resolve("100.00", List.of(excl, other));
        assertThat(reasonOf(r, "PROMO-2026-000001")).isEqualTo(ReasonCode.PROMO_APPLIED);
        assertThat(reasonOf(r, "PROMO-2026-000002")).isEqualTo(ReasonCode.PROMO_REJECTED_EXCLUSIVE);
    }

    @Test
    void exclusiveLaterIsRejectedWhenSomethingIsApplied() {
        PromotionOffer first = md("PROMO-2026-000001", "0.05").priority(1).build();
        PromotionOffer excl = md("PROMO-2026-000002", "0.20").priority(2).exclusive().build();
        PromotionStackResolver.Resolution r = resolve("100.00", List.of(first, excl));
        assertThat(reasonOf(r, "PROMO-2026-000002")).isEqualTo(ReasonCode.PROMO_REJECTED_EXCLUSIVE);
        assertThat(r.net()).isEqualByComparingTo("95.00");
    }

    @Test
    void excludedPairInEitherDirection() {
        PromotionOffer a = md("PROMO-2026-000001", "0.05").priority(1).excludes("PROMO-2026-000002").build();
        PromotionOffer b = md("PROMO-2026-000002", "0.05").priority(2).build();
        assertThat(reasonOf(resolve("100.00", List.of(a, b)), "PROMO-2026-000002"))
                .isEqualTo(ReasonCode.PROMO_REJECTED_EXCLUDED_PAIR);
        PromotionOffer a2 = md("PROMO-2026-000001", "0.05").priority(1).build();
        PromotionOffer b2 = md("PROMO-2026-000002", "0.05").priority(2).excludes("PROMO-2026-000001").build();
        assertThat(reasonOf(resolve("100.00", List.of(a2, b2)), "PROMO-2026-000002"))
                .isEqualTo(ReasonCode.PROMO_REJECTED_EXCLUDED_PAIR);
    }

    @Test
    void stackLimitIsTheMinimumOverTheSet() {
        PromotionOffer a = md("PROMO-2026-000001", "0.05").priority(1).build();            // max 3
        PromotionOffer b = Offers.markdownPct("PROMO-2026-000002", "0.05").priority(2).maxStack(2).build();
        PromotionOffer c = md("PROMO-2026-000003", "0.05").priority(3).build();            // max 3, but b says 2
        PromotionStackResolver.Resolution r = resolve("100.00", List.of(a, b, c));
        assertThat(reasonOf(r, "PROMO-2026-000002")).isEqualTo(ReasonCode.PROMO_APPLIED);
        assertThat(reasonOf(r, "PROMO-2026-000003")).isEqualTo(ReasonCode.PROMO_REJECTED_STACK_LIMIT);
        // Without any MAX_STACK_COUNT rule the policy default (2) applies.
        PromotionOffer x = Offers.markdownPct("PROMO-2026-000011", "0.05").priority(1).build();
        PromotionOffer y = Offers.markdownPct("PROMO-2026-000012", "0.05").priority(2).build();
        PromotionOffer z = Offers.markdownPct("PROMO-2026-000013", "0.05").priority(3).build();
        assertThat(reasonOf(resolve("100.00", List.of(x, y, z)), "PROMO-2026-000013"))
                .isEqualTo(ReasonCode.PROMO_REJECTED_STACK_LIMIT);
    }

    @Test
    void combinedDiscountCap() {
        PromotionOffer a = Offers.markdownPct("PROMO-2026-000001", "0.30").priority(1).maxStack(3).maxCombined("0.40")
                .build();
        PromotionOffer b = md("PROMO-2026-000002", "0.15").priority(2).build(); // 30% then 15% of 70 = 40.5% > 40%
        PromotionOffer c = md("PROMO-2026-000003", "0.10").priority(3).build(); // 30% then 10% of 70 = 37%
        PromotionStackResolver.Resolution r = resolve("100.00", List.of(a, b, c));
        assertThat(reasonOf(r, "PROMO-2026-000002")).isEqualTo(ReasonCode.PROMO_REJECTED_DISCOUNT_CAP);
        assertThat(reasonOf(r, "PROMO-2026-000003")).isEqualTo(ReasonCode.PROMO_APPLIED);
        assertThat(r.net()).isEqualByComparingTo("63.00");
        // Exactly at the cap is allowed: 30% then ~14.29% of 70 = 10.00 → 40.00 = 40%.
        PromotionOffer exact = Offers.markdownAmount("PROMO-2026-000004", "10.00").priority(2).maxStack(3).build();
        assertThat(reasonOf(resolve("100.00", List.of(a, exact)), "PROMO-2026-000004"))
                .isEqualTo(ReasonCode.PROMO_APPLIED);
    }

    @Test
    void minOrderQuantity() {
        PromotionOffer coupon = Offers.couponPct("PROMO-2026-000001", "0.10").maxStack(3).minQty(2).build();
        assertThat(reasonOf(resolve("100.00", List.of(), List.of(coupon), 1, false), "PROMO-2026-000001"))
                .isEqualTo(ReasonCode.PROMO_REJECTED_MIN_QTY);
        assertThat(reasonOf(resolve("200.00", List.of(), List.of(coupon), 2, false), "PROMO-2026-000001"))
                .isEqualTo(ReasonCode.PROMO_APPLIED);
    }

    @Test
    void seededMarkdownsCountForCouponRulesAndApplyFirst() {
        PromotionOffer markdown = md("PROMO-2026-000001", "0.10").exclusive().build();
        PromotionOffer coupon = Offers.couponAmount("PROMO-2026-000002", "20.00").maxStack(3).build();
        // Two units at 100 list with a 10.00/unit markdown already applied: the exclusive markdown blocks the coupon.
        List<PromotionStackResolver.Applied> seeded = List.of(
                new PromotionStackResolver.Applied(markdown, new BigDecimal("20.00")));
        PromotionStackResolver.Resolution r = resolve("200.00", seeded, List.of(coupon), 2, false);
        assertThat(reasonOf(r, "PROMO-2026-000002")).isEqualTo(ReasonCode.PROMO_REJECTED_EXCLUSIVE);
        assertThat(r.net()).isEqualByComparingTo("180.00");

        PromotionOffer plain = md("PROMO-2026-000003", "0.10").build();
        PromotionOffer pctCoupon = Offers.couponPct("PROMO-2026-000004", "0.10").maxStack(3).build();
        PromotionStackResolver.Resolution r2 = resolve("200.00",
                List.of(new PromotionStackResolver.Applied(plain, new BigDecimal("20.00"))), List.of(pctCoupon), 2,
                false);
        // Coupon percent applies to the running amount after the markdown: 10% of 180 = 18.
        assertThat(r2.evaluations().get(0).discount()).isEqualByComparingTo("18.00");
        assertThat(r2.net()).isEqualByComparingTo("162.00");
    }

    @Test
    void stockoutSuppressesEverything() {
        PromotionOffer a = md("PROMO-2026-000001", "0.10").build();
        PromotionStackResolver.Resolution r = resolve("100.00", List.of(), List.of(a), 1, true);
        assertThat(r.evaluations()).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(e.reason()).isEqualTo(ReasonCode.PROMO_SUPPRESSED_STOCKOUT);
            assertThat(e.discount()).isEqualByComparingTo("0.00");
        });
        assertThat(r.net()).isEqualByComparingTo("100.00");
    }

    @Test
    void eligibilityChannelWindowAndCancellation() {
        PromotionOffer ecom = Offers.couponPct("PROMO-2026-000001", "0.10").channel(Channel.ECOM).build();
        Instant noon = Instant.parse("2026-01-12T12:00:00Z");
        assertThat(ecom.eligible(DAY, noon, Channel.ECOM)).isTrue();
        assertThat(ecom.eligible(DAY, noon, Channel.STORE)).isFalse();
        assertThat(ecom.eligible(DAY, noon, Channel.ALL)).as("a decision prices every channel").isFalse();

        PromotionOffer all = Offers.markdownPct("PROMO-2026-000002", "0.10").window(DAY, DAY.plusDays(6)).build();
        assertThat(all.eligible(DAY, noon, Channel.STORE)).isTrue();
        assertThat(all.eligible(DAY.minusDays(1), noon, Channel.ALL)).isFalse();
        assertThat(all.eligible(DAY.plusDays(7), noon, Channel.ALL)).isFalse();

        PromotionOffer cancelled = Offers.markdownPct("PROMO-2026-000003", "0.10").cancelledAt(noon).build();
        assertThat(cancelled.eligible(DAY, noon.minusSeconds(1), Channel.ALL)).isTrue();
        assertThat(cancelled.eligible(DAY, noon, Channel.ALL)).isFalse();
        PromotionOffer neverStarted = Offers.markdownPct("PROMO-2026-000004", "0.10")
                .cancelledAt(JAN_1.minusDays(2).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)).build();
        assertThat(neverStarted.eligible(DAY, noon, Channel.ALL)).isFalse();
    }

    @Test
    void shuffledInputGivesIdenticalOutput() {
        List<PromotionOffer> offers = new ArrayList<>(List.of(
                md("PROMO-2026-000001", "0.10").priority(5).build(),
                md("PROMO-2026-000002", "0.05").priority(5).exclusive().build(),
                md("PROMO-2026-000003", "0.20").priority(3).excludes("PROMO-2026-000004").build(),
                md("PROMO-2026-000004", "0.15").priority(4).build(),
                Offers.markdownAmount("PROMO-2026-000005", "12.00").priority(9).maxStack(2).build(),
                md("PROMO-2026-000006", "0.30").priority(1).build()));
        PromotionStackResolver.Resolution expected = resolve("250.00", offers);
        Random random = new Random(42);
        for (int i = 0; i < 50; i++) {
            Collections.shuffle(offers, random);
            assertThat(resolve("250.00", offers)).isEqualTo(expected);
        }
    }
}
