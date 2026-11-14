package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Retail price points (PLAN.md §8 step S9). A price point is a whole rupee below ₹100 ({@code 1 … 99}), or a whole
 * rupee ending in 9 from ₹100 up ({@code 109, 119, 129, …}). A candidate is rounded to the nearest point (ties go up),
 * then moved to the next point up if it fell below the floor, or the next point down if it rose above the ceiling. If
 * no point fits inside {@code [floor, ceiling]}, the unrounded candidate is kept.
 *
 * <p>Pure and stateless; used by the generator and, later, by the pricing engine.
 */
public final class PricePointRounder {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal NINE = BigDecimal.valueOf(9);
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal NINETY_NINE = BigDecimal.valueOf(99);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FIRST_NINE_ENDING = BigDecimal.valueOf(109);

    /** The rounded price (scale 2) and whether it differs from the candidate. */
    public record Result(BigDecimal price, boolean rounded) {
    }

    private PricePointRounder() {
    }

    /** Rounds {@code candidate} to a price point inside {@code [floor, ceiling]}, or keeps it if none fits. */
    public static Result round(BigDecimal candidate, BigDecimal floor, BigDecimal ceiling) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(ceiling, "ceiling");
        BigDecimal unrounded = money(candidate);
        BigDecimal point = nearestPoint(candidate);
        if (point == null || point.compareTo(floor) < 0) {
            point = pointAtOrAbove(floor);
            if (point.compareTo(ceiling) > 0) {
                point = null;
            }
        } else if (point.compareTo(ceiling) > 0) {
            point = pointAtOrBelow(ceiling);
            if (point != null && point.compareTo(floor) < 0) {
                point = null;
            }
        }
        if (point == null) {
            return new Result(unrounded, false);
        }
        BigDecimal price = money(point);
        return new Result(price, price.compareTo(unrounded) != 0);
    }

    /** Nearest price point to {@code x} (ties go to the higher point), or {@code null} if there is none. */
    public static BigDecimal nearestPoint(BigDecimal x) {
        BigDecimal below = pointAtOrBelow(x);
        BigDecimal above = pointAtOrAbove(x);
        if (below == null) {
            return above;
        }
        int cmp = x.subtract(below).compareTo(above.subtract(x));
        return cmp < 0 ? below : above;
    }

    /** Largest price point {@code <= x}, or {@code null} when {@code x < 1}. */
    public static BigDecimal pointAtOrBelow(BigDecimal x) {
        if (x.compareTo(ONE) < 0) {
            return null;
        }
        if (x.compareTo(HUNDRED) < 0) {
            return x.setScale(0, RoundingMode.FLOOR);
        }
        if (x.compareTo(FIRST_NINE_ENDING) < 0) {
            return NINETY_NINE;
        }
        return x.subtract(NINE).divide(TEN, 0, RoundingMode.FLOOR).multiply(TEN).add(NINE);
    }

    /** Smallest price point {@code >= x} (never below ₹1). */
    public static BigDecimal pointAtOrAbove(BigDecimal x) {
        if (x.compareTo(NINETY_NINE) <= 0) {
            BigDecimal up = x.setScale(0, RoundingMode.CEILING);
            return up.compareTo(ONE) < 0 ? ONE : up;
        }
        return x.subtract(NINE).divide(TEN, 0, RoundingMode.CEILING).multiply(TEN).add(NINE);
    }

    /** {@code true} if {@code x} is exactly a price point. */
    public static boolean isPricePoint(BigDecimal x) {
        BigDecimal below = pointAtOrBelow(x);
        return below != null && below.compareTo(x) == 0;
    }

    private static BigDecimal money(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }
}
