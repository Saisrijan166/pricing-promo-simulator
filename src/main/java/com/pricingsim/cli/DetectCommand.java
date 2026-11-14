package com.pricingsim.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.config.PricingPolicyProperties;
import com.pricingsim.detection.ExceptionDetector;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.io.ManifestWriter;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code detect}: runs the exception detector on the loaded data; writes detected_exception and its CSV. */
@Component
@Command(name = "detect", mixinStandardHelpOptions = true,
        description = "Detect anomalies and operational exceptions in the loaded data (no labels used).")
public class DetectCommand implements Callable<Integer> {

    @Mixin
    ScaleOptions scaleOptions = new ScaleOptions();

    @Spec
    CommandSpec spec;

    private final GeneratorProperties properties;
    private final PricingPolicyProperties policy;
    private final ObjectProvider<DataSource> dataSource;

    public DetectCommand(GeneratorProperties properties, PricingPolicyProperties policy,
                         ObjectProvider<DataSource> dataSource) {
        this.properties = properties;
        this.policy = policy;
        this.dataSource = dataSource;
    }

    @Override
    public Integer call() {
        detect(Database.require(dataSource), policy, scaleOptions.outputDirectory(properties),
                spec.commandLine().getOut());
        return 0;
    }

    static ExceptionDetector.Result detect(DataSource ds, PricingPolicyProperties policy, Path dir, PrintWriter out) {
        ExceptionDetector.Result r = new ExceptionDetector(ds, policy.toPolicy()).run(ManifestWriter.read(dir), dir);
        out.printf("%-30s %-12s %10s%n", "issue_type", "category", "findings");
        for (Map.Entry<IssueType, Long> e : r.counts().entrySet()) {
            out.printf("%-30s %-12s %10d%n", e.getKey(), e.getKey().category(), e.getValue());
        }
        out.printf("Detected %d exceptions (run %s); wrote %s%n", r.exceptions().size(), r.runId(),
                dir.resolve(ExceptionDetector.CSV_FILE));
        out.flush();
        return r;
    }
}
