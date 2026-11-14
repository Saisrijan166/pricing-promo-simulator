package com.pricingsim.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.ObservationSource;
import com.pricingsim.domain.enums.ObservationUsage;
import com.pricingsim.domain.model.CompetitorObservation;

class CompetitorReferenceResolverTest {

    private static final Instant TS = Instant.parse("2026-02-09T06:00:00Z");
    private static final PricingPolicy POLICY = PricingPolicy.defaults();
    private final List<CompetitorObservation> obs = new ArrayList<>();

    private CompetitorObservation add(Competitor c, String price, Instant captured, Instant ingested, String conf) {
        CompetitorObservation o = new CompetitorObservation(String.format("OBS-2026-%07d", obs.size() + 1),
                "SKU-000001", c, new BigDecimal(price), captured, ingested, ObservationSource.SCRAPE_FEED, true,
                new BigDecimal(conf));
        obs.add(o);
        return o;
    }

    private CompetitorObservation add(Competitor c, String price, long secondsBefore) {
        Instant captured = TS.minusSeconds(secondsBefore);
        return add(c, price, captured, captured, "0.950");
    }

    private CompetitorReferenceResolver.Result resolve() {
        return CompetitorReferenceResolver.resolve(obs, TS, POLICY);
    }

    private ObservationUsage usageOf(CompetitorReferenceResolver.Result r, CompetitorObservation o) {
        return r.assessments().stream().filter(a -> a.observationId().equals(o.observationId())).findFirst()
                .orElseThrow().usage();
    }

    @Test
    void exactly72HoursIsFreshOneSecondMoreIsStale() {
        CompetitorObservation edge = add(Competitor.COMP_ALPHA, "100.00", 72 * 3600);
        CompetitorObservation past = add(Competitor.COMP_BETA, "110.00", 72 * 3600 + 1);
        CompetitorReferenceResolver.Result r = resolve();
        assertThat(usageOf(r, edge)).isEqualTo(ObservationUsage.USED);
        assertThat(usageOf(r, past)).isEqualTo(ObservationUsage.EXCLUDED_STALE);
        assertThat(r.referencePrice()).isEqualByComparingTo("100.00");
        assertThat(r.freshCount()).isEqualTo(1);
        assertThat(r.staleCount()).isEqualTo(1);
        assertThat(r.assessments()).extracting(CompetitorReferenceResolver.Assessment::ageHours)
                .containsExactly(new BigDecimal("72.00"), new BigDecimal("72.00"));
        assertThat(r.dataStale()).isFalse();
    }

    @Test
    void lateIngestionIsInvisibleAndFreshnessUsesCaptureTime() {
        // Captured 10 h ago but ingested after the decision: not visible, not considered.
        Instant captured = TS.minusSeconds(10 * 3600);
        add(Competitor.COMP_ALPHA, "100.00", captured, TS.plusSeconds(1), "0.950");
        // A late feed captured 4 days ago that arrived just now is visible but stale.
        Instant old = TS.minusSeconds(96 * 3600);
        CompetitorObservation late = add(Competitor.COMP_BETA, "90.00", old, TS.minusSeconds(60), "0.950");
        CompetitorReferenceResolver.Result r = resolve();
        assertThat(r.assessments()).hasSize(1);
        assertThat(usageOf(r, late)).isEqualTo(ObservationUsage.EXCLUDED_STALE);
        assertThat(r.referencePrice()).isNull();
        assertThat(r.dataStale()).isTrue();
    }

    @Test
    void capturedAfterDecisionIsIgnored() {
        add(Competitor.COMP_ALPHA, "100.00", TS.plusSeconds(1), TS.plusSeconds(2), "0.950");
        assertThat(resolve().assessments()).isEmpty();
        assertThat(resolve().dataStale()).isFalse();
    }

    @Test
    void lowConfidenceIsExcluded() {
        CompetitorObservation low = add(Competitor.COMP_ALPHA, "100.00", TS.minusSeconds(3600), TS, "0.799");
        CompetitorReferenceResolver.Result r = resolve();
        assertThat(usageOf(r, low)).isEqualTo(ObservationUsage.EXCLUDED_LOW_CONFIDENCE);
        assertThat(r.referencePrice()).isNull();
        assertThat(r.dataStale()).as("fresh but low confidence is not stale data").isFalse();
    }

    @Test
    void olderFreshObservationsAreSupersededAndOlderStaleOnesAreNotConsidered() {
        CompetitorObservation older = add(Competitor.COMP_ALPHA, "105.00", 40 * 3600);
        CompetitorObservation latest = add(Competitor.COMP_ALPHA, "100.00", 2 * 3600);
        add(Competitor.COMP_ALPHA, "120.00", 200 * 3600);
        CompetitorReferenceResolver.Result r = resolve();
        assertThat(r.assessments()).hasSize(2);
        assertThat(usageOf(r, latest)).isEqualTo(ObservationUsage.USED);
        assertThat(usageOf(r, older)).isEqualTo(ObservationUsage.EXCLUDED_SUPERSEDED);
        assertThat(r.referencePrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void medianOfOddAndEvenCounts() {
        add(Competitor.COMP_ALPHA, "100.00", 3600);
        add(Competitor.COMP_BETA, "120.00", 3600);
        assertThat(resolve().referencePrice()).isEqualByComparingTo("110.00");
        add(Competitor.COMP_GAMMA, "101.00", 3600);
        assertThat(resolve().referencePrice()).isEqualByComparingTo("101.00");
        assertThat(CompetitorReferenceResolver.median(List.of(new BigDecimal("100.00"), new BigDecimal("100.01"))))
                .isEqualByComparingTo("100.01"); // 100.005 → HALF_UP
        assertThat(CompetitorReferenceResolver.median(List.of())).isNull();
    }

    @Test
    void allStaleGivesNullReferenceAndStaleFlag() {
        add(Competitor.COMP_ALPHA, "100.00", 80 * 3600);
        add(Competitor.COMP_GAMMA, "98.00", 160 * 3600);
        CompetitorReferenceResolver.Result r = resolve();
        assertThat(r.referencePrice()).isNull();
        assertThat(r.staleCount()).isEqualTo(2);
        assertThat(r.dataStale()).isTrue();
    }

    @Test
    void noObservationsIsNotStale() {
        CompetitorReferenceResolver.Result r = resolve();
        assertThat(r.assessments()).isEmpty();
        assertThat(r.dataStale()).isFalse();
        assertThat(r.referencePrice()).isNull();
    }

    @Test
    void resultIsIndependentOfInputOrder() {
        add(Competitor.COMP_ALPHA, "105.00", 40 * 3600);
        add(Competitor.COMP_ALPHA, "100.00", 2 * 3600);
        add(Competitor.COMP_BETA, "111.00", 5 * 3600);
        add(Competitor.COMP_GAMMA, "99.00", 100 * 3600);
        CompetitorReferenceResolver.Result a = resolve();
        java.util.Collections.reverse(obs);
        assertThat(resolve()).isEqualTo(a);
    }
}
