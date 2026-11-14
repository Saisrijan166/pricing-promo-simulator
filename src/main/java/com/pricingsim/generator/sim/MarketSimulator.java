package com.pricingsim.generator.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.InventorySnapshot;
import com.pricingsim.domain.model.PriceHistory;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.GenerationContext;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;

/**
 * The day loop (PLAN.md §7.2 step 5): {@code for day in window: for sku in active SKUs ordered by id}. Each SKU-day:
 * <ol>
 *   <li>receive replenishment arrivals ({@code receipts_units});</li>
 *   <li>get the day's price from the {@link PriceSetter} and write {@code price_history};</li>
 *   <li>draw demand (Poisson) at the shelf price, sell {@code min(demand, available)}, the rest is lost sales;</li>
 *   <li>split the units sold into orders, resolving coupons ({@link OrderGenerator});</li>
 *   <li>shrinkage of −1 unit with probability 0.5%;</li>
 *   <li>(s, S) replenishment;</li>
 *   <li>derive {@code days_of_cover}, {@code stockout_flag}, {@code lost_sales_units} and write
 *       {@code inventory_snapshot}.</li>
 * </ol>
 * Random draws come from per-SKU streams ({@code sku-latent}, {@code demand}, {@code operations}, {@code decision},
 * {@code orders}), so a change to one
 * SKU never shifts another SKU's draws.
 */
public final class MarketSimulator {

    static final double SHRINKAGE_PROBABILITY = 0.005;
    /** Days-of-cover denominator floor: {@code max(average daily units sold, 0.1)} (PLAN.md §5.5). */
    static final BigDecimal MIN_AVERAGE_DAILY_SALES = new BigDecimal("0.1");

    private final GenerationContext ctx;
    private final PriceSetter priceSetter;
    private final OrderGenerator orderGenerator;

