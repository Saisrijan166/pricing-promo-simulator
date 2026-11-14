package com.pricingsim.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.persistence.BulkLoader;
import com.pricingsim.persistence.RunDao;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code load}: replaces the database contents with the dataset in {@code --out} (PLAN.md §3). Flyway has already
 * migrated the schema on startup. Row counts are checked against {@code manifest.json}.
 */
@Component
@Command(name = "load", mixinStandardHelpOptions = true,
        description = "Load a generated dataset (csv/) into PostgreSQL; labels/ are never loaded.")
public class LoadCommand implements Callable<Integer> {

    @Mixin
    ScaleOptions scaleOptions = new ScaleOptions();

    @Spec
    CommandSpec spec;

    private final GeneratorProperties properties;
    private final ObjectProvider<DataSource> dataSource;

    public LoadCommand(GeneratorProperties properties, ObjectProvider<DataSource> dataSource) {
        this.properties = properties;
        this.dataSource = dataSource;
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        Path dir = scaleOptions.outputDirectory(properties);
        DataSource ds = Database.require(dataSource);
        long started = System.nanoTime();
        Map<String, Long> counts = load(ds, dir, out);
        out.printf("Loaded %d rows from %s in %d ms%n", counts.values().stream().mapToLong(Long::longValue).sum(), dir,
                (System.nanoTime() - started) / 1_000_000);
        out.flush();
        return 0;
    }

    /** Loads, checks counts against the manifest and records a LOAD run; shared with {@code run-all}. */
    static Map<String, Long> load(DataSource ds, Path dir, PrintWriter out) {
        ManifestWriter.Summary manifest = ManifestWriter.read(dir);
        Map<String, Long> counts = new BulkLoader(ds).load(dir);
        StringBuilder summary = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            Long expected = manifest.rowsByPath().get("csv/" + e.getKey() + ".csv");
            if (expected == null || expected.longValue() != e.getValue()) {
                throw new IllegalStateException(e.getKey() + ": loaded " + e.getValue() + " rows, manifest says "
                        + expected);
            }
            out.printf("  %-28s %10d%n", e.getKey(), e.getValue());
            summary.append(i++ == 0 ? "" : ", ").append('"').append(e.getKey()).append("\": ").append(e.getValue());
        }
        RunDao runs = new RunDao(new JdbcTemplate(ds));
        String runId = runs.start(RunDao.Command.LOAD, manifest.seed(), manifest.scale(), manifest.fingerprint(),
                dir.toString());
        runs.finish(runId, true, summary.append('}').toString());
        return counts;
    }
}
