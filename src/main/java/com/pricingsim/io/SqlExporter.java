package com.pricingsim.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * {@code export --formats sql} (PLAN.md §11): {@code sql/schema.sql} is the Flyway migrations concatenated in version
 * order, and {@code sql/seed.sql} loads the data with multi-row INSERTs (500 rows each) in FK-safe order inside one
 * transaction. {@code psql -f schema.sql && psql -f seed.sql} rebuilds the dataset without the application.
 */
public final class SqlExporter {

    public static final String DIR = "sql";
    public static final int ROWS_PER_INSERT = 500;

    private SqlExporter() {
    }

    public static List<Path> export(Path datasetDir) {
        Path out = datasetDir.resolve(DIR);
        try {
            Files.createDirectories(out);
            Path schema = out.resolve("schema.sql");
            Files.writeString(schema, schemaSql(), StandardCharsets.UTF_8);
            Path seed = out.resolve("seed.sql");
            writeSeed(new CsvDatasetReader(datasetDir), seed);
            return List.of(schema, seed);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot export SQL", e);
        }
    }

    /** The Flyway migrations V1…Vn, concatenated in version order. */
    public static String schemaSql() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
        List<Resource> sorted = new ArrayList<>(Arrays.asList(scripts));
        sorted.sort(Comparator.comparingInt(r -> Integer.parseInt(r.getFilename().substring(1,
                r.getFilename().indexOf("__")))));
        StringBuilder sb = new StringBuilder("-- Schema of the pricing & promotion simulator: the Flyway migrations"
                + " concatenated in version order.\n\n");
        for (Resource r : sorted) {
            try (InputStream in = r.getInputStream()) {
                sb.append("-- ").append(r.getFilename()).append('\n')
                        .append(new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private static void writeSeed(CsvDatasetReader reader, Path file) throws IOException {
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), 1 << 16)) {
            w.write("-- Generated dataset as INSERT statements in FK-safe order (run after schema.sql).\n");
            w.write("BEGIN;\n");
            for (TableSpec table : TableRegistry.loadOrder()) {
                String head = "INSERT INTO " + table.name() + " (" + String.join(", ", table.columnNames())
                        + ") VALUES\n";
                List<String> batch = new ArrayList<>(ROWS_PER_INSERT);
                reader.forEachRaw(table, fields -> {
                    batch.add(row(table, fields));
                    if (batch.size() == ROWS_PER_INSERT) {
                        flush(w, head, batch);
                    }
                });
                flush(w, head, batch);
            }
            w.write("COMMIT;\n");
        }
    }

    private static void flush(Writer w, String head, List<String> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            w.write(head);
            w.write(String.join(",\n", batch));
            w.write(";\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        batch.clear();
    }

    private static String row(TableSpec table, String[] fields) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(literal(table.columns().get(i).type(), fields[i]));
        }
        return sb.append(')').toString();
    }

    static String literal(TableSpec.ColumnType type, String text) {
        if (text == null) {
            return "NULL";
        }
        return switch (type) {
            case INTEGER, BIGINT, NUMERIC -> text;
            case BOOLEAN -> text.toUpperCase(java.util.Locale.ROOT);
            case DATE -> "DATE '" + text + "'";
            case TIMESTAMPTZ -> "TIMESTAMP WITH TIME ZONE '" + text.replace('T', ' ').replace("Z", "+00") + "'";
            default -> "'" + text.replace("'", "''") + "'";
        };
    }
}
