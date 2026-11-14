package com.pricingsim.validation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.io.JsonText;

/**
 * Writes {@code validation_summary.json} and {@code validation_summary.md} (PLAN.md §10). Both contain only values
 * derived from the data, so they are identical for identical datasets; the execution time goes to
 * {@code run_log.json}.
 */
public final class ValidationSummaryWriter {

    public static final String JSON_FILE = "validation_summary.json";
    public static final String MARKDOWN_FILE = "validation_summary.md";

    private ValidationSummaryWriter() {
    }

    public static void write(Path dir, ValidationRunner.Report r) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(JSON_FILE), json(r), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(MARKDOWN_FILE), markdown(r), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write validation summary", e);
        }
    }

    static String json(ValidationRunner.Report r) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"run_id\": ").append(JsonText.quote(r.runId())).append(",\n");
        sb.append("  \"seed\": ").append(r.seed()).append(",\n");
        sb.append("  \"scale\": ").append(JsonText.quote(r.scale())).append(",\n");
        sb.append("  \"dataset_fingerprint\": ").append(JsonText.quote(r.fingerprint())).append(",\n");
        sb.append("  \"totals\": {\"checks\": ").append(r.checks().size()).append(", \"passed\": ").append(r.passed())
                .append(", \"failed\": ").append(r.failed()).append(", \"warnings\": ").append(r.warnings())
                .append("},\n");
        sb.append("  \"checks\": [\n");
        List<CheckResult> checks = r.checks();
        for (int i = 0; i < checks.size(); i++) {
            CheckResult c = checks.get(i);
            sb.append("    {\"check_id\": ").append(JsonText.quote(c.checkId()))
                    .append(", \"category\": ").append(JsonText.quote(c.category().name()))
                    .append(", \"table\": ").append(JsonText.quote(c.table()))
                    .append(", \"description\": ").append(JsonText.quote(c.description()))
                    .append(", \"rows_checked\": ").append(c.rowsChecked())
                    .append(", \"violations\": ").append(c.violations())
                    .append(", \"status\": ").append(JsonText.quote(c.status().name()))
                    .append(", \"sample_ids\": [")
                    .append(String.join(", ", c.sampleIds().stream().map(JsonText::quote).toList()))
                    .append("]}").append(i + 1 < checks.size() ? ",\n" : "\n");
        }
        sb.append("  ],\n  \"business_rule_findings\": {");
        int k = 0;
        for (Map.Entry<IssueType, Long> e : r.businessRuleFindings().entrySet()) {
            sb.append(k++ == 0 ? "" : ", ").append(JsonText.quote(e.getKey().name())).append(": ").append(e.getValue());
        }
        return sb.append("}\n}\n").toString();
    }

    static String markdown(ValidationRunner.Report r) {
        long structural = r.checks().stream().filter(c -> c.category().structural()).count();
        long structuralFailures = r.failed();
        long findings = r.businessRuleFindings().values().stream().mapToLong(Long::longValue).sum();
        StringBuilder sb = new StringBuilder("# Validation summary\n\n");
        sb.append("| Item | Value |\n|---|---|\n");
        sb.append("| Run | `").append(r.runId()).append("` |\n");
        sb.append("| Seed / scale | ").append(r.seed()).append(" / ").append(r.scale()).append(" |\n");
        sb.append("| Dataset fingerprint | `").append(r.fingerprint()).append("` |\n");
        sb.append("| Checks | ").append(r.checks().size()).append(" (").append(structural).append(" structural, ")
                .append(r.checks().size() - structural).append(" business-rule) |\n");
        sb.append("| Passed / failed / warnings | ").append(r.passed()).append(" / ").append(r.failed()).append(" / ")
                .append(r.warnings()).append(" |\n");
        sb.append("| **Structural failures** | **").append(structuralFailures).append("** |\n");
        sb.append("| **Business-rule findings** | **").append(findings).append("** |\n\n");
        sb.append("## Business-rule findings\n\n| Issue type | Findings |\n|---|---|\n");
        r.businessRuleFindings().forEach((t, n) -> sb.append("| ").append(t).append(" | ").append(n).append(" |\n"));
        sb.append("\n## Checks\n\n| ID | Category | Table | Check | Rows | Violations | Status | Sample IDs |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (CheckResult c : r.checks()) {
            sb.append("| ").append(c.checkId()).append(" | ").append(c.category()).append(" | ").append(c.table())
                    .append(" | ").append(c.description().replace("|", "\\|")).append(" | ").append(c.rowsChecked())
                    .append(" | ").append(c.violations()).append(" | ").append(c.status()).append(" | ")
                    .append(String.join(", ", c.sampleIds())).append(" |\n");
        }
        return sb.toString();
    }
}
