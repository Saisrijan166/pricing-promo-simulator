package com.pricingsim.generator.sink;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pricingsim.io.TableRegistry;
import com.pricingsim.io.TableSpec;

/**
 * Destination for generated rows. Generators emit rows in whatever order they produce them; a sink presents each
 * table sorted by primary key (PLAN.md §11), so the output never depends on emission order.
 */
public interface RowSink extends AutoCloseable {

    /** Registers a table that this run produces, so it is written (header only) even if it gets no rows. */
    void declare(TableSpec table);

    /** Adds one row; the row's type must be {@code table.recordType()}. Implicitly declares the table. */
    void accept(TableSpec table, Record row);

    /** Rows accepted so far for {@code table} (0 if never declared). */
    long count(TableSpec table);

    /** Row counts for every declared table, in {@link TableRegistry} load order. */
    default Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TableSpec table : TableRegistry.loadOrder()) {
            if (isDeclared(table)) {
                counts.put(table.name(), count(table));
            }
        }
        return counts;
    }

    boolean isDeclared(TableSpec table);

    /** Finishes the output (for files: sorts and writes them). Accepting rows after close is an error. */
    @Override
    void close();
}
