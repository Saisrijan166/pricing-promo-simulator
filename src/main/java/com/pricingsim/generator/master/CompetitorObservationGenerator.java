package com.pricingsim.generator.master;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.ObservationSource;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.Amounts;
import com.pricingsim.generator.GenerationContext;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;

/**
 * Generates {@code competitor_observation} (PLAN.md §7.2 step 4). Competitors do not react to our prices, so this
 * runs before the market simulation.
 *
 * <ul>
 *   <li>Only {@code competitor_tracked} SKUs, on the SKU's active days. Each competitor covers 40–80% of them.</li>
 *   <li>Price: weekly random walk (σ = 1.5%) anchored at MRP × U(0.80, 0.98), kept within 0.5–1.5 × MRP, whole
 *       rupees.</li>
 *   <li>Cadence: ALPHA every 1–2 days, BETA every 2–4 days, GAMMA weekly; capture at a random second of the day.</li>
 *   <li>8% of SKU-competitor pairs have a 7–20 day gap episode with no captures (these become stale at decision
 *       time).</li>
 *   <li>Ingestion lag ~ exponential, mean 6 h; 2% are late feeds that arrive 2–5 days after capture.</li>
 *   <li>Match confidence 0.60–1.00, Beta-like, with 5% below 0.80 (3 dp, rounded down so low values stay low).</li>
 * </ul>
 * IDs ({@code OBS-YYYY-%07d}) follow (source_captured_at, sku_id, competitor), per year of capture (PLAN.md §6).
 */
public final class CompetitorObservationGenerator {

    static final double COVERAGE_MIN = 0.40;
    static final double COVERAGE_MAX = 0.80;
    static final double ANCHOR_MIN = 0.80;
    static final double ANCHOR_MAX = 0.98;
    static final double WEEKLY_SIGMA = 0.015;
    static final double PRICE_MIN_RATIO = 0.5;
    static final double PRICE_MAX_RATIO = 1.5;
    static final double GAP_SHARE = 0.08;
    static final int GAP_MIN_DAYS = 7;
    static final int GAP_MAX_DAYS = 20;
    static final double LAG_MEAN_SECONDS = 6 * 3600.0;
    static final double LATE_FEED_SHARE = 0.02;
    static final int LATE_MIN_SECONDS = 2 * 86_400;
    static final int LATE_MAX_SECONDS = 5 * 86_400;
    static final double LOW_CONFIDENCE_SHARE = 0.05;
    static final double CONFIDENCE_THRESHOLD = 0.80;
    static final double IN_STOCK_SHARE = 0.95;
    static final double MANUAL_AUDIT_SHARE = 0.05;

    private final GenerationContext ctx;

    public CompetitorObservationGenerator(GenerationContext ctx) {
        this.ctx = ctx;
    }

    /** Generates every observation; returns them per SKU, each list in (source_captured_at, competitor) order. */
    public Map<String, List<CompetitorObservation>> generate(List<Product> products, RowSink sink) {
        Map<Competitor, Double> coverage = new EnumMap<>(Competitor.class);
        for (Competitor c : ctx.competitors()) {
            coverage.put(c, Distributions.uniform(ctx.streams().of("competitor-coverage", c.name()),
                    COVERAGE_MIN, COVERAGE_MAX));
        }

        List<CompetitorObservation> drafts = new ArrayList<>();
        for (Product product : products) {
            if (!product.competitorTracked() || ctx.activeDays(product) == 0) {
                continue;
            }
            for (Competitor c : ctx.competitors()) {
                generatePair(product, c, coverage.get(c), drafts);
            }
        }

        drafts.sort(Comparator.comparing(CompetitorObservation::sourceCapturedAt)
                .thenComparing(CompetitorObservation::skuId)
                .thenComparing(CompetitorObservation::competitor));
        Map<String, List<CompetitorObservation>> bySku = new TreeMap<>();
        for (CompetitorObservation d : drafts) {
            int year = d.sourceCapturedAt().atZone(ZoneOffset.UTC).getYear();
            CompetitorObservation obs = new CompetitorObservation(ctx.nextId(IdFactory.Sequence.OBSERVATION, year),
                    d.skuId(), d.competitor(), d.observedPrice(), d.sourceCapturedAt(), d.ingestedAt(),
                    d.sourceType(), d.competitorInStock(), d.matchConfidence());
            sink.accept(TableRegistry.COMPETITOR_OBSERVATION, obs);
            bySku.computeIfAbsent(obs.skuId(), k -> new ArrayList<>()).add(obs);
        }
        bySku.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(bySku);
    }

