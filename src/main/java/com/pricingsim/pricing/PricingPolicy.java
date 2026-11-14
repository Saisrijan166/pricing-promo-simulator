package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.util.Objects;

import com.pricingsim.domain.enums.PricePosition;

/**
 * Immutable pricing thresholds (PLAN.md §8 policy defaults). The same policy object drives the generator and the
 * simulate API, so a stored decision can be reproduced from its inputs.
 *
 * @param freshnessHours            competitor observation is fresh if {@code decision_ts − captured ≤ this}
 * @param minMatchConfidence        observations below this match confidence are excluded
 * @param maxStepChangePct          largest list-price move per decision (S7), as a fraction of the previous price
 * @param autoApproveMaxChangePct   larger moves are escalated (S12)
 * @param overstockDays             days of cover above this → overstock markdown (S6)
 * @param lowCoverDays              days of cover below this (with high velocity) → increase (S6)
 * @param highVelocityRatio         velocity ratio at or above this counts as high (S6)
 * @param overstockAdj              target adjustment for overstock (negative fraction)
 * @param lowCoverAdj               target adjustment for low cover (positive fraction)
 * @param valueOffset               competitor-reference offset for VALUE positioning
 * @param matchOffset               … for MATCH
 * @param premiumOffset             … for PREMIUM
 * @param defaultMaxStack           stack limit when no promotion has a MAX_STACK_COUNT rule
 * @param defaultMaxCombinedDiscount combined discount cap when no promotion has a MAX_COMBINED_DISCOUNT_PCT rule
 * @param initialTargetRatio        INITIAL target without a competitor reference = MRP × this
 * @param minHistoryDays            S6 overstock / low-cover adjustments need at least this many days of sales history
 */
public record PricingPolicy(
        int freshnessHours,
        BigDecimal minMatchConfidence,
        BigDecimal maxStepChangePct,
        BigDecimal autoApproveMaxChangePct,
        BigDecimal overstockDays,
        BigDecimal lowCoverDays,
        BigDecimal highVelocityRatio,
        BigDecimal overstockAdj,
        BigDecimal lowCoverAdj,
        BigDecimal valueOffset,
        BigDecimal matchOffset,
        BigDecimal premiumOffset,
        int defaultMaxStack,
        BigDecimal defaultMaxCombinedDiscount,
        BigDecimal initialTargetRatio,
        int minHistoryDays) {

    /** Stored on every decision; bump it when any rule or default changes. */
    public static final String RULE_VERSION = "pricing-rules-v1";

    public PricingPolicy {
        Objects.requireNonNull(minMatchConfidence, "minMatchConfidence");
        Objects.requireNonNull(maxStepChangePct, "maxStepChangePct");
        Objects.requireNonNull(autoApproveMaxChangePct, "autoApproveMaxChangePct");
        Objects.requireNonNull(overstockDays, "overstockDays");
        Objects.requireNonNull(lowCoverDays, "lowCoverDays");
        Objects.requireNonNull(highVelocityRatio, "highVelocityRatio");
        Objects.requireNonNull(overstockAdj, "overstockAdj");
        Objects.requireNonNull(lowCoverAdj, "lowCoverAdj");
        Objects.requireNonNull(valueOffset, "valueOffset");
        Objects.requireNonNull(matchOffset, "matchOffset");
        Objects.requireNonNull(premiumOffset, "premiumOffset");
        Objects.requireNonNull(defaultMaxCombinedDiscount, "defaultMaxCombinedDiscount");
        Objects.requireNonNull(initialTargetRatio, "initialTargetRatio");
        if (freshnessHours <= 0 || defaultMaxStack < 1 || minHistoryDays < 0) {
            throw new IllegalArgumentException("freshnessHours > 0, defaultMaxStack >= 1, minHistoryDays >= 0");
        }
    }

    /** PLAN.md §8 defaults. */
    public static PricingPolicy defaults() {
        return new PricingPolicy(72, new BigDecimal("0.80"), new BigDecimal("0.08"), new BigDecimal("0.10"),
                new BigDecimal("60"), new BigDecimal("7"), new BigDecimal("1.2"), new BigDecimal("-0.03"),
                new BigDecimal("0.03"), new BigDecimal("-0.03"), BigDecimal.ZERO, new BigDecimal("0.05"), 2,
                new BigDecimal("0.40"), new BigDecimal("0.95"), 7);
    }

    /** Competitor-reference offset for a price position (S5). */
    public BigDecimal offsetFor(PricePosition position) {
        return switch (position) {
            case VALUE -> valueOffset;
            case MATCH -> matchOffset;
            case PREMIUM -> premiumOffset;
        };
    }

    public long freshnessSeconds() {
        return freshnessHours * 3600L;
    }
}
