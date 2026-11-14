package com.pricingsim.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.TestDatabases;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.generator.TestSettings;
import com.pricingsim.io.CsvSupport;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.persistence.BulkLoader;
import com.pricingsim.pricing.PricingPolicy;

/**
 * Validation on H2 (PLAN.md §12): a generated dataset passes every structural check, and each
 * {@code fixtures/broken/*.properties} patch makes exactly its one check fail. A fixture is a small declarative patch on
 * the generated tiny dataset (duplicate a row, copy it with new values, or set values on the first matching row); where
 * the database would reject the broken row, the fixture drops that constraint first, showing that the validator catches
 * it independently of the DB.
 */
class ValidationRunnerIT {

    private static final String[] FIXTURES = {"duplicate_primary_key", "orphan_foreign_key", "invalid_enum",
            "invalid_id_format", "reversed_dates", "illegal_transition", "broken_reconciliation",
            "duplicate_natural_key"};

    @TempDir
    static Path tmp;
    private static Path dataset;

    @BeforeAll
    static void generate() {
        dataset = tmp.resolve("tiny");
        DatasetWriter.write(TestSettings.forScale("tiny"), dataset);
    }

    private static ValidationRunner.Report validate(Path dir, String... ddl) {
        DataSource ds = TestDatabases.fresh("validation");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        for (String statement : ddl) {
            jdbc.execute(statement);
        }
        new BulkLoader(ds).load(dir);
        return new ValidationRunner(ds, PricingPolicy.defaults()).run(ManifestWriter.read(dir), dir.toString());
    }

    @Test
    void generatedDatasetPassesEveryStructuralCheck() {
        ValidationRunner.Report r = validate(dataset);
        assertThat(r.checks().stream().filter(c -> c.status() == CheckResult.Status.FAIL).toList()).isEmpty();
        assertThat(r.structurallyValid()).isTrue();
        assertThat(r.checks()).extracting(CheckResult::checkId).doesNotHaveDuplicates();
        assertThat(r.checks()).hasSizeGreaterThan(150);
        // tiny injects 1 anomaly per type; each business-rule check finds exactly it.
        assertThat(r.businessRuleFindings()).hasSize(5).allSatisfy((t, n) -> assertThat(n).as(t.name()).isEqualTo(1L));
        assertThat(r.businessRuleFindings()).containsOnlyKeys(IssueType.PRICE_BELOW_FLOOR,
                IssueType.PROMO_STACK_VIOLATION, IssueType.STALE_OBSERVATION_AS_CURRENT,
                IssueType.MARGIN_BEFORE_DISCOUNT, IssueType.PRICE_CHANGE_WITHOUT_REASON);
        // Every category of PLAN.md §10 is present.
        assertThat(r.checks()).extracting(CheckResult::category).contains(CheckCategory.values());
    }

    @Test
    void summaryFilesAreWritten() throws IOException {
        Path out = tmp.resolve("summary");
        ValidationRunner.Report r = validate(dataset);
        ValidationSummaryWriter.write(out, r);
        String json = Files.readString(out.resolve(ValidationSummaryWriter.JSON_FILE));
        assertThat(json).contains("\"failed\": 0").contains("\"business_rule_findings\": {\"PRICE_BELOW_FLOOR\": 1");
        assertThat(Files.readString(out.resolve(ValidationSummaryWriter.MARKDOWN_FILE)))
                .contains("| **Structural failures** | **0** |").contains("| **Business-rule findings** | **5** |");
    }

    static Stream<Arguments> fixtures() {
        return Stream.of(FIXTURES).map(f -> Arguments.of(Named.of(f, f)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void eachBrokenFixtureFailsExactlyItsCheck(String name) throws IOException {
        Properties fixture = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/fixtures/broken/" + name + ".properties")) {
            assertThat(in).as(name).isNotNull();
            fixture.load(in);
        }
        Path dir = tmp.resolve("broken-" + name);
        copyDataset(dataset, dir);
        patch(dir.resolve("csv").resolve(fixture.getProperty("table") + ".csv"), fixture);

        String ddl = fixture.getProperty("ddl");
        ValidationRunner.Report r = ddl == null ? validate(dir) : validate(dir, ddl.split(";"));
        List<CheckResult> failed = r.checks().stream().filter(c -> c.status() == CheckResult.Status.FAIL).toList();
        assertThat(failed).as(name + ": failing checks").hasSize(1);
        assertThat(failed.get(0).category().name()).isEqualTo(fixture.getProperty("expect.category"));
        assertThat(failed.get(0).table()).isEqualTo(fixture.getProperty("expect.table"));
        assertThat(failed.get(0).violations()).isPositive();
        assertThat(r.structurallyValid()).isFalse();
    }

    private static void copyDataset(Path from, Path to) throws IOException {
        Files.createDirectories(to.resolve("csv"));
        Files.copy(from.resolve(ManifestWriter.MANIFEST_FILE), to.resolve(ManifestWriter.MANIFEST_FILE));
        try (Stream<Path> files = Files.list(from.resolve("csv"))) {
            for (Path f : files.toList()) {
                Files.copy(f, to.resolve("csv").resolve(f.getFileName()));
            }
        }
    }

    /** Applies a fixture: duplicate / copy (with set.*) / set on the first row matching every where.* value. */
    private static void patch(Path file, Properties fixture) throws IOException {
        List<String> header;
        List<String[]> rows = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.builder().setReader(reader).setFormat(CsvSupport.READ_FORMAT).get()) {
            header = parser.getHeaderNames();
            for (CSVRecord rec : parser) {
                rows.add(rec.values());
            }
        }
        Map<String, String> where = prefixed(fixture, "where.");
        Map<String, String> set = prefixed(fixture, "set.");
        int index = -1;
        for (int i = 0; i < rows.size() && index < 0; i++) {
            final String[] row = rows.get(i);
            if (where.entrySet().stream().allMatch(e -> e.getValue().equals(row[header.indexOf(e.getKey())]))) {
                index = i;
            }
        }
        assertThat(index).as("no row matches " + where).isNotNegative();
        String[] target = rows.get(index).clone();
        set.forEach((col, value) -> target[header.indexOf(col)] = value);
        switch (fixture.getProperty("action")) {
            case "duplicate" -> rows.add(rows.get(index).clone());
            case "copy" -> rows.add(target);
            case "set" -> rows.set(index, target);
            default -> throw new IllegalArgumentException("Unknown action " + fixture.getProperty("action"));
        }
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(CsvSupport.formatLine(header.toArray(String[]::new)) + "\n");
            for (String[] row : rows) {
                w.write(CsvSupport.formatLine(row) + "\n");
            }
        }
    }

    private static Map<String, String> prefixed(Properties p, String prefix) {
        Map<String, String> out = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                out.put(key.substring(prefix.length()), p.getProperty(key));
            }
        }
        return out;
    }
}