    private void generatePair(Product product, Competitor competitor, double coverageRate,
                              List<CompetitorObservation> out) {
        Random r = ctx.streams().of("competitor-observation", competitor.name() + "|" + product.skuId());
        if (!Distributions.bernoulli(r, coverageRate)) {
            return;
        }
        LocalDate from = ctx.activeFrom(product);
        LocalDate to = ctx.activeTo(product);
        int activeDays = (int) ctx.activeDays(product);
        double mrp = product.mrp().doubleValue();

        double anchor = mrp * Distributions.uniform(r, ANCHOR_MIN, ANCHOR_MAX);
        boolean hasGap = Distributions.bernoulli(r, GAP_SHARE);
        int gapLength = Distributions.uniformInt(r, GAP_MIN_DAYS, GAP_MAX_DAYS);
        LocalDate gapStart = from.plusDays(Distributions.uniformInt(r, 0, activeDays - 1));
        LocalDate gapEnd = gapStart.plusDays(gapLength); // exclusive

        // Weekly random walk, one level per week since the first active day.
        int weeks = (activeDays + 6) / 7;
        double[] levels = new double[weeks];
        double level = anchor;
        for (int w = 0; w < weeks; w++) {
            if (w > 0) {
                level = level * StrictMath.exp(WEEKLY_SIGMA * Distributions.standardNormal(r));
                level = StrictMath.min(mrp * PRICE_MAX_RATIO, StrictMath.max(mrp * PRICE_MIN_RATIO, level));
            }
            levels[w] = level;
        }

        LocalDate day = from.plusDays(Distributions.uniformInt(r, 0, maxCadence(competitor) - 1));
        while (!day.isAfter(to)) {
            int secondOfDay = Distributions.uniformInt(r, 0, 86_399);
            boolean late = Distributions.bernoulli(r, LATE_FEED_SHARE);
            long lagSeconds = late ? Distributions.uniformInt(r, LATE_MIN_SECONDS, LATE_MAX_SECONDS)
                    : StrictMath.max(60L, StrictMath.round(Distributions.exponential(r, LAG_MEAN_SECONDS)));
            boolean inStock = Distributions.bernoulli(r, IN_STOCK_SHARE);
            boolean manual = Distributions.bernoulli(r, MANUAL_AUDIT_SHARE);
            double confidence = Distributions.betaLike(r, 0.60, CONFIDENCE_THRESHOLD, 1.0, LOW_CONFIDENCE_SHARE);
            int cadence = cadence(competitor, r);

            boolean inGap = hasGap && !day.isBefore(gapStart) && day.isBefore(gapEnd);
            if (!inGap) {
                int week = (int) ((day.toEpochDay() - from.toEpochDay()) / 7);
                BigDecimal price = Amounts.wholeRupees(levels[week]);
                if (price.signum() <= 0) {
                    price = BigDecimal.ONE.setScale(2, RoundingMode.UNNECESSARY);
                }
                Instant captured = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(secondOfDay);
                out.add(new CompetitorObservation(null, product.skuId(), competitor, price, captured,
                        captured.plusSeconds(lagSeconds), manual ? ObservationSource.MANUAL_AUDIT : primarySource(competitor),
                        inStock, new BigDecimal(confidence).setScale(3, RoundingMode.DOWN)));
            }
            day = day.plusDays(cadence);
        }
    }

    private static int cadence(Competitor competitor, Random r) {
        return switch (competitor) {
            case COMP_ALPHA -> Distributions.uniformInt(r, 1, 2);
            case COMP_BETA -> Distributions.uniformInt(r, 2, 4);
            case COMP_GAMMA -> {
                r.nextInt(); // same draw count for every competitor
                yield 7;
            }
        };
    }

    private static int maxCadence(Competitor competitor) {
        return switch (competitor) {
            case COMP_ALPHA -> 2;
            case COMP_BETA -> 4;
            case COMP_GAMMA -> 7;
        };
    }

    private static ObservationSource primarySource(Competitor competitor) {
        return switch (competitor) {
            case COMP_ALPHA -> ObservationSource.SCRAPE_FEED;
            case COMP_BETA -> ObservationSource.PARTNER_FEED;
            case COMP_GAMMA -> ObservationSource.SCRAPE_FEED;
        };
    }
}
