package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.pricing.PromotionStackResolver;

/**
 * Sets the price in effect for one SKU-day. {@link MarketSimulator} calls it once per active SKU-day, in day order and
 * then SKU order, after the day's receipts and before demand is drawn. Implementations may only use data visible
 * before the day's decision time (no look-ahead, PLAN.md §7.2 step 5.2).
 */
public interface PriceSetter {

    /**
     * @param stockAtDecision units available when trading starts: previous closing (or the initial stock) plus today's
     *                        receipts
     */
    DailyPrice priceFor(SkuState state, LocalDate day, CostRecord cost, int stockAtDecision);

    /** Called after every SKU of {@code day} has been simulated. */
    default void endOfDay(LocalDate day) {
    }

    /** Called once after the last day, to flush anything held back. */
    default void finish() {
    }

    /**
     * The day's price.
     *
     * @param listPrice        list price in effect
     * @param shelfPrice       list price minus the applied markdowns
     * @param changeReason     reason to record when the list price differs from the previous day (or on the first
     *                         day); the simulator records NO_CHANGE otherwise
     * @param sourceDecisionId decision that set the price
     * @param markdowns        applied markdowns with their per-unit discounts, in application order
     */
    record DailyPrice(BigDecimal listPrice, BigDecimal shelfPrice, ReasonCode changeReason, String sourceDecisionId,
                      List<PromotionStackResolver.Applied> markdowns) {
        public DailyPrice {
            Objects.requireNonNull(listPrice, "listPrice");
            Objects.requireNonNull(shelfPrice, "shelfPrice");
            Objects.requireNonNull(changeReason, "changeReason");
            markdowns = List.copyOf(markdowns);
            if (shelfPrice.compareTo(listPrice) > 0) {
                throw new IllegalArgumentException("shelf price above list price");
            }
            if (shelfPrice.signum() <= 0) {
                throw new IllegalArgumentException("shelf price must be > 0");
            }
            if (listPrice.subtract(PromotionStackResolver.total(markdowns)).compareTo(shelfPrice) != 0) {
                throw new IllegalArgumentException("shelf price must equal list price minus markdowns");
            }
        }
    }
}
