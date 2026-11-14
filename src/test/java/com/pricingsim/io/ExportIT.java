package com.pricingsim.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.generator.TestSettings;

/** JSON and SQL exports reproduce the dataset (PLAN.md §11): same row counts, loadable SQL. */
class ExportIT {

    @TempDir
    Path tmp;

    @Test
    void jsonAndSqlExportsMatchTheManifest() throws IOException {
        Path dir = tmp.resolve("tiny");
        DatasetWriter.write(TestSettings.forScale("tiny"), dir);
        Map<String, Long> rows = ManifestWriter.read(dir).rowsByPath();

        Map<String, Path> json = JsonExporter.export(dir);
        assertThat(json).hasSize(16);
        ObjectMapper mapper = new ObjectMapper();
        for (TableSpec table : TableRegistry.loadOrder()) {
            JsonNode array = mapper.readTree(json.get(table.name()).toFile());
            assertThat(array.isArray()).isTrue();
            assertThat((long) array.size()).as(table.name()).isEqualTo(rows.get("csv/" + table.fileName()));
            if (!array.isEmpty()) {
                assertThat(array.get(0).fieldNames()).toIterable().containsExactlyElementsOf(table.columnNames());
            }
        }

        SqlExporter.export(dir);
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:export-" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("RUNSCRIPT FROM '" + dir.resolve("sql/schema.sql").toAbsolutePath() + "'");
        jdbc.execute("RUNSCRIPT FROM '" + dir.resolve("sql/seed.sql").toAbsolutePath() + "'");
        for (TableSpec table : TableRegistry.loadOrder()) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table.name(), Long.class)).as(table.name())
                    .isEqualTo(rows.get("csv/" + table.fileName()));
        }
        assertThat(Files.readString(dir.resolve("sql/seed.sql"))).startsWith("-- Generated").contains("BEGIN;")
                .endsWith("COMMIT;\n");
    }
}
