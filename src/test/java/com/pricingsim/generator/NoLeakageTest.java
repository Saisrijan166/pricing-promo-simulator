package com.pricingsim.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pricingsim.generator.anomaly.GroundTruthFiles;
import com.pricingsim.generator.anomaly.GroundTruthRecord;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.io.TableSpec;

/** Labels never leak into features or into the detection and validation code (PLAN.md §9 leakage controls, §12). */
class NoLeakageTest {

    @TempDir
    static Path tmp;
    private static Path out;

    private static final List<String> LABEL_WORDS = List.of("anomaly", "issue_type", "gt_id", "label", "ground_truth",
            "injected", "is_anomalous", "violated_rule", "expected_action");

    @BeforeAll
    static void generate() {
        out = tmp.resolve("tiny");
        DatasetWriter.write(TestSettings.forScale("tiny"), out);
    }

    @Test
    void labelsAreSeparateFromFeatures() throws IOException {
        try (Stream<Path> csv = Files.list(out.resolve("csv"))) {
            assertThat(csv.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrderElementsOf(TableRegistry.loadOrder().stream().map(TableSpec::fileName).toList());
        }
        try (Stream<Path> labels = Files.list(out.resolve("labels"))) {
            assertThat(labels.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder(GroundTruthFiles.CSV_FILE, GroundTruthFiles.JSON_FILE);
        }
        assertThat(GroundTruthFiles.read(out.resolve("labels").resolve(GroundTruthFiles.CSV_FILE))).hasSize(5);
    }

    @Test
    void noFeatureHeaderContainsALabelColumn() throws IOException {
        for (TableSpec table : TableRegistry.loadOrder()) {
            String header = Files.readAllLines(out.resolve("csv").resolve(table.fileName())).get(0);
            for (String column : header.split(",")) {
                for (String word : LABEL_WORDS) {
                    assertThat(column).as(table.name()).doesNotContain(word);
                }
            }
        }
    }

    @Test
    void groundTruthIdsAppearOnlyInLabels() throws IOException {
        List<GroundTruthRecord> labels = GroundTruthFiles.read(out.resolve("labels").resolve(GroundTruthFiles.CSV_FILE));
        for (TableSpec table : TableRegistry.loadOrder()) {
            String text = Files.readString(out.resolve("csv").resolve(table.fileName()), StandardCharsets.UTF_8);
            assertThat(text).doesNotContain("GT-");
            for (GroundTruthRecord l : labels) {
                assertThat(text).doesNotContain(l.gtId()).doesNotContain(l.violatedRule());
            }
        }
    }

    @Test
    void detectionAndValidationNeverTouchAnomalyCodeOrLabels() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : List.of("detection", "validation")) {
            Path root = Path.of("src/main/java/com/pricingsim").resolve(pkg);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    String lower = text.toLowerCase(Locale.ROOT);
                    if (text.contains("generator.anomaly") || lower.contains("labels/") || lower.contains("ground_truth")
                            || lower.contains("groundtruth")) {
                        violations.add(f.toString());
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void registryHasNoLabelTable() {
        assertThat(TableRegistry.loadOrder()).extracting(TableSpec::name)
                .noneMatch(n -> LABEL_WORDS.stream().anyMatch(n::contains));
    }
}
