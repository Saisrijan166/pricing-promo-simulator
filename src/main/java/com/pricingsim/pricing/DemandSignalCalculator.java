package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Demand and inventory signals (PLAN.md §8 S6).
 *
 * <ul>
 *   <li>{@code d7}, {@code d28}: units sold in the 7 / 28 days before the effective date; {@code n7}, {@code n28}: how
 *       many of those days have sales history (a SKU launched 10 days ago has n28 = 10).</li>
 *   <li>{@code velocity_ratio = (d7 / n7) / (d28 / n28)}, 4 dp. It is 1 (neutral) when there is no history or no
 *       sales in 28 days, so there is never a division by zero.</li>
 *   <li>{@code days_of_cover = on_hand / max(d28 / n28, 0.1)}, 2 dp; the average is 0 without history.</li>
 * </ul>
 */
public final class DemandSignalCalculator {

    static final BigDecimal MIN_DAILY_RATE = new BigDecimal("0.1");

    private DemandSignalCalculator() {
    }

    /**
     * @param velocityRatio recent vs 28-day sales rate
     * @param daysOfCover   on-hand stock in days of average sales
     * @param historyDays   n28, days of sales history behind the signals
     */
    public record Signals(BigDecimal velocityRatio, BigDecimal daysOfCover, int historyDays) {
    }

    public static Signals signals(int onHand, int d7, int n7, int d28, int n28) {
        if (onHand < 0 || d7 < 0 || d28 < 0 || n7 < 0 || n28 < 0 || n7 > 7 || n28 > 28 || n7 > n28 || d7 > d28) {
            throw new IllegalArgumentException("invalid demand inputs");
        }
        return new Signals(velocityRatio(d7, n7, d28, n28), daysOfCover(onHand, d28, n28), n28);
    }

    public static BigDecimal velocityRatio(int d7, int n7, int d28, int n28) {
        if (n7 == 0 || n28 == 0 || d28 == 0) {
            return BigDecimal.ONE.setScale(4, RoundingMode.UNNECESSARY);
        }
        // (d7 / n7) / (d28 / n28) = d7 · n28 / (n7 · d28), exact before the final rounding.
        return BigDecimal.valueOf((long) d7 * n28).divide(BigDecimal.valueOf((long) n7 * d28), 4, RoundingMode.HALF_UP);
    }

    public static BigDecimal daysOfCover(int onHand, int d28, int n28) {
        // average ≤ 0.1 ⇔ 10 · d28 ≤ n28 (also covers n28 = 0).
        if (n28 == 0 || 10L * d28 <= n28) {
            return BigDecimal.valueOf(onHand).divide(MIN_DAILY_RATE, 2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf((long) onHand * n28).divide(BigDecimal.valueOf(d28), 2, RoundingMode.HALF_UP);
    }
}
