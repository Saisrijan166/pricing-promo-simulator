package com.pricingsim.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

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
import com.pricingsim.generator.DatasetWriter;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code generate}: writes the dataset to {@code <out>}: {@code csv/<table>.csv}, {@code labels/}, {@code manifest.json}
 * and {@code checksums.sha256} (PLAN.md §11, see {@link DatasetWriter}). Runs under the
 * {@code offline} profile, so it needs no database. The output depends only on the seed and the scale settings, so
 * generating twice into different directories gives byte-identical files.
 */
@Component
@Command(name = "generate", mixinStandardHelpOptions = true,
        description = "Generate the synthetic dataset as CSV (no database needed).")
public class GenerateCommand implements Callable<Integer> {


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
        // originalArgs() holds the whole command line, including the subcommand name.
        List<String> commandLine = new ArrayList<>(spec.commandLine().getParseResult().originalArgs());

        GenerationSettings settings = scaleOptions.toSettings(properties, policyProperties.toPolicy(), commandLine);
        Path outDir = scaleOptions.outputDirectory(properties);

        ScaleProfile p = settings.profile();
        out.printf("Generating scale=%s seed=%d skus=%d days=%d (%s to %s) promotions=%d anomalies-per-type=%d"
                        + " competitors=%d%n",
                settings.scaleName(), settings.seed(), p.skus(), p.days(), settings.windowStart(),
                settings.windowEnd(), p.promotions(), p.anomaliesPerType(), p.competitors());
        out.println("Note: anomalies-per-type is recorded; anomaly injection is added in a later step.");

        long started = System.nanoTime(); // timing for the console only; never written to data
        DatasetWriter.Output output = DatasetWriter.write(settings, outDir);
        DatasetGenerator.Result result = output.result();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        out.println();
        out.printf("%-26s %12s%n", "table", "rows");
        for (Map.Entry<String, Long> e : result.rowCounts().entrySet()) {
            out.printf("%-26s %12d%n", e.getKey(), e.getValue());
        }
        printSummary(out, result);
        out.printf("%nDataset fingerprint %s%n", output.fingerprint());
        out.printf("Wrote %s (csv/, labels/, manifest.json, checksums.sha256) in %d ms%n", outDir, elapsedMs);
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
}
