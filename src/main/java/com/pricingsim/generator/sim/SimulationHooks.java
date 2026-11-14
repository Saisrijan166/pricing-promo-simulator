package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.model.CustomerOrder;
import com.pricingsim.domain.model.PriceHistory;
import com.pricingsim.pricing.PricingContext;
import com.pricingsim.pricing.PricingEngine;
import com.pricingsim.pricing.PricingResult;
import com.pricingsim.pricing.PromotionStackResolver;

/**
 * Extension points of the day loop, used by anomaly injection (PLAN.md §7.2 step 7, §9). Every hook sees the valid
 * baseline record before it is written. {@link #NONE} changes nothing, which is the clean dataset.
 */
public interface SimulationHooks {

    SimulationHooks NONE = new SimulationHooks() {
    };

    /** Faults for a decision, given its baseline result; null for none. */
    default PricingEngine.Faults decisionFaults(DecisionTrigger trigger, PricingContext context, PricingResult baseline) {
        return null;
    }

    /** After a decision is assigned its ID ({@code result} equals {@code baseline} unless faults were returned). */
    default void afterDecision(PricingContext context, PricingResult baseline, PricingResult result,
                               String decisionId) {
    }

    /** On a day without a decision: a replacement price for the rest of the decision's span, or null. */
    default PriceSetter.DailyPrice nonDecisionDay(SkuState state, LocalDate day, PriceSetter.DailyPrice current) {
        return null;
    }

    /** After each {@code price_history} row is written. */
    default void afterPriceHistory(PriceHistory row) {
    }

    /**
     * For the {@code orderIndex}-th order of a SKU-day (in write order): a rejected coupon to force to APPLIED, or null.
     */
    default PromotionStackResolver.Evaluation couponToForce(String skuId, LocalDate day, int orderIndex,
                                                            PromotionStackResolver.Resolution resolution,
                                                            BigDecimal gross, BigDecimal seededTotal) {
        return null;
    }

    /** After an order is written; {@code forcedPromotionId} is the coupon forced to APPLIED, or null. */
    default void afterOrder(CustomerOrder order, String forcedPromotionId) {
    }
}
