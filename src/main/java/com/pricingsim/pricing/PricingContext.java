package com.pricingsim.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.PricePosition;
import com.pricingsim.domain.model.CompetitorObservation;

/**
 * Immutable snapshot of everything one decision may use (PLAN.md §8 S1). The caller assembles it from data visible
 * before {@code decisionTs} only; everything here is stored on the decision or its bridges, so the decision can be
 * replayed.
 *
 * @param onHandUnits       stock available at the decision: the day's opening stock plus receipts delivered before
 *                          trading (= previous day's closing + today's receipts)
 * @param demand7dUnits     units sold in the 7 days before {@code effectiveDate}
 * @param salesDays7        days of sales history inside that 7-day window (0–7)
 * @param demand28dUnits    units sold in the 28 days before {@code effectiveDate}
 * @param salesDays28       days of sales history inside that 28-day window (0–28)
 * @param observations      competitor observations of the SKU (the resolver applies the visibility rule)
 * @param promotions        promotions that include the SKU (the engine applies eligibility; coupons are ignored)
 * @param manualTargetPrice analyst's override price, or null for an engine decision
 */
public record PricingContext(
        String skuId,
        LocalDate effectiveDate,
        Instant decisionTs,
        DecisionTrigger trigger,
        BigDecimal mrp,
        BigDecimal mapPrice,
        BigDecimal minMarginPct,
        BigDecimal minPromoMarginPct,
        PricePosition pricePosition,
        String costRecordId,
        BigDecimal landedCost,
        BigDecimal prevListPrice,
        String previousDecisionId,
        int onHandUnits,
        int demand7dUnits,
        int salesDays7,
        int demand28dUnits,
        int salesDays28,
        List<CompetitorObservation> observations,
        List<PromotionOffer> promotions,
        BigDecimal manualTargetPrice) {

    public PricingContext {
        Objects.requireNonNull(skuId, "skuId");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(decisionTs, "decisionTs");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(mrp, "mrp");
        Objects.requireNonNull(minMarginPct, "minMarginPct");
        Objects.requireNonNull(minPromoMarginPct, "minPromoMarginPct");
        Objects.requireNonNull(pricePosition, "pricePosition");
        Objects.requireNonNull(costRecordId, "costRecordId");
        Objects.requireNonNull(landedCost, "landedCost");
        observations = List.copyOf(observations);
        promotions = List.copyOf(promotions);
        if ((trigger == DecisionTrigger.INITIAL) != (prevListPrice == null)) {
            throw new IllegalArgumentException("prevListPrice is null exactly for INITIAL decisions");
        }
        if (onHandUnits < 0) {
            throw new IllegalArgumentException("onHandUnits must be >= 0");
        }
    }

    public boolean manualOverride() {
        return manualTargetPrice != null;
    }
}
