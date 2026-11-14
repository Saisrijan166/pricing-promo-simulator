package com.pricingsim.generator.sink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.pricingsim.io.CsvSupport;
import com.pricingsim.io.TableSpec;

/**
 * Keeps generated records in memory, for tests and in-process checks. {@link #rows} returns a table sorted by primary
 * key, exactly the order a {@link CsvRowSink} writes, and fails on a duplicate primary key.
 */
public final class InMemoryRowSink implements RowSink {

    private final Map<String, List<Record>> tables = new TreeMap<>();
    private final Map<String, TableSpec> specs = new TreeMap<>();
    private boolean closed;

    @Override
    public void declare(TableSpec table) {
        requireOpen();
        specs.putIfAbsent(table.name(), table);
        tables.computeIfAbsent(table.name(), n -> new ArrayList<>());
    }

    @Override
    public void accept(TableSpec table, Record row) {
        requireOpen();
        if (row.getClass() != table.recordType()) {
            throw new IllegalArgumentException(table.name() + " expects " + table.recordType().getSimpleName());
        }
        declare(table);
        tables.get(table.name()).add(row);
    }

    @Override
    public long count(TableSpec table) {
        List<Record> rows = tables.get(table.name());
        return rows == null ? 0 : rows.size();
    }

    @Override
    public boolean isDeclared(TableSpec table) {
        return tables.containsKey(table.name());
    }

    /** Rows of {@code table} sorted by primary key (as written to CSV). */
    @SuppressWarnings("unchecked")
    public <R extends Record> List<R> rows(TableSpec table) {
        List<Record> rows = tables.get(table.name());
        if (rows == null) {
            return List.of();
        }
        record Keyed(String key, Record row) {
        }
        List<Keyed> keyed = new ArrayList<>(rows.size());
        for (Record row : rows) {
            keyed.add(new Keyed(CsvSupport.primaryKey(table, CsvSupport.formatRow(table, row)), row));
        }
        keyed.sort(Comparator.comparing(Keyed::key));
        for (int i = 1; i < keyed.size(); i++) {
            if (keyed.get(i).key().equals(keyed.get(i - 1).key())) {
                throw new IllegalStateException(table.name() + ": duplicate primary key " + keyed.get(i).key());
            }
        }
        return keyed.stream().map(k -> (R) k.row()).toList();
    }

    @Override
    public void close() {
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("sink is closed");
        }
    }
}
