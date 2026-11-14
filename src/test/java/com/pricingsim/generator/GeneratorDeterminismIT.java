package com.pricingsim.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pricingsim.io.ChecksumService;

/**
 * The reproducibility proof in CI (PLAN.md §11): starter generated twice with the same seed gives identical checksums
 * for every data and label file, and a different seed gives a different dataset fingerprint.
 */
class GeneratorDeterminismIT {

    @TempDir
    Path tmp;

    @Test
    void starterIsReproducibleAndSeedSensitive() {
        DatasetWriter.Output a = DatasetWriter.write(TestSettings.forScale("starter"), tmp.resolve("run-a"));
        DatasetWriter.Output b = DatasetWriter.write(TestSettings.forScale("starter"), tmp.resolve("run-b"));
        DatasetWriter.Output c = DatasetWriter.write(TestSettings.forScale("starter", 20261023L), tmp.resolve("run-c"));

        var sumsA = ChecksumService.readChecksumsFile(a.directory().resolve(ChecksumService.CHECKSUMS_FILE));
        var sumsB = ChecksumService.readChecksumsFile(b.directory().resolve(ChecksumService.CHECKSUMS_FILE));
        assertThat(sumsA).hasSize(18).isEqualTo(sumsB); // 16 tables + 2 label files
        assertThat(sumsA).containsKeys("labels/ground_truth.csv", "labels/ground_truth.json", "csv/price_decision.csv");
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(c.fingerprint()).isNotEqualTo(a.fingerprint());
    }
}
