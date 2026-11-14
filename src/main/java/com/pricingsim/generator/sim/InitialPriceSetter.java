package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.Product;
import com.pricingsim.pricing.PricePointRounder;

/**
 * TEMPORARY price setter, replaced by the pricing engine in the next build step. On a SKU's first active day it sets
 * an initial price and then holds it constant; there are no markdowns, so shelf price = list price.
 *
 * <p>The initial price follows the PLAN.md §8 steps that need no competitor or promotion data:
 * <ol>
 *   <li>S2 floor = {@code max(landed / (1 − min_margin_pct), map_price ?? 0)}, rounded up to the paisa;</li>
 *   <li>S3 ceiling = MRP; if the ceiling is below the floor, the price is the floor;</li>
 *   <li>S5 target for an INITIAL decision without a competitor reference = MRP × 0.95;</li>
 *   <li>S8 clamp to [floor, ceiling]; S9 round to a price point inside the bounds.</li>
 * </ol>
 * No price decisions are written, so {@code source_decision_id} stays null until the engine exists.
 */
public final class InitialPriceSetter implements PriceSetter {

    static final BigDecimal INITIAL_TARGET_RATIO = new BigDecimal("0.95");

    @Override
    public DailyPrice priceFor(SkuState state, LocalDate day, CostRecord cost) {
        BigDecimal price = state.listPrice();
        if (price == null) {
            price = initialPrice(state.product(), cost);
        }
        return new DailyPrice(price, price, ReasonCode.INITIAL_PRICE, null);
    }

    static BigDecimal initialPrice(Product product, CostRecord cost) {
        BigDecimal floor = floor(product, cost);
        BigDecimal ceiling = product.mrp();
        if (ceiling.compareTo(floor) < 0) {
            return floor; // guardrail conflict: price = floor
        }
        BigDecimal target = product.mrp().multiply(INITIAL_TARGET_RATIO);
        BigDecimal candidate = target.max(floor).min(ceiling);
        return PricePointRounder.round(candidate, floor, ceiling).price();
    }

    static BigDecimal floor(Product product, CostRecord cost) {
        BigDecimal marginFloor = cost.landedCost()
                .divide(BigDecimal.ONE.subtract(product.minMarginPct()), 10, RoundingMode.HALF_UP);
        BigDecimal map = product.mapPrice() == null ? BigDecimal.ZERO : product.mapPrice();
        return marginFloor.max(map).setScale(2, RoundingMode.CEILING);
    }
}
