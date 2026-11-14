package com.pricingsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pricingsim.detection.ExceptionDetector;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.evaluation.EvaluationReportWriter;
import com.pricingsim.evaluation.EvaluationRunner;
import com.pricingsim.evaluation.GroundTruthEvaluator;
import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.generator.TestSettings;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.persistence.BulkLoader;
import com.pricingsim.pricing.PricingPolicy;
import com.pricingsim.validation.ValidationRunner;

/**
 * Generate → load → validate → detect → evaluate on H2 (PLAN.md §12): no structural failures, and the detector finds
 * every labelled anomaly and nothing else (precision = recall = 1.0 per type). A clean run has no findings at all.
 */
class EndToEndPipelineIT {

    @TempDir
    Path tmp;

    @Test
    void starterPipelineDetectsExactlyTheLabelledAnomalies() throws IOException {
        Path dir = tmp.resolve("starter");
        DatasetWriter.write(TestSettings.forScale("starter"), dir);
        DataSource ds = TestDatabases.fresh("e2e");
        new BulkLoader(ds).load(dir);
        ManifestWriter.Summary manifest = ManifestWriter.read(dir);

        ValidationRunner.Report validation = new ValidationRunner(ds, PricingPolicy.defaults()).run(manifest, "x");
        assertThat(validation.failed()).as("structural failures").isZero();
        assertThat(validation.businessRuleFindings().values()).containsOnly(5L).hasSize(5);

        ExceptionDetector.Result detected = new ExceptionDetector(ds, PricingPolicy.defaults()).run(manifest, dir);
        assertThat(Files.readAllLines(dir.resolve(ExceptionDetector.CSV_FILE))).hasSize(detected.exceptions().size() + 1);
        assertThat(detected.counts().get(IssueType.STOCKOUT)).isPositive();
        assertThat(detected.counts().get(IssueType.COUPON_REJECTED)).isPositive();
        assertThat(detected.counts().get(IssueType.COMPETITOR_DATA_STALE)).isPositive();
        assertThat(detected.counts().get(IssueType.GUARDRAIL_CONFLICT_ESCALATED)).isPositive();

        EvaluationReportWriter.Report report = new EvaluationRunner(ds).run(dir);
        for (IssueType type : GroundTruthEvaluator.ANOMALY_TYPES) {
            GroundTruthEvaluator.Score s = report.evaluation().perType().get(type);
            assertThat(s.labelled()).as(type.name()).isEqualTo(5);
            assertThat(s.precision()).as(type + " precision").isEqualTo(1.0);
            assertThat(s.recall()).as(type + " recall").isEqualTo(1.0);
        }
        assertThat(report.evaluation().missed()).isEmpty();
        assertThat(report.evaluation().unexpected()).isEmpty();
        assertThat(Files.readString(dir.resolve(EvaluationReportWriter.FILE)))
                .contains("\"PRICE_BELOW_FLOOR\": {\"labelled\": 5, \"detected\": 5, \"tp\": 5, \"fp\": 0, \"fn\": 0,"
                        + " \"precision\": 1.0000, \"recall\": 1.0000");
    }

    @Test
    void cleanDatasetHasNoBusinessRuleFindings() {
        Path dir = tmp.resolve("clean");
        DatasetWriter.write(TestSettings.clean("tiny"), dir);
        DataSource ds = TestDatabases.fresh("e2e-clean");
        new BulkLoader(ds).load(dir);
        ManifestWriter.Summary manifest = ManifestWriter.read(dir);
        ValidationRunner.Report validation = new ValidationRunner(ds, PricingPolicy.defaults()).run(manifest, "x");
        assertThat(validation.failed()).isZero();
        assertThat(validation.businessRuleFindings().values()).containsOnly(0L);
        ExceptionDetector.Result detected = new ExceptionDetector(ds, PricingPolicy.defaults()).run(manifest, dir);
        assertThat(detected.exceptions()).noneMatch(e -> e.issueType().isAnomaly());
        EvaluationReportWriter.Report report = new EvaluationRunner(ds).run(dir);
        assertThat(report.evaluation().micro().labelled()).isZero();
        assertThat(report.evaluation().micro().precision()).isEqualTo(1.0);
    }
}
