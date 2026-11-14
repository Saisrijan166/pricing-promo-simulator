package com.pricingsim.cli;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.config.ScaleProfile;
import com.pricingsim.generator.GenerationSettings;
import com.pricingsim.pricing.PricingPolicy;

import picocli.CommandLine.Option;

/**
 * Shared scale flags (PLAN.md §3 cli/ScaleOptions). A flag that is not given falls back to the named profile in
 * {@code application.yml}, then to {@code generator.*} defaults. {@code --anomalies-per-type} is only used by anomaly
 * injection (a later pipeline step); it is accepted now and recorded in the run settings.
 */
public class ScaleOptions {

    @Option(names = "--scale", paramLabel = "<name>",
            description = "Scale profile: tiny, starter or full (default: generator.default-scale, starter).")
    String scale;

    @Option(names = "--seed", paramLabel = "<long>", description = "Global random seed (default: 20261022).")
    Long seed;

    @Option(names = "--skus", paramLabel = "<n>", description = "Override the profile's number of SKUs.")
    Integer skus;

    @Option(names = "--days", paramLabel = "<n>", description = "Override the profile's window length in days.")
    Integer days;

    @Option(names = "--promos", paramLabel = "<n>", description = "Override the profile's number of promotions.")
    Integer promos;

    @Option(names = "--start-date", paramLabel = "<yyyy-mm-dd>",
            description = "Override the first day of the simulation window.")
    LocalDate startDate;

    @Option(names = "--anomalies-per-type", paramLabel = "<n>",
            description = "Override the number of labelled anomalies per issue type.")
    Integer anomaliesPerType;

    @Option(names = "--out", paramLabel = "<dir>", description = "Output directory (default: out/<scale>).")
    Path out;

    /** Name of the base profile. */
    public String scaleName(GeneratorProperties properties) {
        return scale != null ? scale : properties.defaultScale();
    }

    /** Settings for the generator: the named profile with every given flag applied. */
    public GenerationSettings toSettings(GeneratorProperties properties, PricingPolicy policy,
                                         List<String> commandLine) {
        String name = scaleName(properties);
        ScaleProfile profile = properties.scale(name)
                .withOverrides(skus, days, promos, startDate, anomaliesPerType);
        long effectiveSeed = seed != null ? seed : properties.seed();
        return new GenerationSettings(effectiveSeed, name, profile, properties.referenceTs(), policy, commandLine);
    }

    /** The output directory: {@code --out}, or {@code out/<scale>}. */
    public Path outputDirectory(GeneratorProperties properties) {
        return out != null ? out : Path.of("out", scaleName(properties));
    }
}
