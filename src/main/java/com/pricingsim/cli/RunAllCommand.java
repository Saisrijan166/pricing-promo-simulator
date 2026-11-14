package com.pricingsim.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.config.PricingPolicyProperties;
import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.validation.ValidationRunner;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code run-all}: generate → load → validate → detect → evaluate → export in one JVM (PLAN.md §3). */
@Component
@Command(name = "run-all", mixinStandardHelpOptions = true,
        description = "Full pipeline in one command: generate, load, validate, detect, evaluate, export.")
public class RunAllCommand implements Callable<Integer> {

    @Mixin
    ScaleOptions scaleOptions = new ScaleOptions();

    @Option(names = "--formats", split = ",", defaultValue = "json,sql", paramLabel = "<json|sql>",
            description = "Export formats (default: json,sql).")
    List<String> formats;

    @Spec
    CommandSpec spec;

    private final GeneratorProperties properties;
    private final PricingPolicyProperties policy;
    private final ObjectProvider<DataSource> dataSource;

    public RunAllCommand(GeneratorProperties properties, PricingPolicyProperties policy,
                         ObjectProvider<DataSource> dataSource) {
        this.properties = properties;
        this.policy = policy;
        this.dataSource = dataSource;
    }

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        DataSource ds = Database.require(dataSource);
        Path dir = scaleOptions.outputDirectory(properties);
        List<String> cmd = new ArrayList<>(spec.commandLine().getParseResult().originalArgs());

        long t = stage(out, "generate", 0);
        DatasetWriter.Output generated = DatasetWriter.write(scaleOptions.toSettings(properties, policy.toPolicy(), cmd),
                dir);
        out.printf("Dataset fingerprint %s%n", generated.fingerprint());
        t = stage(out, "load", t);
        LoadCommand.load(ds, dir, out);
        t = stage(out, "validate", t);
        ValidationRunner.Report validation = ValidateCommand.validate(ds, policy, dir, out);
        t = stage(out, "detect", t);
        DetectCommand.detect(ds, policy, dir, out);
        t = stage(out, "evaluate", t);
        EvaluateCommand.evaluate(ds, dir, out);
        t = stage(out, "export", t);
        ExportCommand.export(dir, formats, out);
        stage(out, "done", t);
        return validation.structurallyValid() ? 0 : 1;
    }

    private static long stage(PrintWriter out, String name, long previous) {
        long now = System.nanoTime();
        if (previous != 0) {
            out.printf("   (%d ms)%n", (now - previous) / 1_000_000);
        }
        if (!name.equals("done")) {
            out.printf("%n==> %s%n", name);
        }
        out.flush();
        return now;
    }
}
