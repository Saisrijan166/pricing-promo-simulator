package com.pricingsim.generator.sim;

import java.time.LocalDate;
import java.util.Random;

import com.pricingsim.generator.rng.Distributions;

/**
 * Continuous-review (s, S) replenishment with stochastic lead times (PLAN.md §7.2 step 5.9).
 *
 * <ul>
 *   <li>Reorder point {@code s = ceil(μ·L + z·√(μ·L))} with planned lead time L = 6.5 days (the mean of 3–10) and
 *       safety factor z = 1; order-up-to {@code S = s + max(1, ceil(μ · 14))}, μ being the expected daily demand at the
 *       reference price.</li>
 *   <li>When closing stock + on order ≤ s, order {@code S − position}; it arrives after U[3, 10] days, doubled for
 *       "fragile supply" SKUs. The planner does not know which SKUs are fragile, so those SKUs stock out more often.</li>
 *   <li>No new orders in the last 14 days before a planned discontinuation.</li>
 * </ul>
 */
public final class ReplenishmentPolicy {

    static final int LEAD_MIN_DAYS = 3;
    static final int LEAD_MAX_DAYS = 10;
    static final double PLANNED_LEAD_DAYS = (LEAD_MIN_DAYS + LEAD_MAX_DAYS) / 2.0;
    static final double SAFETY_Z = 1.0;
    static final int REVIEW_COVER_DAYS = 14;
    static final int FRAGILE_LEAD_MULTIPLIER = 2;
    static final int STOP_ORDERING_DAYS_BEFORE_DISCONTINUATION = 14;

    /** Reorder point and order-up-to level for one SKU. */
    public record Levels(int reorderPoint, int orderUpTo) {
        public Levels {
            if (reorderPoint < 0 || orderUpTo <= reorderPoint) {
                throw new IllegalArgumentException("require 0 <= s < S, was s=" + reorderPoint + " S=" + orderUpTo);
            }
        }
    }

    private ReplenishmentPolicy() {
    }

    static Levels levelsFor(DemandModel.Latent latent) {
        double mu = latent.baseDemand() * DemandModel.MEAN_WEEKDAY_FACTOR;
        double leadDemand = mu * PLANNED_LEAD_DAYS;
        int s = (int) StrictMath.ceil(leadDemand + SAFETY_Z * StrictMath.sqrt(leadDemand));
        int S = s + StrictMath.max(1, (int) StrictMath.ceil(mu * REVIEW_COVER_DAYS));
        return new Levels(s, S);
    }

    /** Opening stock for a SKU launched before the window: uniform between s + 1 and S. */
    static int initialStock(Levels levels, Random r) {
        return Distributions.uniformInt(r, levels.reorderPoint() + 1, levels.orderUpTo());
    }

    /**
     * Places an order if the inventory position is at or below s. Returns the units ordered (0 if none). The lead time
     * is drawn only when an order is placed.
     */
    static int maybeOrder(SkuState state, LocalDate day, int closing, boolean orderingAllowed) {
        if (!orderingAllowed) {
            return 0;
        }
        Levels levels = state.levels();
        int position = closing + state.onOrder();
        if (position > levels.reorderPoint()) {
            return 0;
        }
        int quantity = levels.orderUpTo() - position;
        int lead = Distributions.uniformInt(state.operationsRandom(), LEAD_MIN_DAYS, LEAD_MAX_DAYS);
        if (state.latent().fragile()) {
            lead *= FRAGILE_LEAD_MULTIPLIER;
        }
        state.schedule(day.plusDays(lead), quantity);
        return quantity;
    }
}
