package com.pricingsim.generator.anomaly;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.io.CsvSupport;
import com.pricingsim.io.JsonText;

/**
 * {@code labels/ground_truth.csv} and {@code labels/ground_truth.json} (PLAN.md §9). The CSV uses the same rules as the
 * data files; {@code related_ids} is pipe-delimited. The JSON is {@code {seed, scale, counts_by_issue_type, records}}.
 * Only the generator writes these files and only {@code evaluate} reads them.
 */
public final class GroundTruthFiles {

    public static final String CSV_FILE = "ground_truth.csv";
    public static final String JSON_FILE = "ground_truth.json";

    private GroundTruthFiles() {
    }

    public static void write(Path labelsDir, List<GroundTruthRecord> records, long seed, String scale) {
        try {
            Files.createDirectories(labelsDir);
            try (Writer w = Files.newBufferedWriter(labelsDir.resolve(CSV_FILE), StandardCharsets.UTF_8)) {
                w.write(CsvSupport.formatLine(GroundTruthRecord.COLUMNS.toArray(String[]::new)));
                w.write(CsvSupport.LINE_SEPARATOR);
                for (GroundTruthRecord r : records) {
                    w.write(CsvSupport.formatLine(fields(r)));
                    w.write(CsvSupport.LINE_SEPARATOR);
                }
            }
            Map<IssueType, Integer> counts = new EnumMap<>(IssueType.class);
            for (IssueType t : AnomalyPlanner.TYPES) {
                counts.put(t, 0);
            }
            records.forEach(r -> counts.merge(r.issueType(), 1, Integer::sum));
            StringBuilder sb = new StringBuilder("{\n");
            sb.append("  \"seed\": ").append(seed).append(",\n");
            sb.append("  \"scale\": ").append(JsonText.quote(scale)).append(",\n");
            sb.append("  \"counts_by_issue_type\": {");
            int i = 0;
            for (Map.Entry<IssueType, Integer> e : counts.entrySet()) {
                sb.append(i++ == 0 ? "" : ", ").append(JsonText.quote(e.getKey().name())).append(": ").append(e.getValue());
            }
            sb.append("},\n  \"records\": [");
            for (int k = 0; k < records.size(); k++) {
                String[] f = fields(records.get(k));
                sb.append(k == 0 ? "\n" : ",\n").append("    {");
                for (int c = 0; c < f.length; c++) {
                    String name = GroundTruthRecord.COLUMNS.get(c);
                    String value;
                    if (name.equals("expected_detection")) {
                        value = Boolean.toString(records.get(k).expectedDetection());
                    } else if (name.equals("related_ids")) {
                        value = "[" + String.join(", ", records.get(k).relatedIds().stream().map(JsonText::quote)
                                .toList()) + "]";
                    } else {
                        value = JsonText.quote(f[c]);
                    }
                    sb.append(c == 0 ? "" : ", ").append(JsonText.quote(name)).append(": ").append(value);
                }
                sb.append('}');
            }
            sb.append(records.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
            Files.writeString(labelsDir.resolve(JSON_FILE), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write ground truth", e);
        }
    }

    private static String[] fields(GroundTruthRecord r) {
        return new String[] {r.gtId(), r.issueType().name(), r.entityType(), r.entityId(), r.skuId(),
                r.eventDate().toString(), r.relatedIds().isEmpty() ? null : String.join("|", r.relatedIds()),
                r.fieldName(), r.baselineValue(), r.injectedValue(), r.violatedRule(),
                r.expectedDetection() ? "TRUE" : "FALSE", r.expectedAction()};
    }

    public static List<GroundTruthRecord> read(Path csvFile) {
        try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.builder().setReader(reader).setFormat(CsvSupport.READ_FORMAT).get()) {
            if (!parser.getHeaderNames().equals(GroundTruthRecord.COLUMNS)) {
                throw new IllegalArgumentException(csvFile + ": unexpected header " + parser.getHeaderNames());
            }
            List<GroundTruthRecord> out = new ArrayList<>();
            for (CSVRecord r : parser) {
                String related = r.get("related_ids");
                out.add(new GroundTruthRecord(r.get("gt_id"), IssueType.valueOf(r.get("issue_type")),
                        r.get("entity_type"), r.get("entity_id"), r.get("sku_id"), LocalDate.parse(r.get("event_date")),
                        related == null ? List.of() : Arrays.asList(related.split("\\|")), r.get("field_name"),
                        r.get("baseline_value"), r.get("injected_value"), r.get("violated_rule"),
                        "TRUE".equals(r.get("expected_detection")), r.get("expected_action")));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + csvFile, e);
        }
    }
}
