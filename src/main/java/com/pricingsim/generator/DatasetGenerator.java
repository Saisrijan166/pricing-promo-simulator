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
import com.pricingsim.generator.sink.RowSink;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.io.TableSpec;

/**
 * Orchestrates one generation run (PLAN.md §7.2): master data (the market day loop follows). Pure Java with no
 * Spring or database, so the same code runs from the CLI and from tests. The sink decides the output (CSV files or
 * memory); the data itself depends only on {@link GenerationSettings}.
 */
public final class DatasetGenerator {

    /** Tables written by this generator, in load order. */
    public static final List<TableSpec> TABLES = List.of(
            TableRegistry.PRODUCT,
            TableRegistry.PRODUCT_STATUS_EVENT,
            TableRegistry.COST_RECORD,
            TableRegistry.COMPETITOR_OBSERVATION);

    /** Row counts per table (load order). */
    public record Result(Map<String, Long> rowCounts) {
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
        Objects.requireNonNull(costRecords, "costRecords");
        Objects.requireNonNull(observations, "observations");

        return new Result(sink.counts());
    }
}