    public MarketSimulator(GenerationContext ctx, PriceSetter priceSetter, OrderGenerator orderGenerator) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.priceSetter = Objects.requireNonNull(priceSetter, "priceSetter");
        this.orderGenerator = Objects.requireNonNull(orderGenerator, "orderGenerator");
    }

    /** Totals over the simulated SKU-days. */
    public record Stats(long skuDays, long stockoutDays, long demandUnits, long unitsSold, long lostSalesUnits,
                        long receiptsUnits, long shrinkageUnits, long replenishmentOrders) {
    }

    /**
     * Runs the day loop.
     *
     * @param products     products sorted by {@code sku_id}
     * @param costRecords  cost records per SKU
     */
    public Stats run(List<Product> products, Map<String, List<CostRecord>> costRecords, RowSink sink) {
        Map<String, SkuState> states = new TreeMap<>();
        Map<String, Map<YearMonth, CostRecord>> costByMonth = new TreeMap<>();
        List<Product> simulated = new ArrayList<>();
        for (Product product : products) {
            if (ctx.activeDays(product) == 0) {
                continue;
            }
            simulated.add(product);
            states.put(product.skuId(), newState(product));
            Map<YearMonth, CostRecord> months = new TreeMap<>();
            for (CostRecord rec : costRecords.getOrDefault(product.skuId(), List.of())) {
                months.put(IdFactory.costMonthOf(rec.costRecordId()), rec);
            }
            costByMonth.put(product.skuId(), months);
        }

        Counters counters = new Counters();
        for (LocalDate day = ctx.windowStart(); !day.isAfter(ctx.windowEnd()); day = day.plusDays(1)) {
            for (Product product : simulated) {
                if (!ctx.isActive(product, day)) {
                    continue;
                }
                CostRecord cost = costByMonth.get(product.skuId()).get(YearMonth.from(day));
                if (cost == null) {
                    throw new IllegalStateException("No cost record for " + product.skuId() + " on " + day);
                }
                simulateDay(states.get(product.skuId()), day, cost, sink, counters);
            }
            priceSetter.endOfDay(day);
        }
        priceSetter.finish();
        return counters.toStats();
    }

    private SkuState newState(Product product) {
        DemandModel.Latent latent = DemandModel.drawLatent(product, ctx.streams().of("sku-latent", product.skuId()));
        ReplenishmentPolicy.Levels levels = ReplenishmentPolicy.levelsFor(latent);
        return new SkuState(product, latent, levels, ctx.streams().of("demand", product.skuId()),
                ctx.streams().of("operations", product.skuId()), ctx.streams().of("decision", product.skuId()),
                ctx.streams().of("orders", product.skuId()));
    }

    private void simulateDay(SkuState state, LocalDate day, CostRecord cost, RowSink sink, Counters counters) {
        Product product = state.product();

        // 1. Opening stock and receipts. A SKU launched before the window starts with stock between s+1 and S; a SKU
        //    launched in the window starts empty and receives its first delivery (S units) on launch day.
        int opening;
        int receipts;
        if (!state.started()) {
            state.markStarted();
            if (product.launchDate().isBefore(ctx.windowStart())) {
                opening = ReplenishmentPolicy.initialStock(state.levels(), state.operationsRandom());
                receipts = state.receive(day);
            } else {
                opening = 0;
                receipts = state.levels().orderUpTo();
                counters.replenishmentOrders++;
            }
        } else {
            opening = state.onHand();
            receipts = state.receive(day);
        }

        // 2. Price for the day.
        BigDecimal previousListPrice = state.listPrice();
        PriceSetter.DailyPrice price = priceSetter.priceFor(state, day, cost, opening + receipts);
        boolean changed = previousListPrice == null || price.listPrice().compareTo(previousListPrice) != 0;
        ReasonCode changeReason = changed ? price.changeReason() : ReasonCode.NO_CHANGE;
        String priceHistoryId = IdFactory.priceHistory(product.skuId(), day);
        sink.accept(TableRegistry.PRICE_HISTORY, new PriceHistory(priceHistoryId, product.skuId(), day,
                price.listPrice(), price.shelfPrice(), previousListPrice, changeReason, price.sourceDecisionId(),
                cost.costRecordId()));
        state.setListPrice(price.listPrice());

        // 3. Demand and sales.
        int demand = DemandModel.drawDemand(state.latent(), day, price.shelfPrice(), state.demandRandom());
        int available = opening + receipts;
        int sold = StrictMath.min(demand, available);
        int lost = demand - sold;

        // 4. Shrinkage (one draw every day, so the stream does not depend on stock levels).
        boolean shrink = Distributions.bernoulli(state.operationsRandom(), SHRINKAGE_PROBABILITY);
        int adjustment = shrink && available - sold >= 1 ? -1 : 0;
        int closing = available - sold + adjustment;
        orderGenerator.generate(state, day, sold, price, cost, priceHistoryId);

        // 5. Replenishment.
        boolean orderingAllowed = product.discontinuedDate() == null || day.isBefore(product.discontinuedDate()
                .minusDays(ReplenishmentPolicy.STOP_ORDERING_DAYS_BEFORE_DISCONTINUATION));
        if (ReplenishmentPolicy.maybeOrder(state, day, closing, orderingAllowed) > 0) {
            counters.replenishmentOrders++;
        }

        // 6. Derived values.
        state.recordUnitsSold(sold);
        BigDecimal daysOfCover = daysOfCover(closing, state.trailingUnitsSold(), state.trailingDays());
        boolean stockout = closing == 0 || lost > 0;
        sink.accept(TableRegistry.INVENTORY_SNAPSHOT, new InventorySnapshot(
                IdFactory.inventorySnapshot(product.skuId(), day), product.skuId(), day, opening, receipts, sold,
                adjustment, closing, state.onOrder(), demand, lost, stockout, daysOfCover));
        state.setOnHand(closing);

        counters.skuDays++;
        counters.stockoutDays += stockout ? 1 : 0;
        counters.demand += demand;
        counters.sold += sold;
        counters.lost += lost;
        counters.receipts += receipts;
        counters.shrinkage -= adjustment;
    }

    /**
     * {@code closing / max(avg daily units sold over the trailing window, 0.1)}, 2 dp HALF_UP. The trailing window is
     * the last 28 snapshot days of the SKU up to and including this day (fewer at the start of its history), and the
     * average is their units sold divided by their count. Computed exactly in decimals so a validator can recompute it.
     */
    public static BigDecimal daysOfCover(int closing, int trailingUnitsSold, int trailingDays) {
        if (trailingDays < 1) {
            throw new IllegalArgumentException("trailingDays must be >= 1");
        }
        // average <= 0.1  ⇔  sum / days <= 0.1  ⇔  10 · sum <= days (integer test, no rounding).
        if (10L * trailingUnitsSold <= trailingDays) {
            return BigDecimal.valueOf(closing).divide(MIN_AVERAGE_DAILY_SALES, 2, RoundingMode.HALF_UP);
        }
        // Exact form of closing / (sum / days), avoiding a rounded average.
        return BigDecimal.valueOf((long) closing * trailingDays)
                .divide(BigDecimal.valueOf(trailingUnitsSold), 2, RoundingMode.HALF_UP);
    }

    private static final class Counters {
        long skuDays;
        long stockoutDays;
        long demand;
        long sold;
        long lost;
        long receipts;
        long shrinkage;
        long replenishmentOrders;

        Stats toStats() {
            return new Stats(skuDays, stockoutDays, demand, sold, lost, receipts, shrinkage, replenishmentOrders);
        }
    }
}
