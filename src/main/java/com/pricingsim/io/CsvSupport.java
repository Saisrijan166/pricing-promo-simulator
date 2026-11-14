package com.pricingsim.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.QuoteMode;

/**
 * The canonical CSV rules (PLAN.md §11):
 * <ul>
 *   <li>RFC 4180 quoting, UTF-8 without BOM, {@code \n} line endings, a header row in {@link TableSpec} column order;</li>
 *   <li>null is an empty field, and a non-null empty string is rejected (it would read back as null);</li>
 *   <li>{@link BigDecimal} via {@code toPlainString()}, dates as ISO-8601 {@code yyyy-MM-dd}, timestamps as ISO-8601
 *       UTC ({@code 2026-01-01T06:00:00Z}), enums by name, booleans as {@code true}/{@code false};</li>
 *   <li>rows sorted by primary key ({@link #primaryKey}).</li>
 * </ul>
 * Record ↔ row mapping is by name: a record component {@code skuId} is column {@code sku_id}.
 */
public final class CsvSupport {

    public static final String LINE_SEPARATOR = "\n";

    /** Separator inside composite primary-key sort keys; sorts below every printable character. */
    private static final char PK_SEPARATOR = '\u0000';

    /** Format used to write rows. The header is written separately so {@link CSVFormat#format} never emits it. */
    public static final CSVFormat WRITE_FORMAT = CSVFormat.RFC4180.builder()
            .setRecordSeparator(LINE_SEPARATOR)
            .setQuoteMode(QuoteMode.MINIMAL)
            .setNullString("")
            .get();

    /** Format used to read files written with {@link #WRITE_FORMAT}: first row is the header, empty field = null. */
    public static final CSVFormat READ_FORMAT = CSVFormat.RFC4180.builder()
            .setRecordSeparator(LINE_SEPARATOR)
            .setNullString("")
            .setHeader()
            .setSkipHeaderRecord(true)
            .get();

    private static final ClassValue<Codec> CODECS = new ClassValue<>() {
        @Override
        protected Codec computeValue(Class<?> type) {
            return new Codec(type);
        }
    };

    private CsvSupport() {
    }

    // --- Values ---------------------------------------------------------------------------------------------------

