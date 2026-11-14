package com.pricingsim.io;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Streams a generated dataset's CSV files ({@code <dir>/csv/<table>.csv}) row by row, so loading and exporting a full
 * dataset never holds a table in memory. Values are typed from the {@link TableSpec} column types.
 */
public final class CsvDatasetReader {

    public static final String CSV_DIR = "csv";

    private final Path datasetDir;

    public CsvDatasetReader(Path datasetDir) {
        this.datasetDir = Objects.requireNonNull(datasetDir, "datasetDir");
    }

    public Path datasetDir() {
        return datasetDir;
    }

    public Path fileFor(TableSpec table) {
        return datasetDir.resolve(CSV_DIR).resolve(table.fileName());
    }

    public boolean exists(TableSpec table) {
        return Files.isRegularFile(fileFor(table));
    }

    /** Calls {@code consumer} with each row's raw fields (null for empty) in file order; returns the row count. */
    public long forEachRaw(TableSpec table, Consumer<String[]> consumer) {
        Path file = fileFor(table);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.builder().setReader(reader).setFormat(CsvSupport.READ_FORMAT).get()) {
            List<String> header = parser.getHeaderNames();
            if (!header.equals(table.columnNames())) {
                throw new IllegalArgumentException(file + ": header " + header + " does not match "
                        + table.columnNames());
            }
            long rows = 0;
            for (CSVRecord rec : parser) {
                consumer.accept(rec.values());
                rows++;
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Like {@link #forEachRaw} with values typed for JDBC: String, Long, BigDecimal, Boolean, LocalDate, OffsetDateTime. */
    public long forEachTyped(TableSpec table, Consumer<Object[]> consumer) {
        List<TableSpec.Column> columns = table.columns();
        return forEachRaw(table, fields -> {
            Object[] values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                values[i] = typed(columns.get(i).type(), fields[i]);
            }
            consumer.accept(values);
        });
    }

    /** Typed value of one CSV field for a column type. */
    public static Object typed(TableSpec.ColumnType type, String text) {
        if (text == null) {
            return null;
        }
        return switch (type) {
            case VARCHAR, CHAR, TEXT -> text;
            case INTEGER, BIGINT -> Long.valueOf(text);
            case NUMERIC -> new BigDecimal(text);
            case BOOLEAN -> parseBoolean(text);
            case DATE -> LocalDate.parse(text);
            case TIMESTAMPTZ -> OffsetDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC);
        };
    }

    private static Boolean parseBoolean(String text) {
        if (!text.equals("true") && !text.equals("false")) {
            throw new IllegalArgumentException("Not a boolean: " + text);
        }
        return Boolean.valueOf(text);
    }
}
