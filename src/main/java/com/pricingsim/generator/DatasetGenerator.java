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
import com.pricingsim.generator.sim.InitialPriceSetter;
import com.pricingsim.generator.sim.MarketSimulator;
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.io.TableSpec;

/**
 * Orchestrates one generation run (PLAN.md §7.2): master data first, then the market day loop. Pure Java with no
 * Spring or database, so the same code runs from the CLI and from tests. The sink decides the output (CSV files or
 * memory); the data itself depends only on {@link GenerationSettings}.
 */
public final class DatasetGenerator {

    /** Tables written by this generator, in load order. */
    public static final List<TableSpec> TABLES = List.of(
            TableRegistry.PRODUCT,
            TableRegistry.PRODUCT_STATUS_EVENT,
            TableRegistry.COST_RECORD,
            TableRegistry.COMPETITOR_OBSERVATION,
            TableRegistry.PRICE_HISTORY,
            TableRegistry.INVENTORY_SNAPSHOT);

    /** Row counts per table (load order) and simulation totals. */
    public record Result(Map<String, Long> rowCounts, MarketSimulator.Stats simulation) {
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
        // 4. Competitor observations (step 3, promotions, is added with the pricing engine).
        Map<String, List<CompetitorObservation>> observations =
                new CompetitorObservationGenerator(ctx).generate(products, sink);
        // 5. Market day loop: price history and inventory.
        MarketSimulator.Stats stats = new MarketSimulator(ctx, new InitialPriceSetter())
                .run(products, costRecords, observations, sink);

        return new Result(sink.counts(), stats);
    }
}
