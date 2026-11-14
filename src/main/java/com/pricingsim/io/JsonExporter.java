package com.pricingsim.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code export --formats json} (PLAN.md §11): one file per table under {@code json/}, keys in column order. A table of
 * up to 100,000 rows is a JSON array ({@code <table>.json}); a larger one is JSON Lines ({@code <table>.jsonl}, one
 * object per line) so memory stays flat. Numbers are exact decimals, dates and timestamps ISO-8601 strings, and the
 * decision explanation stays a JSON string, exactly as stored.
 */
public final class JsonExporter {

    public static final String DIR = "json";
    public static final long JSON_LINES_THRESHOLD = 100_000;

    private JsonExporter() {
    }

    /** Exports every table; returns the written file per table. */
    public static Map<String, Path> export(Path datasetDir) {
        CsvDatasetReader reader = new CsvDatasetReader(datasetDir);
        Map<String, Long> rows = ManifestWriter.read(datasetDir).rowsByPath();
        Path out = datasetDir.resolve(DIR);
        Map<String, Path> written = new TreeMap<>();
        try {
            Files.createDirectories(out);
            for (TableSpec table : TableRegistry.loadOrder()) {
                long n = rows.getOrDefault(CsvDatasetReader.CSV_DIR + "/" + table.fileName(), 0L);
                boolean lines = n > JSON_LINES_THRESHOLD;
                Path file = out.resolve(table.name() + (lines ? ".jsonl" : ".json"));
                Files.deleteIfExists(out.resolve(table.name() + (lines ? ".json" : ".jsonl")));
                write(reader, table, file, lines);
                written.put(table.name(), file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot export JSON", e);
        }
        return written;
    }

    private static void write(CsvDatasetReader reader, TableSpec table, Path file, boolean jsonLines) throws IOException {
        List<TableSpec.Column> columns = table.columns();
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), 1 << 16)) {
            boolean[] first = {true};
            if (!jsonLines) {
                w.write("[");
            }
            reader.forEachRaw(table, fields -> {
                StringBuilder sb = new StringBuilder(256);
                if (!jsonLines) {
                    sb.append(first[0] ? "\n  " : ",\n  ");
                }
                first[0] = false;
                sb.append('{');
                for (int i = 0; i < fields.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(JsonText.quote(columns.get(i).name())).append(": ").append(value(columns.get(i).type(),
                            fields[i]));
                }
                sb.append('}');
                if (jsonLines) {
                    sb.append('\n');
                }
                try {
                    w.write(sb.toString());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (!jsonLines) {
                w.write(first[0] ? "]\n" : "\n]\n");
            }
        }
    }

    static String value(TableSpec.ColumnType type, String text) {
        if (text == null) {
            return "null";
        }
        return switch (type) {
            case INTEGER, BIGINT, NUMERIC -> text;
            case BOOLEAN -> text;
            default -> JsonText.quote(text);
        };
    }
}
