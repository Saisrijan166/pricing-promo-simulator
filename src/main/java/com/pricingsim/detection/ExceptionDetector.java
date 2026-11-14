package com.pricingsim.detection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.detection.rules.BusinessRules;
import com.pricingsim.detection.rules.OperationalExceptionCollector;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.domain.id.IdFactory;
import com.pricingsim.io.CsvSupport;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.persistence.RunDao;
import com.pricingsim.pricing.PricingPolicy;

/**
 * Runs the five rule detectors and the operational collector over the loaded data and writes the findings to
 * {@code detected_exception} and {@code detected_exceptions.csv} (PLAN.md §10). It works only from the data: it never
 * reads labels or the anomaly code.
 */
public final class ExceptionDetector {

    public static final String CSV_FILE = "detected_exceptions.csv";
    public static final List<String> COLUMNS = List.of("exception_id", "run_id", "category", "issue_type",
            "entity_type", "entity_id", "sku_id", "event_date", "related_ids", "violated_rule", "observed_value",
            "expected_value", "recommended_action", "detail");

    private final JdbcTemplate jdbc;
    private final PricingPolicy policy;

    public ExceptionDetector(DataSource dataSource, PricingPolicy policy) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** The findings with IDs, and counts per issue type. */
    public record Result(String runId, List<DetectedException> exceptions, Map<IssueType, Long> counts) {
    }

    /** All findings, sorted by (issue_type, entity_id), without IDs. */
    public List<DetectedException> findAll() {
        List<DetectedException> all = new ArrayList<>();
        for (DetectionRule rule : BusinessRules.all()) {
            all.addAll(rule.detect(jdbc, policy));
        }
        all.addAll(OperationalExceptionCollector.collect(jdbc));
        all.sort(Comparator.comparing(DetectedException::issueType).thenComparing(DetectedException::entityId));
        return all;
    }

    /** Detects, numbers ({@code EXC-YYYY-%07d} per year of the event date) and writes the findings. */
    public Result run(ManifestWriter.Summary manifest, Path outDir) {
        RunDao runs = new RunDao(jdbc);
        String runId = runs.start(RunDao.Command.DETECT, manifest.seed(), manifest.scale(), manifest.fingerprint(),
                outDir.toString());
        List<DetectedException> numbered = new ArrayList<>();
        Map<Integer, Long> counters = new TreeMap<>();
        Map<IssueType, Long> counts = new EnumMap<>(IssueType.class);
        for (IssueType t : IssueType.values()) {
            counts.put(t, 0L);
        }
        for (DetectedException e : findAll()) {
            int year = e.eventDate().getYear();
            long n = counters.merge(year, 1L, Long::sum);
            numbered.add(e.withIds(IdFactory.detectedException(year, n), runId));
            counts.merge(e.issueType(), 1L, Long::sum);
        }
        insert(numbered);
        writeCsv(outDir.resolve(CSV_FILE), numbered);
        StringBuilder summary = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<IssueType, Long> c : counts.entrySet()) {
            summary.append(i++ == 0 ? "" : ", ").append('"').append(c.getKey()).append("\": ").append(c.getValue());
        }
        runs.finish(runId, true, summary.append('}').toString());
        return new Result(runId, numbered, counts);
    }

    private void insert(List<DetectedException> rows) {
        String sql = "INSERT INTO detected_exception (" + String.join(", ", COLUMNS) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(COLUMNS.size(), "?")) + ")";
        for (int from = 0; from < rows.size(); from += 5_000) {
            List<DetectedException> batch = rows.subList(from, Math.min(rows.size(), from + 5_000));
            jdbc.batchUpdate(sql, batch, batch.size(), (ps, e) -> {
                String[] f = fields(e);
                for (int k = 0; k < f.length; k++) {
                    if (k == 7) {
                        ps.setObject(k + 1, e.eventDate());
                    } else {
                        ps.setString(k + 1, f[k]);
                    }
                }
            });
        }
    }

    static String[] fields(DetectedException e) {
        return new String[] {e.exceptionId(), e.runId(), e.category().name(), e.issueType().name(), e.entityType(),
                e.entityId(), e.skuId(), e.eventDate() == null ? null : e.eventDate().toString(),
                cut(e.relatedIds().isEmpty() ? null : String.join("|", e.relatedIds()), 1000), e.violatedRule(),
                cut(e.observedValue(), 64), cut(e.expectedValue(), 64), e.recommendedAction(), cut(e.detail(), 1000)};
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static void writeCsv(Path file, List<DetectedException> rows) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(CsvSupport.formatLine(COLUMNS.toArray(String[]::new)));
                w.write(CsvSupport.LINE_SEPARATOR);
                for (DetectedException e : rows) {
                    w.write(CsvSupport.formatLine(fields(e)));
                    w.write(CsvSupport.LINE_SEPARATOR);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** Anomaly findings of the latest DETECT run, as (issue_type, entity_type, entity_id). */
    public static List<String[]> anomalyKeys(JdbcTemplate jdbc) {
        return jdbc.query("SELECT issue_type, entity_type, entity_id FROM detected_exception WHERE category ="
                        + " 'ANOMALY' ORDER BY issue_type, entity_id",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
    }
}
