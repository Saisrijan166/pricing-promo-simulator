package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.ObservationUsage;
import com.pricingsim.domain.model.CompetitorObservation;

/**
 * Competitor reference price (PLAN.md §8 S4), with no look-ahead.
 *
 * <ol>
 *   <li>Only observations <b>visible</b> at the decision are considered: {@code source_captured_at ≤ decision_ts} and
 *       {@code ingested_at ≤ decision_ts}. Others are ignored entirely.</li>
 *   <li>Per competitor, the latest visible observation (by capture time) is assessed: EXCLUDED_STALE if its age
 *       ({@code decision_ts − source_captured_at}) exceeds the freshness window (72 h; exactly 72 h is still fresh),
 *       otherwise EXCLUDED_LOW_CONFIDENCE if {@code match_confidence < 0.80}, otherwise USED. Freshness uses capture
 *       time, so a late feed is stale even though it just arrived.</li>
 *   <li>Older visible observations of the same competitor that are still inside the freshness window are recorded as
 *       EXCLUDED_SUPERSEDED. Older stale ones are not "considered", which keeps the bridge small.</li>
 *   <li>{@code ref = median(USED prices)} (mean of the middle two for an even count, 2 dp HALF_UP), or null.</li>
 *   <li>COMPETITOR_DATA_STALE: observations were considered but none is fresh.</li>
 * </ol>
 */
public final class CompetitorReferenceResolver {

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    private CompetitorReferenceResolver() {
    }

    /** One considered observation and how it was used. */
    public record Assessment(CompetitorObservation observation, ObservationUsage usage, BigDecimal ageHours) {
        public String observationId() {
            return observation.observationId();
        }
    }

    /**
     * @param referencePrice median of USED prices, or null
     * @param assessments    every considered observation, sorted by observation ID
     * @param freshCount     number of USED observations
     * @param staleCount     number of EXCLUDED_STALE observations
     * @param dataStale      observations exist but none is fresh (COMPETITOR_DATA_STALE)
     */
    public record Result(BigDecimal referencePrice, List<Assessment> assessments, int freshCount, int staleCount,
                         boolean dataStale) {
    }

    public static Result resolve(List<CompetitorObservation> observations, Instant decisionTs, PricingPolicy policy) {
        return resolve(observations, decisionTs, policy, java.util.Collections.emptySortedSet());
    }

    /**
     * As {@link #resolve(List, Instant, PricingPolicy)}, but the observations in {@code treatedAsFresh} pass the
     * freshness test whatever their age. This is a deliberate fault used only by anomaly injection
     * (STALE_OBSERVATION_AS_CURRENT, PLAN.md §9); the stored {@code age_hours} stays the real age.
     */
    public static Result resolve(List<CompetitorObservation> observations, Instant decisionTs, PricingPolicy policy,
                                 java.util.Set<String> treatedAsFresh) {
        Objects.requireNonNull(decisionTs, "decisionTs");
        Map<Competitor, List<CompetitorObservation>> visible = new EnumMap<>(Competitor.class);
        for (CompetitorObservation o : observations) {
            if (!o.sourceCapturedAt().isAfter(decisionTs) && !o.ingestedAt().isAfter(decisionTs)) {
                visible.computeIfAbsent(o.competitor(), c -> new ArrayList<>()).add(o);
            }
        }
        Comparator<CompetitorObservation> newestFirst = Comparator
                .comparing(CompetitorObservation::sourceCapturedAt).reversed()
                .thenComparing(CompetitorObservation::observationId, Comparator.reverseOrder());

        List<Assessment> assessments = new ArrayList<>();
        List<BigDecimal> used = new ArrayList<>();
        int stale = 0;
        boolean anyFresh = false;
        for (List<CompetitorObservation> list : visible.values()) {
            list.sort(newestFirst);
            for (int i = 0; i < list.size(); i++) {
                CompetitorObservation o = list.get(i);
                long ageSeconds = Duration.between(o.sourceCapturedAt(), decisionTs).getSeconds();
                boolean fresh = ageSeconds <= policy.freshnessSeconds() || treatedAsFresh.contains(o.observationId());
                ObservationUsage usage;
                if (i == 0) {
                    if (!fresh) {
                        usage = ObservationUsage.EXCLUDED_STALE;
                        stale++;
                    } else if (o.matchConfidence().compareTo(policy.minMatchConfidence()) < 0) {
                        usage = ObservationUsage.EXCLUDED_LOW_CONFIDENCE;
                    } else {
                        usage = ObservationUsage.USED;
                        used.add(o.observedPrice());
                    }
                } else if (fresh) {
                    usage = ObservationUsage.EXCLUDED_SUPERSEDED;
                } else {
                    break; // list is newest first: everything after is older and stale
                }
                anyFresh |= fresh;
                assessments.add(new Assessment(o, usage, ageHours(ageSeconds)));
            }
        }
        assessments.sort(Comparator.comparing(Assessment::observationId));
        BigDecimal ref = median(used);
        boolean dataStale = !assessments.isEmpty() && !anyFresh;
        return new Result(ref, List.copyOf(assessments), used.size(), stale, dataStale);
    }

    /** Age in hours, 2 dp HALF_UP (the stored {@code age_hours}). */
    public static BigDecimal ageHours(long ageSeconds) {
        return BigDecimal.valueOf(ageSeconds).divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    /** Median, 2 dp HALF_UP; mean of the middle two for an even count; null for an empty list. */
    public static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        int n = sorted.size();
        BigDecimal m = n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        return m.setScale(2, RoundingMode.HALF_UP);
    }
}
