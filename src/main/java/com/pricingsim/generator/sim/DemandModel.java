package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Random;

import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.master.CategoryCatalog;
import com.pricingsim.generator.rng.Distributions;

/**
 * Daily demand (PLAN.md §7.2 step 5.5): {@code λ = base_demand × weekday factor × (shelf_price / ref_price)^elasticity}
 * and {@code demand ~ Poisson(λ)}. Saturday and Sunday have factor 1.25, other days 1.0.
 *
 * <p>The latent parameters (base demand, elasticity, reference price, supply fragility) are drawn per SKU from stream
 * {@code sku-latent} and stay inside the generator; exporting them would leak the data-generating truth
 * (PLAN.md §2 assumption 13). Base demand is lognormal with sigma 1.2, which gives a Pareto-like concentration
 * (top 20% of SKUs ≈ 64% of units, inside the PLAN.md §7.5 band of 55–85%).
 */
public final class DemandModel {

    public static final double WEEKEND_FACTOR = 1.25;
    static final double BASE_DEMAND_MEDIAN = 0.6;
    static final double BASE_DEMAND_SIGMA = 1.2;
    static final double BASE_DEMAND_MIN = 0.02;
    static final double BASE_DEMAND_MAX = 200.0;
    static final double ELASTICITY_MIN = -2.5;
    static final double ELASTICITY_MAX = -0.8;
    static final double REF_PRICE_MIN = 0.88;
    static final double REF_PRICE_MAX = 0.98;
    static final double FRAGILE_SHARE = 0.05;

    /** Average weekday factor over a week: (5 × 1.0 + 2 × 1.25) / 7. */
    public static final double MEAN_WEEKDAY_FACTOR = (5 + 2 * WEEKEND_FACTOR) / 7.0;

    /**
     * Latent per-SKU parameters (never exported).
     *
     * @param baseDemand  expected units per weekday at the reference price
     * @param elasticity  constant price elasticity (negative)
     * @param refPrice    reference price at which the price factor is 1
     * @param fragile     "fragile supply": replenishment lead times are doubled
     */
    public record Latent(double baseDemand, double elasticity, double refPrice, boolean fragile) {
    }

    private DemandModel() {
    }

    /** Draws a SKU's latent parameters from its own stream. Four draw groups, always in the same order. */
    static Latent drawLatent(Product product, Random r) {
        double factor = CategoryCatalog.of(product.category()).demandFactor();
        double base = Distributions.lognormalMedian(r, BASE_DEMAND_MEDIAN * factor, BASE_DEMAND_SIGMA);
        base = StrictMath.min(BASE_DEMAND_MAX, StrictMath.max(BASE_DEMAND_MIN, base));
        double elasticity = Distributions.uniform(r, ELASTICITY_MIN, ELASTICITY_MAX);
        double refPrice = product.mrp().doubleValue() * Distributions.uniform(r, REF_PRICE_MIN, REF_PRICE_MAX);
        boolean fragile = Distributions.bernoulli(r, FRAGILE_SHARE);
        return new Latent(base, elasticity, refPrice, fragile);
    }

    public static double weekdayFactor(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY ? WEEKEND_FACTOR : 1.0;
    }

    /** Expected demand {@code λ} for one SKU-day at the given shelf price. */
    static double lambda(Latent latent, LocalDate day, BigDecimal shelfPrice) {
        double priceFactor = StrictMath.pow(shelfPrice.doubleValue() / latent.refPrice(), latent.elasticity());
        return latent.baseDemand() * weekdayFactor(day) * priceFactor;
    }

    /** Demand units for one SKU-day: Poisson(λ). */
    static int drawDemand(Latent latent, LocalDate day, BigDecimal shelfPrice, Random r) {
        return Distributions.poisson(r, lambda(latent, day, shelfPrice));
    }
}
