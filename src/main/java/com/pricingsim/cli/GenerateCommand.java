package com.pricingsim.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.config.PricingPolicyProperties;
import com.pricingsim.config.ScaleProfile;
import com.pricingsim.generator.DatasetGenerator;
import com.pricingsim.generator.GenerationSettings;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.generator.sim.EnginePriceSetter;
import com.pricingsim.generator.sim.MarketSimulator;
import com.pricingsim.generator.sim.OrderGenerator;
import com.pricingsim.generator.sink.CsvRowSink;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code generate}: writes the dataset as CSV to {@code <out>/csv/<table>.csv} (PLAN.md §11). Runs under the
 * {@code offline} profile, so it needs no database. The output depends only on the seed and the scale settings, so
 * generating twice into different directories gives byte-identical files.
 */
@Component
@Command(name = "generate", mixinStandardHelpOptions = true,
        description = "Generate the synthetic dataset as CSV (no database needed).")
public class GenerateCommand implements Callable<Integer> {

    /** Sub-folder of the output directory that holds the CSV files. */
    public static final String CSV_DIR = "csv";

    @Mixin
    ScaleOptions scaleOptions = new ScaleOptions();

    @Spec
    CommandSpec spec;

    private final GeneratorProperties properties;
    private final PricingPolicyProperties policyProperties;

    public GenerateCommand(GeneratorProperties properties, PricingPolicyProperties policyProperties) {
        this.properties = properties;
        this.policyProperties = policyProperties;
    }

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        List<String> commandLine = new ArrayList<>();
        commandLine.add("generate");
        commandLine.addAll(spec.commandLine().getParseResult().originalArgs());

        GenerationSettings settings = scaleOptions.toSettings(properties, policyProperties.toPolicy(), commandLine);
        Path outDir = scaleOptions.outputDirectory(properties);
        Path csvDir = outDir.resolve(CSV_DIR);
        clearCsvDirectory(csvDir);

        ScaleProfile p = settings.profile();
        out.printf("Generating scale=%s seed=%d skus=%d days=%d (%s to %s) promotions=%d anomalies-per-type=%d"
                        + " competitors=%d%n",
                settings.scaleName(), settings.seed(), p.skus(), p.days(), settings.windowStart(),
                settings.windowEnd(), p.promotions(), p.anomaliesPerType(), p.competitors());
        out.println("Note: anomalies-per-type is recorded; anomaly injection is added in a later step.");

        long started = System.nanoTime(); // timing for the console only; never written to data
        DatasetGenerator.Result result;
        try (CsvRowSink sink = new CsvRowSink(csvDir)) {
            result = new DatasetGenerator().generate(settings, sink);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        out.println();
        out.printf("%-26s %12s%n", "table", "rows");
        for (Map.Entry<String, Long> e : result.rowCounts().entrySet()) {
            out.printf("%-26s %12d%n", e.getKey(), e.getValue());
        }
        printSummary(out, result);
        out.printf("%nWrote %s in %d ms%n", csvDir, elapsedMs);
        out.flush();
        return 0;
    }

    static void printSummary(PrintWriter out, DatasetGenerator.Result result) {
        Locale l = Locale.ROOT;
        MarketSimulator.Stats s = result.simulation();
        EnginePriceSetter.Stats d = result.decisions();
        OrderGenerator.Stats o = result.orders();
        out.println();
        out.printf(l, "SKU-days %d, units sold %d, lost sales %d, replenishment orders %d%n",
                s.skuDays(), s.unitsSold(), s.lostSalesUnits(), s.replenishmentOrders());
        out.printf(l, "Decisions %d %s, escalated %d, manual overrides %d%n", d.decisions(), d.byTrigger(),
                d.escalated(), d.manualOverrides());
        out.printf(l, "Orders %d (cancelled %d), coupons presented %d, applied %d, rejected %d%n", o.orders(),
                o.cancelled(), o.couponsPresented(), o.couponsApplied(), o.couponsRejected());
        out.println();
        out.println("Operational exceptions:");
        out.printf(l, "  %-32s %10d%n", "COMPETITOR_DATA_STALE decisions", d.withReason(ReasonCode.COMPETITOR_DATA_STALE));
        out.printf(l, "  %-32s %10d%n", "GUARDRAIL_CONFLICT_ESCALATED",
                d.withReason(ReasonCode.GUARDRAIL_CONFLICT_ESCALATED));
        out.printf(l, "  %-32s %10d (%.2f%% of SKU-days)%n", "Stockout days", s.stockoutDays(),
                s.skuDays() == 0 ? 0.0 : 100.0 * s.stockoutDays() / s.skuDays());
        out.printf(l, "  %-32s %10d%n", "REJECTED coupons", o.couponsRejected());
    }

    /** Removes CSV files left by a previous run, so the directory holds exactly this run's tables. */
    static void clearCsvDirectory(Path csvDir) throws IOException {
        if (!Files.isDirectory(csvDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(csvDir)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".csv")) {
                    Files.delete(file);
                }
            }
        }
    }
}
