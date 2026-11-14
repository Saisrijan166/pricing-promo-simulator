package com.pricingsim.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.config.PricingPolicyProperties;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.validation.CheckResult;
import com.pricingsim.validation.ValidationRunner;
import com.pricingsim.validation.ValidationSummaryWriter;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code validate}: runs every PLAN.md §10 check against the loaded database and writes
 * {@code validation_summary.json/.md} next to the dataset. Exits 1 if any structural check fails; business-rule
 * findings are warnings.
 */
@Component
@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Validate the loaded dataset; exit 1 on any structural failure.")
public class ValidateCommand implements Callable<Integer> {

    @Mixin
    ScaleOptions scaleOptions = new ScaleOptions();

    @Spec
    CommandSpec spec;

    private final GeneratorProperties properties;
    private final PricingPolicyProperties policy;
    private final ObjectProvider<DataSource> dataSource;

    public ValidateCommand(GeneratorProperties properties, PricingPolicyProperties policy,
                           ObjectProvider<DataSource> dataSource) {
        this.properties = properties;
        this.policy = policy;
        this.dataSource = dataSource;
    }

    @Override
    public Integer call() throws IOException {
        Path dir = scaleOptions.outputDirectory(properties);
        ValidationRunner.Report report = validate(Database.require(dataSource), policy, dir,
                spec.commandLine().getOut());
        return report.structurallyValid() ? 0 : 1;
    }

    static ValidationRunner.Report validate(DataSource ds, PricingPolicyProperties policy, Path dir, PrintWriter out)
            throws IOException {
        Instant started = Instant.now();
        ValidationRunner.Report report = new ValidationRunner(ds, policy.toPolicy())
                .run(ManifestWriter.read(dir), dir.toString());
        ValidationSummaryWriter.write(dir, report);
        Files.writeString(dir.resolve("run_log.json"), "{\"command\": \"validate\", \"run_id\": \"" + report.runId()
                + "\", \"executed_at\": \"" + started + "\", \"duration_ms\": "
                + (Instant.now().toEpochMilli() - started.toEpochMilli()) + "}\n", StandardCharsets.UTF_8);
        for (CheckResult c : report.checks()) {
            if (c.status() != CheckResult.Status.PASS) {
                out.printf("  %-7s %-4s %-26s %8d  %s%n", c.checkId(), c.status(), c.table(), c.violations(),
                        c.description());
            }
        }
        out.printf("Validation: %d checks, %d passed, %d failed, %d warnings; business-rule findings %s%n",
                report.checks().size(), report.passed(), report.failed(), report.warnings(),
                report.businessRuleFindings());
        out.printf("Wrote %s and %s%n", dir.resolve(ValidationSummaryWriter.JSON_FILE),
                dir.resolve(ValidationSummaryWriter.MARKDOWN_FILE));
        out.flush();
        return report;
    }
}
