package com.pricingsim.generator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.pricingsim.config.ScaleProfile;
import com.pricingsim.generator.sink.CsvRowSink;
import com.pricingsim.io.ChecksumService;
import com.pricingsim.io.CsvDatasetReader;
import com.pricingsim.io.JsonText;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.io.TableSpec;
import com.pricingsim.pricing.PricingPolicy;

/**
 * Generates a dataset into a directory (PLAN.md §3 data layout, §11):
 * <pre>
 *   &lt;out&gt;/csv/&lt;table&gt;.csv          16 data tables
 *   &lt;out&gt;/labels/ground_truth.csv   anomaly labels (never under csv/)
 *   &lt;out&gt;/labels/ground_truth.json
 *   &lt;out&gt;/manifest.json             how it was made, rows, columns, sha256 per file, fingerprint
 *   &lt;out&gt;/checksums.sha256           sha256sum -c compatible
 * </pre>
 * Files a previous run left in {@code csv/} and {@code labels/} are removed first.
 */
public final class DatasetWriter {

    public static final String LABELS_DIR = "labels";

    /** A written dataset. */
    public record Output(Path directory, DatasetGenerator.Result result, String fingerprint) {
    }

    private DatasetWriter() {
    }

    public static Output write(GenerationSettings settings, Path outDir) {
        Path csvDir = outDir.resolve(CsvDatasetReader.CSV_DIR);
        clear(csvDir);
        clear(outDir.resolve(LABELS_DIR));
        DatasetGenerator.Result result;
        try (CsvRowSink sink = new CsvRowSink(csvDir)) {
            result = new DatasetGenerator().generate(settings, sink);
        }
        Map<String, Long> rows = new TreeMap<>();
        Map<String, Integer> columns = new TreeMap<>();
        for (TableSpec table : TableRegistry.loadOrder()) {
            Long n = result.rowCounts().get(table.name());
            if (n != null) {
                String path = CsvDatasetReader.CSV_DIR + "/" + table.fileName();
                rows.put(path, n);
                columns.put(path, table.columns().size());
            }
        }
        String fingerprint = ManifestWriter.write(outDir, runInfo(settings), rows, columns);
        return new Output(outDir, result, fingerprint);
    }

    static ManifestWriter.RunInfo runInfo(GenerationSettings s) {
        ScaleProfile p = s.profile();
        PricingPolicy policy = s.policy();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("generator", "{\"name\": \"pricing-promo-simulator\", \"version\": " + JsonText.quote(ManifestWriter.version())
                + ", \"git_commit\": " + JsonText.quote(ManifestWriter.gitCommit()) + "}");
        h.put("command_line", JsonText.quote(String.join(" ", s.commandLine())));
        h.put("seed", Long.toString(s.seed()));
        h.put("scale", "{\"name\": " + JsonText.quote(s.scaleName()) + ", \"skus\": " + p.skus() + ", \"days\": "
                + p.days() + ", \"promotions\": " + p.promotions() + ", \"start_date\": "
                + JsonText.quote(p.startDate().toString()) + ", \"end_date\": " + JsonText.quote(p.endDate().toString())
                + ", \"anomalies_per_type\": " + p.anomaliesPerType() + ", \"competitors\": " + p.competitors() + "}");
        h.put("rule_version", JsonText.quote(PricingPolicy.RULE_VERSION));
        h.put("reference_ts", JsonText.quote(s.referenceTs().toString()));
        h.put("pricing_policy", "{\"freshness_hours\": " + policy.freshnessHours()
                + ", \"min_match_confidence\": " + JsonText.number(policy.minMatchConfidence())
                + ", \"max_step_change_pct\": " + JsonText.number(policy.maxStepChangePct())
                + ", \"auto_approve_max_change_pct\": " + JsonText.number(policy.autoApproveMaxChangePct())
                + ", \"overstock_days\": " + JsonText.number(policy.overstockDays())
                + ", \"low_cover_days\": " + JsonText.number(policy.lowCoverDays())
                + ", \"high_velocity_ratio\": " + JsonText.number(policy.highVelocityRatio())
                + ", \"default_max_stack\": " + policy.defaultMaxStack()
                + ", \"default_max_combined_discount\": " + JsonText.number(policy.defaultMaxCombinedDiscount()) + "}");
        return new ManifestWriter.RunInfo(h);
    }

    /** Deletes the regular files directly inside {@code dir} (a generated folder); keeps the folder. */
    static void clear(Path dir) {
        for (Path f : ChecksumService.filesUnder(dir)) {
            try {
                Files.delete(f);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot delete " + f, e);
            }
        }
    }
}
