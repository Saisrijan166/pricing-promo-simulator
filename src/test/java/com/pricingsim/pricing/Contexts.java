package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.ObservationSource;
import com.pricingsim.domain.enums.PricePosition;
import com.pricingsim.domain.model.CompetitorObservation;

/**
 * Fluent {@link PricingContext} fixture. Defaults: Monday 05-01-2026 06:00Z weekly review, MRP 199, landed 100,
 * min margin 0.20, min promo margin 0.10, MATCH, previous price 169, 50 on hand, 28 days of history selling 2/day
 * (cover 25 days, velocity 1.0), no observations, no promotions.
 */
public final class Contexts {

    public static final LocalDate DAY = LocalDate.of(2026, 1, 5);
    public static final Instant TS = Instant.parse("2026-01-05T06:00:00Z");

    private DecisionTrigger trigger = DecisionTrigger.WEEKLY_REVIEW;
    private BigDecimal mrp = new BigDecimal("199.00");
    private BigDecimal map;
    private BigDecimal minMargin = new BigDecimal("0.2000");
    private BigDecimal minPromoMargin = new BigDecimal("0.1000");
    private PricePosition position = PricePosition.MATCH;
    private BigDecimal landed = new BigDecimal("100.00");
    private BigDecimal prev = new BigDecimal("169.00");
    private int onHand = 50;
    private int d7 = 14;
    private int n7 = 7;
    private int d28 = 56;
    private int n28 = 28;
    private final List<CompetitorObservation> observations = new ArrayList<>();
    private final List<PromotionOffer> promotions = new ArrayList<>();
    private BigDecimal manual;
    private int obsSeq = 1;

    public static Contexts weekly() {
        return new Contexts();
    }

    public static Contexts initial() {
        Contexts c = new Contexts();
        c.trigger = DecisionTrigger.INITIAL;
        c.prev = null;
        c.d7 = 0;
        c.n7 = 0;
        c.d28 = 0;
        c.n28 = 0;
        return c;
    }

    public Contexts trigger(DecisionTrigger t) {
        trigger = t;
        return this;
    }

    public Contexts mrp(String v) {
        mrp = new BigDecimal(v);
        return this;
    }

    public Contexts map(String v) {
        map = new BigDecimal(v);
        return this;
    }

    public Contexts minMargin(String v) {
        minMargin = new BigDecimal(v);
        return this;
    }

    public Contexts minPromoMargin(String v) {
        minPromoMargin = new BigDecimal(v);
        return this;
    }

    public Contexts position(PricePosition p) {
        position = p;
        return this;
    }

    public Contexts landed(String v) {
        landed = new BigDecimal(v);
        return this;
    }

    public Contexts prev(String v) {
        prev = v == null ? null : new BigDecimal(v);
        return this;
    }

    public Contexts onHand(int v) {
        onHand = v;
        return this;
    }

    /** Sales history: units in the last 7 / 28 days and how many of those days have history. */
    public Contexts sales(int d7, int n7, int d28, int n28) {
        this.d7 = d7;
        this.n7 = n7;
        this.d28 = d28;
        this.n28 = n28;
        return this;
    }

    public Contexts manual(String v) {
        manual = new BigDecimal(v);
        return this;
    }

    /** Observation captured {@code ageHours} before the decision, ingested 1 h after capture, confidence 0.95. */
    public Contexts observation(Competitor c, String price, long ageHours) {
        return observation(c, price, TS.minusSeconds(ageHours * 3600), "0.950");
    }

    public Contexts observation(Competitor c, String price, Instant captured, String confidence) {
        Instant ingested = captured.plusSeconds(3600).isAfter(TS) ? captured : captured.plusSeconds(3600);
        return observation(c, price, captured, ingested, confidence);
    }

    public Contexts observation(Competitor c, String price, Instant captured, Instant ingested, String confidence) {
        observations.add(new CompetitorObservation(String.format("OBS-2026-%07d", obsSeq++), "SKU-000001", c,
                new BigDecimal(price), captured, ingested, ObservationSource.SCRAPE_FEED, true,
                new BigDecimal(confidence)));
        return this;
    }

    public Contexts promotions(PromotionOffer... offers) {
        promotions.addAll(List.of(offers));
        return this;
    }

    public PricingContext build() {
        return new PricingContext("SKU-000001", DAY, TS, trigger, mrp, map, minMargin, minPromoMargin, position,
                "COST-SKU-000001-2026-01", landed, prev, prev == null ? null : "DEC-2026-000001", onHand, d7, n7, d28,
                n28, observations, promotions, manual);
    }
}
