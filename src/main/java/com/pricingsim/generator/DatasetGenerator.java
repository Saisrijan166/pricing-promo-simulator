package com.pricingsim.generator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.master.CompetitorObservationGenerator;
import com.pricingsim.generator.master.CostRecordGenerator;
import com.pricingsim.generator.master.ProductGenerator;
import com.pricingsim.generator.master.PromotionCatalog;
import com.pricingsim.generator.master.PromotionGenerator;
import com.pricingsim.generator.sim.EnginePriceSetter;
import com.pricingsim.generator.sim.MarketSimulator;
import com.pricingsim.generator.sim.OrderGenerator;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.io.TableSpec;
import com.pricingsim.pricing.PricingEngine;

/**
 * Orchestrates one generation run (PLAN.md §7.2): master data first, then the market day loop. Pure Java with no
 * Spring or database, so the same code runs from the CLI and from tests. The sink decides the output (CSV files or
 * memory); the data itself depends only on {@link GenerationSettings}.
 */
public final class DatasetGenerator {

    /** Every data table, in load order (all 16 in {@link TableRegistry}). */
    public static final List<TableSpec> TABLES = TableRegistry.loadOrder();

    /** Row counts per table (load order) and run totals. */
    public record Result(Map<String, Long> rowCounts, MarketSimulator.Stats simulation,
                         EnginePriceSetter.Stats decisions, OrderGenerator.Stats orders) {
    }

    public Result generate(GenerationSettings settings, RowSink sink) {
        Objects.requireNonNull(sink, "sink");
        GenerationContext ctx = new GenerationContext(settings);
        ctx.checkCapacity();
        TABLES.forEach(sink::declare);

        // 1. Products and their status events.
        List<Product> products = new ProductGenerator(ctx).generate(sink);
        // 2. Monthly cost records.
        Map<String, List<CostRecord>> costRecords = new CostRecordGenerator(ctx).generate(products, sink);
        // 3. Promotions, rules, promotion_product and status events.
        PromotionCatalog promotions = new PromotionGenerator(ctx).generate(products, sink);
        // 4. Competitor observations (competitors do not react to our prices).
        Map<String, List<CompetitorObservation>> observations =
                new CompetitorObservationGenerator(ctx).generate(products, sink);
        // 5. Market day loop: decisions, price history, orders and inventory.
        EnginePriceSetter priceSetter = new EnginePriceSetter(ctx, new PricingEngine(settings.policy()), observations,
                promotions, sink);
        OrderGenerator orderGenerator = new OrderGenerator(ctx, promotions, settings.policy(), sink);
        MarketSimulator.Stats stats = new MarketSimulator(ctx, priceSetter, orderGenerator)
                .run(products, costRecords, sink);

        return new Result(sink.counts(), stats, priceSetter.stats(), orderGenerator.stats());
    }
}
