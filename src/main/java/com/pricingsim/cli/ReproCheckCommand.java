package com.pricingsim.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.config.PricingPolicyProperties;
import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.generator.GenerationSettings;
import com.pricingsim.io.ChecksumService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code repro-check} (PLAN.md §11): generates the dataset twice in-process with the same settings into
 * {@code <out>/run-a} and {@code <out>/run-b} and compares every checksum (expected IDENTICAL), then once with
 * seed + 1 into {@code <out>/run-c} (expected DIFFERENT fingerprint). Exits 1 if either expectation fails.
 * {@code scripts/repro_check.sh} does the same across separate JVM runs and writes the evidence report.
 */
@Component
@Command(name = "repro-check", mixinStandardHelpOptions = true,
        description = "Generate twice with the same seed (IDENTICAL) and once with another seed (DIFFERENT).")
public class ReproCheckCommand implements Callable<Integer> {

    @Mixin
    ScaleOptions scaleOptions = new ScaleOptions();

    @Option(names = "--report", paramLabel = "<file>", description = "Also write a Markdown report to this file.")
    Path report;

    @Spec
    CommandSpec spec;

    private final GeneratorProperties properties;
    private final PricingPolicyProperties policy;

    public ReproCheckCommand(GeneratorProperties properties, PricingPolicyProperties policy) {
        this.properties = properties;
        this.policy = policy;
    }

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        Path base = scaleOptions.out != null ? scaleOptions.out : Path.of("out", "repro");
        List<String> cmd = new ArrayList<>(spec.commandLine().getParseResult().originalArgs());
        GenerationSettings settings = scaleOptions.toSettings(properties, policy.toPolicy(), cmd);
        GenerationSettings other = new GenerationSettings(settings.seed() + 1, settings.scaleName(), settings.profile(),
                settings.referenceTs(), settings.policy(), cmd);

        DatasetWriter.Output a = DatasetWriter.write(settings, base.resolve("run-a"));
        DatasetWriter.Output b = DatasetWriter.write(settings, base.resolve("run-b"));
        DatasetWriter.Output c = DatasetWriter.write(other, base.resolve("run-c"));
        Map<String, String> sa = ChecksumService.readChecksumsFile(a.directory().resolve(ChecksumService.CHECKSUMS_FILE));
        Map<String, String> sb = ChecksumService.readChecksumsFile(b.directory().resolve(ChecksumService.CHECKSUMS_FILE));
        boolean identical = sa.equals(sb) && a.fingerprint().equals(b.fingerprint());
        boolean different = !c.fingerprint().equals(a.fingerprint());

        out.printf("Same seed %d, run-a vs run-b: %s (%d files, fingerprint %s)%n", settings.seed(),
                identical ? "IDENTICAL" : "DIFFERENT", sa.size(), a.fingerprint());
        out.printf("Seed %d vs %d: %s (fingerprint %s)%n", settings.seed(), other.seed(),
                different ? "DIFFERENT" : "IDENTICAL", c.fingerprint());
        if (report != null) {
            writeReport(report, settings, sa, sb, a.fingerprint(), b.fingerprint(), c.fingerprint(), identical,
                    different, other.seed());
            out.printf("Wrote %s%n", report);
        }
        out.flush();
        return identical && different ? 0 : 1;
    }

    private static void writeReport(Path file, GenerationSettings s, Map<String, String> a, Map<String, String> b,
                                    String fa, String fb, String fc, boolean identical, boolean different,
                                    long otherSeed) throws IOException {
        StringBuilder sb = new StringBuilder("# Reproducibility report (in-process)\n\n");
        sb.append("Scale `").append(s.scaleName()).append("`, seed ").append(s.seed()).append(".\n\n");
        sb.append("| File | run-a sha256 | run-b sha256 | Match |\n|---|---|---|---|\n");
        for (String path : new TreeSet<>(a.keySet())) {
            sb.append("| ").append(path).append(" | `").append(a.get(path)).append("` | `").append(b.get(path))
                    .append("` | ").append(a.get(path).equals(b.get(path)) ? "yes" : "**no**").append(" |\n");
        }
        sb.append("\nFingerprint run-a `").append(fa).append("`, run-b `").append(fb).append("`.\n\n");
        sb.append("**Verdict: ").append(identical ? "IDENTICAL" : "DIFFERENT").append("**\n\n");
        sb.append("Different seed (").append(otherSeed).append("): fingerprint `").append(fc).append("` → **")
                .append(different ? "DIFFERENT" : "IDENTICAL").append("** (expected DIFFERENT).\n");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
