package com.pricingsim.generator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.Product;
import com.pricingsim.generator.anomaly.AnomalyInjector;
import com.pricingsim.generator.anomaly.AnomalyPlan;
import com.pricingsim.generator.anomaly.AnomalyPlanner;
import com.pricingsim.generator.anomaly.CandidateCollector;
import com.pricingsim.generator.anomaly.GroundTruthRecord;
import com.pricingsim.generator.master.CompetitorObservationGenerator;
import com.pricingsim.generator.master.CostRecordGenerator;
import com.pricingsim.generator.master.ProductGenerator;
import com.pricingsim.generator.master.PromotionCatalog;
import com.pricingsim.generator.master.PromotionGenerator;
import com.pricingsim.generator.sim.EnginePriceSetter;
import com.pricingsim.generator.sim.MarketSimulator;
import com.pricingsim.generator.sim.OrderGenerator;
import com.pricingsim.generator.sim.SimulationHooks;
import com.pricingsim.generator.rng.RandomStreams;
import com.pricingsim.generator.sink.NullRowSink;
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

    /** Row counts per table (load order), run totals and the anomaly labels. */
    public record Result(Map<String, Long> rowCounts, MarketSimulator.Stats simulation,
                         EnginePriceSetter.Stats decisions, OrderGenerator.Stats orders,
                         List<GroundTruthRecord> groundTruth) {
    }

    /**
     * Generates the dataset into {@code sink}. With anomalies requested, a baseline dry run (rows discarded) first
     * collects eligible records, {@link AnomalyPlanner} picks the targets from the {@code anomaly} streams, and the real
     * run injects them (PLAN.md §7.2 step 7, §9). With {@code anomaliesPerType = 0} the single run is the clean baseline.
     */
    public Result generate(GenerationSettings settings, RowSink sink) {
        Objects.requireNonNull(sink, "sink");
        int perType = settings.profile().anomaliesPerType();
        if (perType == 0) {
            return run(settings, sink, SimulationHooks.NONE, List.of());
        }
        CandidateCollector collector = new CandidateCollector();
        try (NullRowSink dryRun = new NullRowSink()) {
            run(settings, dryRun, collector, List.of());
        }
        AnomalyPlan plan = AnomalyPlanner.plan(collector.candidates(), perType, new RandomStreams(settings.seed()));
        AnomalyInjector injector = new AnomalyInjector(plan, new RandomStreams(settings.seed()));
        Result result = run(settings, sink, injector, null);
        return new Result(result.rowCounts(), result.simulation(), result.decisions(), result.orders(),
                injector.groundTruth());
    }

    private Result run(GenerationSettings settings, RowSink sink, SimulationHooks hooks,
                       List<GroundTruthRecord> groundTruth) {
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
                promotions, sink, hooks);
        OrderGenerator orderGenerator = new OrderGenerator(ctx, promotions, settings.policy(), sink, hooks);
        MarketSimulator.Stats stats = new MarketSimulator(ctx, priceSetter, orderGenerator, hooks)
                .run(products, costRecords, sink);

        return new Result(sink.counts(), stats, priceSetter.stats(), orderGenerator.stats(), groundTruth);
    }
}
