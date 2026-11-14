package com.pricingsim.config;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Generator settings bound from {@code generator.*} in {@code application.yml} (PLAN.md §7.1).
 *
 * @param seed         global seed; every random stream derives from it (default 20261022)
 * @param defaultScale profile used when {@code --scale} is not given
 * @param referenceTs  fixed "as of" timestamp recorded with each run; the generator never reads the clock
 * @param scales       named scale profiles (tiny, starter, full)
 */
@ConfigurationProperties("generator")
public record GeneratorProperties(
        @DefaultValue("20261022") long seed,
        @DefaultValue("starter") String defaultScale,
        @DefaultValue("2026-07-01T00:00:00Z") Instant referenceTs,
        Map<String, ScaleProfile> scales) {

    public static final long DEFAULT_SEED = 20261022L;

    public GeneratorProperties {
        Objects.requireNonNull(defaultScale, "defaultScale");
        Objects.requireNonNull(referenceTs, "referenceTs");
        // Sorted copy: profile lookup never depends on map iteration order.
        scales = scales == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(scales));
    }

    /** The named profile, or an error listing the known names. */
    public ScaleProfile scale(String name) {
        ScaleProfile profile = scales.get(name);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown scale '" + name + "'. Known scales: " + scales.keySet());
        }
        return profile;
    }
}
