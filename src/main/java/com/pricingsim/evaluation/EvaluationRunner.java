package com.pricingsim.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.ExceptionDetector;
import com.pricingsim.domain.enums.ExceptionCategory;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.generator.anomaly.GroundTruthFiles;
import com.pricingsim.generator.anomaly.GroundTruthRecord;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.persistence.RunDao;

/**
 * {@code evaluate} (PLAN.md §10): compares the latest detector findings with {@code labels/ground_truth.csv} and adds
 * realism and descriptive metrics. This is the only place that reads the labels.
 */
public final class EvaluationRunner {

    private final JdbcTemplate jdbc;

    public EvaluationRunner(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    public EvaluationReportWriter.Report run(Path datasetDir) {
        ManifestWriter.Summary manifest = ManifestWriter.read(datasetDir);
        Path labels = datasetDir.resolve(DatasetWriter.LABELS_DIR).resolve(GroundTruthFiles.CSV_FILE);
        if (!Files.isRegularFile(labels)) {
            throw new IllegalArgumentException("Missing " + labels + " (run generate first)");
        }
        RunDao runs = new RunDao(jdbc);
        String runId = runs.start(RunDao.Command.EVALUATE, manifest.seed(), manifest.scale(), manifest.fingerprint(),
                datasetDir.toString());
        List<String> labelled = GroundTruthFiles.read(labels).stream()
                .map(EvaluationRunner::key).toList();
        List<String> detected = ExceptionDetector.anomalyKeys(jdbc).stream()
                .map(k -> k[0] + "|" + k[1] + "|" + k[2]).toList();
        GroundTruthEvaluator.Evaluation evaluation = GroundTruthEvaluator.evaluate(labelled, detected);

        Map<IssueType, Long> operational = new EnumMap<>(IssueType.class);
        for (IssueType t : IssueType.values()) {
            if (t.category() == ExceptionCategory.OPERATIONAL) {
                operational.put(t, jdbc.queryForObject("SELECT COUNT(*) FROM detected_exception WHERE issue_type = ?",
                        Long.class, t.name()));
            }
        }
        EvaluationReportWriter.Report report = new EvaluationReportWriter.Report(manifest.seed(), manifest.scale(),
                manifest.fingerprint(), evaluation, DatasetMetricsCalculator.realism(jdbc), operational,
                DatasetMetricsCalculator.decisionMix(jdbc), DatasetMetricsCalculator.byCategory(jdbc));
        EvaluationReportWriter.write(datasetDir.resolve(EvaluationReportWriter.FILE), report);
        runs.finish(runId, true, "{\"micro_precision\": " + evaluation.micro().precision() + ", \"micro_recall\": "
                + evaluation.micro().recall() + "}");
        return report;
    }

    private static String key(GroundTruthRecord r) {
        return r.issueType().name() + "|" + r.entityType() + "|" + r.entityId();
    }
}
