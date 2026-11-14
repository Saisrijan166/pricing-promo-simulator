package com.pricingsim.generator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.pricingsim.config.ScaleProfile;
import com.pricingsim.pricing.PricingPolicy;

/**
 * Everything that determines a generated dataset. Two runs with equal settings produce byte-identical files.
 *
 * @param seed        global seed for {@link com.pricingsim.generator.rng.RandomStreams}
 * @param scaleName   name of the base profile ({@code tiny}, {@code starter}, {@code full})
 * @param profile     the profile after CLI overrides
 * @param referenceTs fixed "as of" timestamp recorded with the run (never the system clock)
 * @param policy      pricing policy used by every decision
 * @param commandLine the command-line arguments, recorded for the manifest (they do not affect the data)
 */
public record GenerationSettings(
        long seed,
        String scaleName,
        ScaleProfile profile,
        Instant referenceTs,
        PricingPolicy policy,
        List<String> commandLine) {

    public GenerationSettings {
        Objects.requireNonNull(scaleName, "scaleName");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(referenceTs, "referenceTs");
        Objects.requireNonNull(policy, "policy");
        commandLine = commandLine == null ? List.of() : List.copyOf(commandLine);
    }

    public LocalDate windowStart() {
        return profile.startDate();
    }

    public LocalDate windowEnd() {
        return profile.endDate();
    }
}
