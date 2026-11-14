package com.pricingsim.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.junit.jupiter.api.Test;


class MarginCalculatorTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void marginIsOnNetPrice() {
        // (100 - 70) / 100 = 0.3000
        assertThat(MarginCalculator.netMargin(d("100.00"), d("70.00"))).isEqualByComparingTo("0.3000");
        // (90 - 70) / 90 = 0.2222…
        assertThat(MarginCalculator.netMargin(d("90.00"), d("70.00"))).isEqualByComparingTo("0.2222");
        assertThat(MarginCalculator.netMargin(d("60.00"), d("70.00"))).isEqualByComparingTo("-0.1667");
    }

    @Test
    void discountsApplySequentiallyPercentFirstThenAmount() {
        // 200 → 10% (20.00) → 180.00 → 5% (9.00) → 171.00 → ₹15 → 156.00, regardless of input order.
        PromotionOffer amount = Offers.markdownAmount("PROMO-2026-000003", "15.00").build();
        PromotionOffer ten = Offers.markdownPct("PROMO-2026-000001", "0.10").build();
        PromotionOffer five = Offers.markdownPct("PROMO-2026-000002", "0.05").build();
        List<PromotionStackResolver.Applied> applied =
                PromotionStackResolver.discounts(d("200.00"), BigDecimal.ZERO, List.of(amount, ten, five));
        assertThat(applied).extracting(a -> a.offer().promotionId())
                .containsExactly("PROMO-2026-000001", "PROMO-2026-000002", "PROMO-2026-000003");
        assertThat(applied).extracting(PromotionStackResolver.Applied::discount)
                .containsExactly(d("20.00"), d("9.00"), d("15.00"));
        assertThat(d("200.00").subtract(PromotionStackResolver.total(applied))).isEqualByComparingTo("156.00");
    }

    /**
     * Regression for the MARGIN_BEFORE_DISCOUNT anomaly (PLAN.md §9 #4): the margin on the list price passes, the margin
     * on the net price fails, and only the net basis is available.
     */
    @Test
    void marginBeforeDiscountRegression() {
        BigDecimal list = d("100.00");
        BigDecimal landed = d("75.00");
        BigDecimal required = d("0.1500");
        BigDecimal net = d("80.00"); // after a 20% markdown

        BigDecimal wrongBasis = list.subtract(landed).divide(list, 4, RoundingMode.HALF_UP); // 0.2500
        BigDecimal netBasis = MarginCalculator.netMargin(net, landed);                         // 0.0625
        assertThat(wrongBasis).isNotEqualByComparingTo(netBasis);
        assertThat(wrongBasis).isGreaterThanOrEqualTo(required);
        assertThat(MarginCalculator.meetsMargin(net, landed, required)).isFalse();
    }

    @Test
    void meetsMarginComparesExactly() {
        // Exactly at the requirement passes; one paisa less fails.
        assertThat(MarginCalculator.meetsMargin(d("100.00"), d("80.00"), d("0.20"))).isTrue();
        assertThat(MarginCalculator.meetsMargin(d("99.99"), d("80.00"), d("0.20"))).isFalse();
    }

    @Test
    void zeroOrNegativeNetIsRejected() {
        assertThat(MarginCalculator.meetsMargin(d("0.00"), d("10.00"), d("0.10"))).isFalse();
        assertThat(MarginCalculator.meetsMargin(d("-5.00"), d("10.00"), d("0.10"))).isFalse();
        assertThatThrownBy(() -> MarginCalculator.netMargin(d("0.00"), d("10.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderGrossMargin() {
        assertThat(MarginCalculator.grossMarginAmount(d("270.00"), 3, d("70.50"))).isEqualByComparingTo("58.50");
    }
}
