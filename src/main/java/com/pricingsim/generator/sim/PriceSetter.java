package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.model.CostRecord;

/**
 * Sets the price in effect for one SKU-day. {@link MarketSimulator} calls it once per active SKU-day, in day order and
 * then SKU order, before demand is drawn. Implementations may only use data visible before the day's decision time
 * (no look-ahead, PLAN.md §7.2 step 5.2); the state passed in holds the previous day's closing stock and list price.
 *
 * <p>{@link InitialPriceSetter} is a temporary implementation; the pricing-engine setter replaces it and also writes
 * price decisions.
 */
public interface PriceSetter {

    DailyPrice priceFor(SkuState state, LocalDate day, CostRecord cost);

    /**
     * The day's price.
     *
     * @param listPrice        list price in effect
     * @param shelfPrice       list price after applied markdowns ({@code <= listPrice})
     * @param changeReason     reason to record when the list price differs from the previous day (or on the first
     *                         day); the simulator records NO_CHANGE otherwise
     * @param sourceDecisionId decision that set the price, or {@code null} when no decision exists yet
     */
    record DailyPrice(BigDecimal listPrice, BigDecimal shelfPrice, ReasonCode changeReason, String sourceDecisionId) {
        public DailyPrice {
            Objects.requireNonNull(listPrice, "listPrice");
            Objects.requireNonNull(shelfPrice, "shelfPrice");
            Objects.requireNonNull(changeReason, "changeReason");
            if (shelfPrice.compareTo(listPrice) > 0) {
                throw new IllegalArgumentException("shelf price above list price");
            }
            if (shelfPrice.signum() <= 0) {
                throw new IllegalArgumentException("shelf price must be > 0");
            }
        }
    }
}
