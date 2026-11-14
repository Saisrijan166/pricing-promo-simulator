package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;

import com.pricingsim.domain.model.Product;

/**
 * Mutable per-SKU simulation state (PLAN.md §3 sim/SkuState). It holds the latent parameters, which are never
 * exported (PLAN.md §2 assumption 13), the stock position, the replenishment pipeline, trailing sales and the current
 * list price. The simulator keeps one instance per SKU, so memory grows with SKUs, not with days.
 */
public final class SkuState {

    /** Trailing window used for days of cover (PLAN.md §5.5). */
    public static final int COVER_WINDOW_DAYS = 28;

    private final Product product;
    private final DemandModel.Latent latent;
    private final ReplenishmentPolicy.Levels levels;
    private final Random demandRandom;
    private final Random operationsRandom;

    private final TreeMap<LocalDate, Integer> pipeline = new TreeMap<>();
    private final Deque<Integer> recentUnitsSold = new ArrayDeque<>(COVER_WINDOW_DAYS);
    private int recentUnitsSoldSum;

    private boolean started;
    private int onHand;
    private BigDecimal listPrice;

    SkuState(Product product, DemandModel.Latent latent, ReplenishmentPolicy.Levels levels, Random demandRandom,
             Random operationsRandom) {
        this.product = Objects.requireNonNull(product, "product");
        this.latent = Objects.requireNonNull(latent, "latent");
        this.levels = Objects.requireNonNull(levels, "levels");
        this.demandRandom = demandRandom;
        this.operationsRandom = operationsRandom;
    }

    public Product product() {
        return product;
    }

    public String skuId() {
        return product.skuId();
    }

    DemandModel.Latent latent() {
        return latent;
    }

    ReplenishmentPolicy.Levels levels() {
        return levels;
    }

    Random demandRandom() {
        return demandRandom;
    }

    Random operationsRandom() {
        return operationsRandom;
    }

    /** {@code true} once the SKU has had its first simulated day. */
    public boolean started() {
        return started;
    }

    void markStarted() {
        started = true;
    }

    /** Closing stock of the previous simulated day (the opening stock of the current day). */
    public int onHand() {
        return onHand;
    }

    void setOnHand(int onHand) {
        if (onHand < 0) {
            throw new IllegalStateException(skuId() + ": negative stock " + onHand);
        }
        this.onHand = onHand;
    }

    /** List price of the previous simulated day, or {@code null} before the first day. */
    public BigDecimal listPrice() {
        return listPrice;
    }

    void setListPrice(BigDecimal listPrice) {
        this.listPrice = listPrice;
    }

    /** Removes and returns the units arriving on {@code day}. */
    int receive(LocalDate day) {
        Integer units = pipeline.remove(day);
        return units == null ? 0 : units;
    }

    void schedule(LocalDate arrival, int units) {
        if (units <= 0) {
            throw new IllegalArgumentException("units must be > 0");
        }
        pipeline.merge(arrival, units, Integer::sum);
    }

    /** Units ordered but not yet received. */
    public int onOrder() {
        int total = 0;
        for (int units : pipeline.values()) {
            total += units;
        }
        return total;
    }

    /** Read-only view of scheduled arrivals by date. */
    public Map<LocalDate, Integer> pipeline() {
        return java.util.Collections.unmodifiableSortedMap(pipeline);
    }

    /** Records today's units sold in the trailing window (the oldest day drops out after 28 days). */
    void recordUnitsSold(int units) {
        if (recentUnitsSold.size() == COVER_WINDOW_DAYS) {
            recentUnitsSoldSum -= recentUnitsSold.removeFirst();
        }
        recentUnitsSold.addLast(units);
        recentUnitsSoldSum += units;
    }

    /** Units sold over the recorded trailing days (at most 28, including today once recorded). */
    public int trailingUnitsSold() {
        return recentUnitsSoldSum;
    }

    /** Number of days in the trailing window so far (1–28 once the SKU has started). */
    public int trailingDays() {
        return recentUnitsSold.size();
    }
}
