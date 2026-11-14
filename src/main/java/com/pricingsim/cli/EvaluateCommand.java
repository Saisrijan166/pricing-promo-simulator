package com.pricingsim.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.evaluation.DatasetMetricsCalculator;
import com.pricingsim.evaluation.EvaluationReportWriter;
import com.pricingsim.evaluation.EvaluationRunner;
import com.pricingsim.evaluation.GroundTruthEvaluator;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code evaluate}: detector findings vs ground truth, realism bands; writes evaluation_metrics.json. */
@Component
@Command(name = "evaluate", mixinStandardHelpOptions = true,
        description = "Compare detected anomalies with labels/ground_truth.csv and compute realism metrics.")
public class EvaluateCommand implements Callable<Integer> {

    @Mixin
    ScaleOptions scaleOptions = new ScaleOptions();

    @Spec
    CommandSpec spec;

    private final GeneratorProperties properties;
    private final ObjectProvider<DataSource> dataSource;

    public EvaluateCommand(GeneratorProperties properties, ObjectProvider<DataSource> dataSource) {
        this.properties = properties;
        this.dataSource = dataSource;
    }

    @Override
    public Integer call() {
        evaluate(Database.require(dataSource), scaleOptions.outputDirectory(properties), spec.commandLine().getOut());
        return 0;
    }

    static EvaluationReportWriter.Report evaluate(DataSource ds, Path dir, PrintWriter out) {
        EvaluationReportWriter.Report r = new EvaluationRunner(ds).run(dir);
        Locale l = Locale.ROOT;
        out.printf(l, "%-30s %9s %9s %4s %4s %4s %10s %8s%n", "issue_type", "labelled", "detected", "TP", "FP", "FN",
                "precision", "recall");
        for (Map.Entry<IssueType, GroundTruthEvaluator.Score> e : r.evaluation().perType().entrySet()) {
            GroundTruthEvaluator.Score s = e.getValue();
            out.printf(l, "%-30s %9d %9d %4d %4d %4d %10.4f %8.4f%n", e.getKey(), s.labelled(), s.detected(),
                    s.truePositives(), s.falsePositives(), s.falseNegatives(), s.precision(), s.recall());
        }
        out.println();
        for (DatasetMetricsCalculator.Metric m : r.realism()) {
            out.printf(l, "%-34s %8.4f  band [%.2f, %.2f]  %s%n", m.name(), m.value(), m.bandLow(), m.bandHigh(),
                    m.status());
        }
        out.printf("Wrote %s%n", dir.resolve(EvaluationReportWriter.FILE));
        out.flush();
        return r;
    }
}
