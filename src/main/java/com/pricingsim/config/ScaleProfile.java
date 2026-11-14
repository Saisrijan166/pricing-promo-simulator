package com.pricingsim.config;

import java.time.LocalDate;
import java.util.Objects;

import org.springframework.format.annotation.DateTimeFormat;

/**
 * One scale profile (PLAN.md §7.1). Starter and full use the same code path; only these values differ.
 *
 * @param skus             number of products
 * @param days             simulation window length in days (window = startDate … startDate + days − 1)
 * @param promotions       number of promotions (used by the promotion generator)
 * @param startDate        first day of the simulation window
 * @param anomaliesPerType labelled anomalies per issue type (used by anomaly injection)
 * @param competitors      number of fictional competitors observed (1–3)
 */
public record ScaleProfile(
        int skus,
        int days,
        int promotions,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        int anomaliesPerType,
        int competitors) {

    public static final int MAX_COMPETITORS = 3;

    public ScaleProfile {
        Objects.requireNonNull(startDate, "startDate");
        require(skus >= 1, "skus must be >= 1, was " + skus);
        require(days >= 1, "days must be >= 1, was " + days);
        require(promotions >= 0, "promotions must be >= 0, was " + promotions);
        require(anomaliesPerType >= 0, "anomaliesPerType must be >= 0, was " + anomaliesPerType);
        require(competitors >= 0 && competitors <= MAX_COMPETITORS,
                "competitors must be 0.." + MAX_COMPETITORS + ", was " + competitors);
    }

    /** Last day of the window (inclusive). */
    public LocalDate endDate() {
        return startDate.plusDays(days - 1L);
    }

    /** A copy with any non-null override applied (CLI flags win over the profile). */
    public ScaleProfile withOverrides(Integer skus, Integer days, Integer promotions, LocalDate startDate,
                                      Integer anomaliesPerType) {
        return new ScaleProfile(
                skus != null ? skus : this.skus,
                days != null ? days : this.days,
                promotions != null ? promotions : this.promotions,
                startDate != null ? startDate : this.startDate,
                anomaliesPerType != null ? anomaliesPerType : this.anomaliesPerType,
                competitors);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
