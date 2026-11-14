package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * THE margin function (PLAN.md §2 assumption 5, §8 S11). Margin is always on the <b>net</b> price, after every applied
 * discount, against the landed cost effective on that date:
 * {@code margin_pct = (net − landed_cost) / net}. There is deliberately no "margin on list price" method: computing
 * the margin before discounts overstates it, which is exactly the MARGIN_BEFORE_DISCOUNT anomaly (PLAN.md §9).
 */
public final class MarginCalculator {

    private MarginCalculator() {
    }

    /** Net margin as a fraction, 4 dp HALF_UP (the stored {@code margin_pct}). Net must be &gt; 0. */
    public static BigDecimal netMargin(BigDecimal netPrice, BigDecimal landedCost) {
        requireNet(netPrice);
        Objects.requireNonNull(landedCost, "landedCost");
        return netPrice.subtract(landedCost).divide(netPrice, 4, RoundingMode.HALF_UP);
    }

    /**
     * {@code true} when the net margin meets {@code required}, compared exactly ({@code net − landed ≥ required × net})
     * so 4-dp rounding never lets a failing price through. A non-positive net never meets a margin.
     */
    public static boolean meetsMargin(BigDecimal netPrice, BigDecimal landedCost, BigDecimal required) {
        Objects.requireNonNull(netPrice, "netPrice");
        if (netPrice.signum() <= 0) {
            return false;
        }
        return netPrice.subtract(landedCost).compareTo(required.multiply(netPrice)) >= 0;
    }

    /** Gross margin amount of an order line: {@code net_amount − quantity × unit_landed_cost}. */
    public static BigDecimal grossMarginAmount(BigDecimal netAmount, int quantity, BigDecimal unitLandedCost) {
        return netAmount.subtract(unitLandedCost.multiply(BigDecimal.valueOf(quantity))).setScale(2,
                RoundingMode.UNNECESSARY);
    }

    private static void requireNet(BigDecimal netPrice) {
        Objects.requireNonNull(netPrice, "netPrice");
        if (netPrice.signum() <= 0) {
            throw new IllegalArgumentException("net price must be > 0, was " + netPrice);
        }
    }
}
