package com.pricingsim.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class PricePointRounderTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "474.05, 479.00",   // nearest 9-ending (469 is 5.05 away, 479 is 4.95)
            "474.00, 479.00",   // tie (5.00 each way) goes up
            "473.99, 469.00",
            "1232.00, 1229.00",
            "1234.00, 1239.00",   // tie goes up
            "103.00, 99.00",    // between 99 and 109: 99 is nearer
            "104.00, 109.00",   // tie between 99 and 109 goes up
            "99.40, 99.00",
            "71.25, 71.00",     // below 100: whole rupees
            "71.50, 72.00",
            "0.40, 1.00"
    })
    void roundsToTheNearestPricePoint(String candidate, String expected) {
        PricePointRounder.Result r = PricePointRounder.round(d(candidate), d("0.01"), d("100000.00"));
        assertThat(r.price()).isEqualByComparingTo(expected);
        assertThat(r.rounded()).isEqualTo(d(candidate).compareTo(d(expected)) != 0);
        assertThat(r.price().scale()).isEqualTo(2);
    }

    @Test
    void endsInNineFromOneHundredUp() {
        for (int i = 100; i < 2_000; i++) {
            BigDecimal p = PricePointRounder.nearestPoint(BigDecimal.valueOf(i));
            if (p.compareTo(BigDecimal.valueOf(100)) >= 0) {
                assertThat(p.remainder(BigDecimal.TEN)).as("point for " + i).isEqualByComparingTo("9");
            }
        }
    }

    @Test
    void movesUpWhenRoundingFallsBelowTheFloor() {
        // 470.00 → nearest 469, below the floor 470 → next point up 479.
        PricePointRounder.Result r = PricePointRounder.round(d("470.00"), d("470.00"), d("499.00"));
        assertThat(r.price()).isEqualByComparingTo("479.00");
    }

    @Test
    void movesDownWhenRoundingRisesAboveTheCeiling() {
        // 495 → nearest 499 > ceiling 497 → next point down 489.
        PricePointRounder.Result r = PricePointRounder.round(d("495.00"), d("480.00"), d("497.00"));
        assertThat(r.price()).isEqualByComparingTo("489.00");
    }

    @Test
    void keepsTheUnroundedCandidateWhenNoPointFits() {
        PricePointRounder.Result r = PricePointRounder.round(d("471.30"), d("470.10"), d("478.50"));
        assertThat(r.price()).isEqualByComparingTo("471.30");
        assertThat(r.rounded()).isFalse();
        // Below 100 the gap between points is one rupee.
        PricePointRounder.Result small = PricePointRounder.round(d("51.40"), d("51.13"), d("51.90"));
        assertThat(small.price()).isEqualByComparingTo("51.40");
    }

    @Test
    void pricePointHelpers() {
        assertThat(PricePointRounder.isPricePoint(d("499.00"))).isTrue();
        assertThat(PricePointRounder.isPricePoint(d("500.00"))).isFalse();
        assertThat(PricePointRounder.isPricePoint(d("57.00"))).isTrue();
        assertThat(PricePointRounder.isPricePoint(d("57.50"))).isFalse();
        assertThat(PricePointRounder.pointAtOrBelow(d("0.99"))).isNull();
        assertThat(PricePointRounder.pointAtOrAbove(d("99.01"))).isEqualByComparingTo("109");
        assertThat(PricePointRounder.pointAtOrBelow(d("108.99"))).isEqualByComparingTo("99");
    }
}
