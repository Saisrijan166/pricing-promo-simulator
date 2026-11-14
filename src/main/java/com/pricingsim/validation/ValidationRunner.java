package com.pricingsim.validation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.persistence.Dialect;
import com.pricingsim.persistence.RunDao;
import com.pricingsim.pricing.PricingPolicy;
import com.pricingsim.validation.checks.BusinessRuleChecks;
import com.pricingsim.validation.checks.CoverageChecks;
import com.pricingsim.validation.checks.DateOrderChecks;
import com.pricingsim.validation.checks.LifecycleTransitionChecks;
import com.pricingsim.validation.checks.NoLookAheadChecks;
import com.pricingsim.validation.checks.ReconciliationChecks;
import com.pricingsim.validation.checks.RegistryChecks;

/**
 * Runs every validation check against the loaded database (PLAN.md §10) and records the results in
 * {@code validation_result} under a VALIDATE run. Check IDs are {@code <prefix>-NNN} in a fixed order, so they are
 * stable from run to run.
 */
public final class ValidationRunner {

    public static final int SAMPLE_SIZE = 5;

    private final JdbcTemplate jdbc;
    private final Dialect dialect;
    private final PricingPolicy policy;

    public ValidationRunner(DataSource dataSource, PricingPolicy policy) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.dialect = Dialect.of(dataSource);
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** The outcome of a validation run. */
    public record Report(String runId, long seed, String scale, String fingerprint, List<CheckResult> checks,
                         Map<IssueType, Long> businessRuleFindings) {

        public long passed() {
            return checks.stream().filter(c -> c.status() == CheckResult.Status.PASS).count();
        }

        public long failed() {
            return checks.stream().filter(c -> c.status() == CheckResult.Status.FAIL).count();
        }

        public long warnings() {
            return checks.stream().filter(c -> c.status() == CheckResult.Status.WARN).count();
        }

        /** {@code true} when no structural check failed. */
        public boolean structurallyValid() {
            return failed() == 0;
        }
    }

    /** All checks in run order. */
    public List<ValidationCheck> checks() {
        List<ValidationCheck> all = new ArrayList<>();
        all.addAll(RegistryChecks.primaryKeys());
        all.addAll(RegistryChecks.foreignKeys());
        all.addAll(RegistryChecks.enums());
        all.addAll(RegistryChecks.idFormats(dialect));
        all.addAll(DateOrderChecks.all());
        all.addAll(NoLookAheadChecks.all());
        all.addAll(ReconciliationChecks.all());
        all.addAll(LifecycleTransitionChecks.all());
        all.addAll(RegistryChecks.duplicates());
        all.addAll(CoverageChecks.all());
        all.addAll(BusinessRuleChecks.all());
        return all;
    }

    public Report run(ManifestWriter.Summary manifest, String inputPath) {
        String runId = new RunDao(jdbc).start(RunDao.Command.VALIDATE, manifest.seed(), manifest.scale(),
                manifest.fingerprint(), inputPath);
        Map<CheckCategory, Integer> seq = new EnumMap<>(CheckCategory.class);
        Map<IssueType, Long> findings = new EnumMap<>(IssueType.class);
        List<CheckResult> results = new ArrayList<>();
        for (ValidationCheck check : checks()) {
            int n = seq.merge(check.category(), 1, Integer::sum);
            String id = String.format(Locale.ROOT, "%s-%03d", check.category().prefix(), n);
            long violations;
            List<String> sample;
            if (check instanceof ValidationCheck.Sql sql) {
                violations = sql.count(jdbc);
                sample = violations == 0 ? List.of() : sql.sample(jdbc, SAMPLE_SIZE);
            } else {
                List<String> all = new ArrayList<>(check.violations(jdbc, policy));
                all.sort(null);
                violations = all.size();
                sample = all.subList(0, Math.min(SAMPLE_SIZE, all.size()));
            }
            if (check instanceof BusinessRuleChecks.RuleCheck rule) {
                findings.put(rule.issueType(), violations);
            }
            long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + check.table(), Long.class);
            CheckResult.Status status = violations == 0 ? CheckResult.Status.PASS
                    : check.category().structural() ? CheckResult.Status.FAIL : CheckResult.Status.WARN;
            CheckResult r = new CheckResult(id, check.category(), check.table(), check.description(), rows, violations,
                    status, sample);
            results.add(r);
            String samples = String.join(",", sample);
            jdbc.update("INSERT INTO validation_result (run_id, check_id, category, table_name, description,"
                            + " rows_checked, violations, status, sample_ids) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    runId, id, check.category().name(), check.table(), truncate(check.description(), 400), rows,
                    violations, status.name(), samples.isEmpty() ? null : truncate(samples, 1000));
        }
        Report report = new Report(runId, manifest.seed(), manifest.scale(), manifest.fingerprint(), results, findings);
        new RunDao(jdbc).finish(runId, report.structurallyValid(), String.format(Locale.ROOT,
                "{\"checks\": %d, \"passed\": %d, \"failed\": %d, \"warnings\": %d}", results.size(), report.passed(),
                report.failed(), report.warnings()));
        return report;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
