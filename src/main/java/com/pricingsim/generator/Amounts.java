package com.pricingsim.generator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversions from simulation doubles to stored decimals (PLAN.md §7.1: money becomes {@code BigDecimal} scale 2,
 * HALF_UP, at every boundary). {@code new BigDecimal(double)} is the exact binary value, so rounding never depends on
 * {@code Double.toString}, whose output changed between JDK versions.
 */
public final class Amounts {

    private Amounts() {
    }

    /** INR amount, 2 decimal places, HALF_UP. */
    public static BigDecimal money(double value) {
        requireFinite(value);
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** INR amount rounded to a whole rupee (HALF_UP), stored with scale 2. */
    public static BigDecimal wholeRupees(double value) {
        requireFinite(value);
        return new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Fraction with 4 decimal places (NUMERIC(6,4)), HALF_UP. */
    public static BigDecimal fraction4(double value) {
        requireFinite(value);
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Not a finite number: " + value);
        }
    }
}
