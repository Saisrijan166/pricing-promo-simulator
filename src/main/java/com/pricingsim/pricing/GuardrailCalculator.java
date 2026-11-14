package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Price guardrails (PLAN.md §8 S2–S3, S8).
 * <ul>
 *   <li>S2 floor = {@code roundUpToPaise(max(landed_cost / (1 − min_margin_pct), map_price ?? 0))}. Rounding up keeps
 *       the margin at the floor ≥ {@code min_margin_pct}.</li>
 *   <li>S3 ceiling = MRP (Indian law: retail price may not exceed MRP). A ceiling below the floor is a guardrail
 *       conflict.</li>
 * </ul>
 */
public final class GuardrailCalculator {

    private GuardrailCalculator() {
    }

    /** Floor and ceiling for one decision. */
    public record Guardrails(BigDecimal floor, BigDecimal ceiling) {
        /** {@code true} when the ceiling is below the floor (S3 guardrail conflict). */
        public boolean conflict() {
            return ceiling.compareTo(floor) < 0;
        }

        /** {@code clamp(price, floor, ceiling)}; in a conflict the floor wins (PLAN.md §8 S3). */
        public BigDecimal clamp(BigDecimal price) {
            if (conflict()) {
                return floor;
            }
            return price.max(floor).min(ceiling);
        }
    }

    public static Guardrails guardrails(BigDecimal landedCost, BigDecimal minMarginPct, BigDecimal mapPrice,
                                        BigDecimal mrp) {
        return new Guardrails(floor(landedCost, minMarginPct, mapPrice), ceiling(mrp));
    }

    public static BigDecimal floor(BigDecimal landedCost, BigDecimal minMarginPct, BigDecimal mapPrice) {
        Objects.requireNonNull(landedCost, "landedCost");
        Objects.requireNonNull(minMarginPct, "minMarginPct");
        if (landedCost.signum() <= 0) {
            throw new IllegalArgumentException("landed cost must be > 0");
        }
        if (minMarginPct.signum() < 0 || minMarginPct.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("min margin must be in [0, 1)");
        }
        BigDecimal marginFloor = landedCost.divide(BigDecimal.ONE.subtract(minMarginPct), 10, RoundingMode.HALF_UP);
        BigDecimal map = mapPrice == null ? BigDecimal.ZERO : mapPrice;
        return marginFloor.max(map).setScale(2, RoundingMode.CEILING);
    }

    public static BigDecimal ceiling(BigDecimal mrp) {
        Objects.requireNonNull(mrp, "mrp");
        if (mrp.signum() <= 0) {
            throw new IllegalArgumentException("mrp must be > 0");
        }
        return mrp.setScale(2, RoundingMode.UNNECESSARY);
    }
}
