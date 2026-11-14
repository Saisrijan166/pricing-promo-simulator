package com.pricingsim.evaluation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.io.JsonText;

/** Writes {@code evaluation_metrics.json} (PLAN.md §10): deterministic, from data and labels only. */
public final class EvaluationReportWriter {

    public static final String FILE = "evaluation_metrics.json";

    private EvaluationReportWriter() {
    }

    /** Everything the report contains. */
    public record Report(long seed, String scale, String fingerprint, GroundTruthEvaluator.Evaluation evaluation,
                         List<DatasetMetricsCalculator.Metric> realism, Map<IssueType, Long> operational,
                         Map<String, Map<String, Long>> decisionMix, Map<String, BigDecimal[]> byCategory) {
    }

    public static void write(Path file, Report r) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"seed\": ").append(r.seed()).append(",\n");
        sb.append("  \"scale\": ").append(JsonText.quote(r.scale())).append(",\n");
        sb.append("  \"dataset_fingerprint\": ").append(JsonText.quote(r.fingerprint())).append(",\n");
        sb.append("  \"matching\": \"(issue_type, entity_type, entity_id)\",\n");
        sb.append("  \"anomaly_detection\": {\n");
        int i = 0;
        for (Map.Entry<IssueType, GroundTruthEvaluator.Score> e : r.evaluation().perType().entrySet()) {
            sb.append("    ").append(JsonText.quote(e.getKey().name())).append(": ").append(score(e.getValue()))
                    .append(++i < r.evaluation().perType().size() ? ",\n" : "\n");
        }
        sb.append("  },\n");
        GroundTruthEvaluator.Evaluation ev = r.evaluation();
        sb.append("  \"micro\": ").append(score(ev.micro())).append(",\n");
        sb.append("  \"macro\": {\"precision\": ").append(n(ev.macroPrecision())).append(", \"recall\": ")
                .append(n(ev.macroRecall())).append(", \"f1\": ").append(n(ev.macroF1())).append("},\n");
        sb.append("  \"missed\": ").append(list(ev.missed())).append(",\n");
        sb.append("  \"unexpected\": ").append(list(ev.unexpected())).append(",\n");
        sb.append("  \"realism\": [\n");
        for (int k = 0; k < r.realism().size(); k++) {
            DatasetMetricsCalculator.Metric m = r.realism().get(k);
            sb.append("    {\"metric\": ").append(JsonText.quote(m.name())).append(", \"value\": ").append(n(m.value()))
                    .append(", \"band\": [").append(n(m.bandLow())).append(", ").append(n(m.bandHigh()))
                    .append("], \"unit\": ").append(JsonText.quote(m.unit())).append(", \"status\": ")
                    .append(JsonText.quote(m.status())).append('}').append(k + 1 < r.realism().size() ? ",\n" : "\n");
        }
        sb.append("  ],\n  \"operational_exceptions\": {");
        i = 0;
        for (Map.Entry<IssueType, Long> e : r.operational().entrySet()) {
            sb.append(i++ == 0 ? "" : ", ").append(JsonText.quote(e.getKey().name())).append(": ").append(e.getValue());
        }
        sb.append("},\n  \"decision_mix\": {\n");
        i = 0;
        for (Map.Entry<String, Map<String, Long>> e : r.decisionMix().entrySet()) {
            sb.append("    ").append(JsonText.quote(e.getKey())).append(": {");
            int j = 0;
            for (Map.Entry<String, Long> v : e.getValue().entrySet()) {
                sb.append(j++ == 0 ? "" : ", ").append(JsonText.quote(v.getKey())).append(": ").append(v.getValue());
            }
            sb.append('}').append(++i < r.decisionMix().size() ? ",\n" : "\n");
        }
        sb.append("  },\n  \"by_category\": {\n");
        i = 0;
        for (Map.Entry<String, BigDecimal[]> e : r.byCategory().entrySet()) {
            BigDecimal[] v = e.getValue();
            sb.append("    ").append(JsonText.quote(e.getKey())).append(": {\"units\": ").append(v[0].toPlainString())
                    .append(", \"net_revenue\": ").append(v[1].toPlainString()).append(", \"gross_margin\": ")
                    .append(v[2].toPlainString()).append('}').append(++i < r.byCategory().size() ? ",\n" : "\n");
        }
        sb.append("  }\n}\n");
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private static String score(GroundTruthEvaluator.Score s) {
        return "{\"labelled\": " + s.labelled() + ", \"detected\": " + s.detected() + ", \"tp\": " + s.truePositives()
                + ", \"fp\": " + s.falsePositives() + ", \"fn\": " + s.falseNegatives() + ", \"precision\": "
                + n(s.precision()) + ", \"recall\": " + n(s.recall()) + ", \"f1\": " + n(s.f1()) + "}";
    }

    private static String n(double v) {
        return JsonText.number(v, 4);
    }

    private static String list(List<String> items) {
        return "[" + String.join(", ", items.stream().map(JsonText::quote).toList()) + "]";
    }
}
