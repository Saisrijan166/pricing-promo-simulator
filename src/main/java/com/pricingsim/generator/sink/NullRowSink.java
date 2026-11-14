package com.pricingsim.generator.sink;

import java.util.Map;
import java.util.TreeMap;

import com.pricingsim.io.TableSpec;

/** Counts rows and discards them; used for the baseline dry run that finds anomaly candidates. */
public final class NullRowSink implements RowSink {

    private final Map<String, Long> counts = new TreeMap<>();

    @Override
    public void declare(TableSpec table) {
        counts.putIfAbsent(table.name(), 0L);
    }

    @Override
    public void accept(TableSpec table, Record row) {
        counts.merge(table.name(), 1L, Long::sum);
    }

    @Override
    public long count(TableSpec table) {
        return counts.getOrDefault(table.name(), 0L);
    }

    @Override
    public boolean isDeclared(TableSpec table) {
        return counts.containsKey(table.name());
    }

    @Override
    public void close() {
    }
}
