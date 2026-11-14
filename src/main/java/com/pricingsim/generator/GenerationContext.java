package com.pricingsim.generator;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.pricingsim.config.ScaleProfile;
import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.id.SequenceCounter;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.rng.RandomStreams;

/**
 * Shared state for one generation run: settings, the fixed window, random streams and the per-year ID counters.
 * There is no clock here: every timestamp is derived from the window dates.
 */
public final class GenerationContext {

    private final GenerationSettings settings;
    private final RandomStreams streams;
    private final Map<IdFactory.Sequence, Map<Integer, SequenceCounter>> counters =
            new EnumMap<>(IdFactory.Sequence.class);

    public GenerationContext(GenerationSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.streams = new RandomStreams(settings.seed());
    }

    public GenerationSettings settings() {
        return settings;
    }

    public ScaleProfile profile() {
        return settings.profile();
    }

    public RandomStreams streams() {
        return streams;
    }

    public LocalDate windowStart() {
        return settings.windowStart();
    }

    /** Last day of the window (inclusive). */
    public LocalDate windowEnd() {
        return settings.windowEnd();
    }

    /** Competitors observed in this run: the first {@code profile.competitors()} of {@link Competitor}. */
    public List<Competitor> competitors() {
        return List.of(Competitor.values()).subList(0, profile().competitors());
    }

    /**
     * Next ID of a per-year sequence. Counters are kept per (sequence, year), so IDs are only reproducible when callers
     * request them in a total order (PLAN.md §6 rule 7).
     */
    public String nextId(IdFactory.Sequence sequence, int year) {
        return counters.computeIfAbsent(sequence, s -> new TreeMap<>())
                .computeIfAbsent(year, y -> SequenceCounter.forSequence(sequence, y))
                .nextId();
    }

    // --- Product active window ------------------------------------------------------------------------------------

    /**
     * First day the product is sold in the window: {@code max(launch_date, window start)}. A product is active on
     * {@code launch_date} and every later day before {@code discontinued_date} (the discontinuation day itself is not
     * active, because the DISCONTINUED event is at 00:00Z that day).
     */
    public LocalDate activeFrom(Product product) {
        return max(product.launchDate(), windowStart());
    }

    /** Last active day in the window (inclusive): {@code min(discontinued_date − 1, window end)}. */
    public LocalDate activeTo(Product product) {
        LocalDate end = windowEnd();
        if (product.discontinuedDate() != null) {
            LocalDate lastDay = product.discontinuedDate().minusDays(1);
            if (lastDay.isBefore(end)) {
                end = lastDay;
            }
        }
        return end;
    }

    public boolean isActive(Product product, LocalDate day) {
        return !day.isBefore(activeFrom(product)) && !day.isAfter(activeTo(product));
    }

    /** Number of active days in the window (0 if none). */
    public long activeDays(Product product) {
        LocalDate from = activeFrom(product);
        LocalDate to = activeTo(product);
        return to.isBefore(from) ? 0 : to.toEpochDay() - from.toEpochDay() + 1;
    }

    // --- Capacity -------------------------------------------------------------------------------------------------

    /**
     * Fails fast if the scale could overflow a fixed-width ID (PLAN.md §2 limitations, §6 rule 3), using upper bounds
     * that hold for every seed.
     */
    public void checkCapacity() {
        ScaleProfile p = profile();
        requireFits("SKU", p.skus(), SequenceCounter.maxValue(IdFactory.SKU_WIDTH));
        // At most 3 status events per product (∅→NEW, NEW→ACTIVE / ∅→ACTIVE, ACTIVE→DISCONTINUED), all could share a year.
        requireFits("PEV", 3L * p.skus(), IdFactory.Sequence.PRODUCT_EVENT.capacity());
        // At most one observation per SKU, competitor and day.
        requireFits("OBS", (long) p.skus() * p.competitors() * p.days(), IdFactory.Sequence.OBSERVATION.capacity());
    }

    private static void requireFits(String what, long needed, long capacity) {
        if (needed > capacity) {
            throw new IllegalStateException("Scale too large: up to " + needed + " " + what + " IDs needed but the ID"
                    + " format allows " + capacity + " (PLAN.md §6). Reduce --skus or --days.");
        }
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