    /** The CSV text for one value; {@code null} stays {@code null} (written as an empty field). */
    public static String formatValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            if (s.isEmpty()) {
                throw new IllegalArgumentException("Empty string is not allowed: an empty CSV field means null");
            }
            return s;
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof Instant i) {
            return formatInstant(i);
        }
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Boolean) {
            return value.toString();
        }
        throw new IllegalArgumentException("Unsupported CSV value type: " + value.getClass().getName());
    }

    /** ISO-8601 UTC with whole seconds; sub-second values are rejected so the format never varies. */
    public static String formatInstant(Instant instant) {
        if (instant.getNano() != 0) {
            throw new IllegalArgumentException("Timestamps must have whole-second precision: " + instant);
        }
        if (instant.getLong(ChronoField.INSTANT_SECONDS) < 0) {
            throw new IllegalArgumentException("Timestamp before 1970 is not expected: " + instant);
        }
        return instant.toString();
    }

    /** Parses CSV text (null for an empty field) into {@code type}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object parseValue(String text, Class<?> type) {
        if (text == null) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("Null for primitive " + type);
            }
            return null;
        }
        if (type == String.class) {
            return text;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(text);
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf(text);
        }
        if (type == boolean.class || type == Boolean.class) {
            if (!text.equals("true") && !text.equals("false")) {
                throw new IllegalArgumentException("Not a boolean: " + text);
            }
            return Boolean.valueOf(text);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(text);
        }
        if (type == LocalDate.class) {
            return LocalDate.parse(text);
        }
        if (type == Instant.class) {
            return Instant.parse(text);
        }
        if (type.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) type, text);
        }
        throw new IllegalArgumentException("Unsupported CSV value type: " + type.getName());
    }

    // --- Rows -----------------------------------------------------------------------------------------------------

    /** Column values of {@code row} in {@link TableSpec} column order, formatted per the CSV rules. */
    public static String[] formatRow(TableSpec table, Record row) {
        Codec codec = codecFor(table, row);
        Object[] values = codec.values(row);
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = formatValue(values[i]);
        }
        return out;
    }

    /** One CSV line (without the line separator). */
    public static String formatLine(String[] fields) {
        return WRITE_FORMAT.format((Object[]) fields);
    }

    /** The header line for {@code table}. */
    public static String headerLine(TableSpec table) {
        return formatLine(table.columnNames().toArray(String[]::new));
    }

    /**
     * Sort key for a formatted row: the primary-key fields joined by a separator that sorts first, so composite keys
     * order by the first column, then the next. IDs are zero-padded, so string order is natural order.
     */
    public static String primaryKey(TableSpec table, String[] fields) {
        List<String> columns = table.columnNames();
        StringBuilder key = new StringBuilder();
        for (String pkColumn : table.primaryKey()) {
            String value = fields[columns.indexOf(pkColumn)];
            if (value == null) {
                throw new IllegalArgumentException(table.name() + ": null primary key column " + pkColumn);
            }
            if (!key.isEmpty()) {
                key.append(PK_SEPARATOR);
            }
            key.append(value);
        }
        return key.toString();
    }

    /** Writes a header and pre-formatted lines (already in PK order) to {@code file}. */
    public static void writeLines(Path file, TableSpec table, Iterable<String> lines) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), 1 << 16)) {
                w.write(headerLine(table));
                w.write(LINE_SEPARATOR);
                for (String line : lines) {
                    w.write(line);
                    w.write(LINE_SEPARATOR);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** Reads a CSV file written by these rules back into records of {@code table.recordType()}. */
    public static <R extends Record> List<R> read(TableSpec table, Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.builder().setReader(reader).setFormat(READ_FORMAT).get()) {
            List<String> header = parser.getHeaderNames();
            if (!header.equals(table.columnNames())) {
                throw new IllegalArgumentException(file + ": header " + header + " does not match "
                        + table.columnNames());
            }
            Codec codec = CODECS.get(table.recordType());
            codec.requireColumns(table);
            List<R> rows = new ArrayList<>();
            for (CSVRecord rec : parser) {
                rows.add(codec.<R>create(rec.values()));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static Codec codecFor(TableSpec table, Record row) {
        Objects.requireNonNull(row, "row");
        if (row.getClass() != table.recordType()) {
            throw new IllegalArgumentException(table.name() + " expects " + table.recordType().getSimpleName()
                    + " but got " + row.getClass().getSimpleName());
        }
        Codec codec = CODECS.get(row.getClass());
        codec.requireColumns(table);
        return codec;
    }

    /** camelCase → snake_case ({@code skuId} → {@code sku_id}, {@code demand7dUnits} → {@code demand_7d_units}). */
    public static String snakeCase(String camel) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            boolean boundary = i > 0 && (Character.isUpperCase(c)
                    || (Character.isDigit(c) && !Character.isDigit(camel.charAt(i - 1))));
            if (boundary) {
                out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /** Reflection-based accessor/constructor for one record type, computed once per type. */
    private static final class Codec {
        private final Class<?> type;
        private final RecordComponent[] components;
        private final List<String> columnNames;
        private final Constructor<?> constructor;

        Codec(Class<?> type) {
            this.type = type;
            this.components = type.getRecordComponents();
            this.columnNames = Arrays.stream(components).map(c -> snakeCase(c.getName())).toList();
            try {
                this.constructor = type.getDeclaredConstructor(
                        Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No canonical constructor for " + type, e);
            }
        }

        void requireColumns(TableSpec table) {
            if (!columnNames.equals(table.columnNames())) {
                throw new IllegalStateException(type.getSimpleName() + " components " + columnNames
                        + " do not match " + table.name() + " columns " + table.columnNames());
            }
        }

        Object[] values(Record row) {
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                try {
                    values[i] = components[i].getAccessor().invoke(row);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("Cannot read " + components[i].getName(), e);
                }
            }
            return values;
        }

        @SuppressWarnings("unchecked")
        <R> R create(String[] fields) {
            if (fields.length != components.length) {
                throw new IllegalArgumentException(type.getSimpleName() + ": expected " + components.length
                        + " fields, got " + fields.length);
            }
            Object[] args = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                args[i] = parseValue(fields[i], components[i].getType());
            }
            try {
                return (R) constructor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create " + type.getSimpleName(), e);
            }
        }
    }
}
