package com.pricingsim.generator.sink;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.pricingsim.io.CsvSupport;
import com.pricingsim.io.TableSpec;

/**
 * Writes each declared table to {@code <dir>/<table>.csv} per the PLAN.md §11 rules. Rows are formatted as they arrive
 * (a compact line is kept instead of the record), then sorted by primary key and written on {@link #close()}.
 * A duplicate primary key is a generator bug and fails the run.
 */
public final class CsvRowSink implements RowSink {

    private record Line(String key, String text) {
    }

    private final Path directory;
    private final Map<String, TableBuffer> tables = new TreeMap<>();
    private boolean closed;

    public CsvRowSink(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void declare(TableSpec table) {
        requireOpen();
        tables.computeIfAbsent(table.name(), n -> new TableBuffer(table));
    }

    @Override
    public void accept(TableSpec table, Record row) {
        requireOpen();
        TableBuffer buffer = tables.computeIfAbsent(table.name(), n -> new TableBuffer(table));
        String[] fields = CsvSupport.formatRow(table, row);
        buffer.lines.add(new Line(CsvSupport.primaryKey(table, fields), CsvSupport.formatLine(fields)));
    }

    @Override
    public long count(TableSpec table) {
        TableBuffer buffer = tables.get(table.name());
        return buffer == null ? 0 : buffer.lines.size();
    }

    @Override
    public boolean isDeclared(TableSpec table) {
        return tables.containsKey(table.name());
    }

    /** Path of the file a table is written to. */
    public Path fileFor(TableSpec table) {
        return directory.resolve(table.fileName());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (TableBuffer buffer : tables.values()) {
            List<Line> lines = buffer.lines;
            lines.sort(Comparator.comparing(Line::key));
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).key().equals(lines.get(i - 1).key())) {
                    throw new IllegalStateException(buffer.table.name() + ": duplicate primary key "
                            + lines.get(i).key().replace('\u0000', ','));
                }
            }
            CsvSupport.writeLines(fileFor(buffer.table), buffer.table, () -> lines.stream().map(Line::text).iterator());
            lines.clear();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("sink is closed");
        }
    }

    private static final class TableBuffer {
        private final TableSpec table;
        private final List<Line> lines = new ArrayList<>();

        TableBuffer(TableSpec table) {
            this.table = table;
        }
    }
}
