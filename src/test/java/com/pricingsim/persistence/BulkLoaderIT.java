package com.pricingsim.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.TestDatabases;
import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.generator.TestSettings;
import com.pricingsim.io.ManifestWriter;

/** Load a generated dataset into H2: counts equal the manifest, values survive, and a reload is idempotent. */
class BulkLoaderIT {

    @TempDir
    static Path tmp;
    private static Path dataset;

    @BeforeAll
    static void generate() {
        dataset = tmp.resolve("tiny");
        DatasetWriter.write(TestSettings.forScale("tiny"), dataset);
    }

    @Test
    void countsMatchTheManifestAndReloadIsIdempotent() {
        DataSource ds = TestDatabases.fresh("loader");
        BulkLoader loader = new BulkLoader(ds);
        assertThat(loader.dialect()).isEqualTo(Dialect.H2);
        Map<String, Long> manifestRows = ManifestWriter.read(dataset).rowsByPath();

        Map<String, Long> first = loader.load(dataset);
        assertThat(first).hasSize(16);
        first.forEach((table, rows) -> assertThat(rows).as(table).isEqualTo(manifestRows.get("csv/" + table + ".csv")));
        assertThat(loader.counts()).isEqualTo(first);

        Map<String, Long> second = loader.load(dataset);
        assertThat(second).isEqualTo(first);
        assertThat(loader.counts()).isEqualTo(first);
    }

    @Test
    void valuesSurviveTheRoundTrip() {
        DataSource ds = TestDatabases.fresh("loader-values");
        new BulkLoader(ds).load(dataset);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Map<String, Object> d = jdbc.queryForMap("SELECT * FROM price_decision ORDER BY decision_id LIMIT 1");
        assertThat(d.get("decision_id")).isEqualTo("DEC-2026-000001");
        assertThat(d.get("previous_decision_id")).isNull();
        assertThat(((java.sql.Date) jdbc.queryForObject("SELECT MIN(price_date) FROM price_history",
                java.sql.Date.class)).toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        OffsetDateTime ts = jdbc.queryForObject("SELECT decision_ts FROM price_decision WHERE decision_id = ?",
                OffsetDateTime.class, "DEC-2026-000001");
        assertThat(ts.toInstant()).isEqualTo(java.time.Instant.parse("2026-01-01T06:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_history WHERE source_decision_id IS NULL",
                Long.class)).as("injected PRICE_CHANGE_WITHOUT_REASON span").isPositive();
    }

    @Test
    void clearEmptiesOutputTablesToo() {
        DataSource ds = TestDatabases.fresh("loader-clear");
        BulkLoader loader = new BulkLoader(ds);
        loader.load(dataset);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        RunDao runs = new RunDao(jdbc);
        String runId = runs.start(RunDao.Command.VALIDATE, 1L, "tiny", "0123456789abcdef0123", "x");
        runs.finish(runId, true, "{}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_run", Long.class)).isEqualTo(1);
        loader.load(dataset);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_run", Long.class)).isZero();
    }
}
