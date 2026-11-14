package com.pricingsim.persistence;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pricingsim.io.CsvDatasetReader;
import com.pricingsim.io.TableRegistry;
import com.pricingsim.io.TableSpec;

/**
 * Loads a generated dataset's CSV files into the database (PLAN.md §2 trade-offs, §3 persistence/BulkLoader).
 *
 * <ul>
 *   <li>Everything happens in one transaction: either the whole dataset is loaded or nothing changes.</li>
 *   <li>First every data table and every pipeline output table is emptied (PostgreSQL {@code TRUNCATE … CASCADE}; H2
 *       {@code DELETE} in reverse FK order), so a reload is idempotent.</li>
 *   <li>Then the tables are loaded in {@link TableRegistry} FK-safe order with {@code JdbcTemplate.batchUpdate} in
 *       batches of 5,000 ({@code reWriteBatchedInserts=true} in the PostgreSQL URL turns them into multi-row inserts).
 *       Rows are streamed from CSV, so memory stays flat.</li>
 *   <li>Only the {@link TableRegistry} tables are loaded, from {@code csv/}. {@code labels/} is never read.</li>
 * </ul>
 */
public final class BulkLoader {

    public static final int BATCH_SIZE = 5_000;
    /** Pipeline output tables, emptied together with the data (children first). */
    static final List<String> OUTPUT_TABLES = List.of("detected_exception", "validation_result", "pipeline_run");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final Dialect dialect;

    public BulkLoader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.dialect = Dialect.of(dataSource);
    }

    /** Replaces the database contents with the dataset in {@code datasetDir}; returns rows loaded per table. */
    public Map<String, Long> load(Path datasetDir) {
        CsvDatasetReader reader = new CsvDatasetReader(datasetDir);
        for (TableSpec table : TableRegistry.loadOrder()) {
            if (!reader.exists(table)) {
                throw new IllegalArgumentException("Missing " + reader.fileFor(table) + " (run generate first)");
            }
        }
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return tx.execute(status -> {
            clear();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (TableSpec table : TableRegistry.loadOrder()) {
                counts.put(table.name(), loadTable(reader, table));
            }
            return counts;
        });
    }

    /** Empties every data and output table. */
    public void clear() {
        List<String> tables = new ArrayList<>(OUTPUT_TABLES);
        TableRegistry.truncateOrder().forEach(t -> tables.add(t.name()));
        if (dialect == Dialect.POSTGRES) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        } else {
            tables.forEach(t -> jdbc.execute("DELETE FROM " + t));
        }
    }

    private long loadTable(CsvDatasetReader reader, TableSpec table) {
        String sql = "INSERT INTO " + table.name() + " (" + String.join(", ", table.columnNames()) + ") VALUES ("
                + table.columns().stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
        List<TableSpec.Column> columns = table.columns();
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        long rows = reader.forEachTyped(table, values -> {
            batch.add(values);
            if (batch.size() == BATCH_SIZE) {
                flush(sql, columns, batch);
            }
        });
        flush(sql, columns, batch);
        return rows;
    }

    private void flush(String sql, List<TableSpec.Column> columns, List<Object[]> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(sql, batch, batch.size(), (PreparedStatement ps, Object[] row) -> {
            for (int i = 0; i < row.length; i++) {
                bind(ps, i + 1, columns.get(i).type(), row[i]);
            }
        });
        batch.clear();
    }

    static void bind(PreparedStatement ps, int index, TableSpec.ColumnType type, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, sqlType(type));
            return;
        }
        switch (type) {
            case VARCHAR, CHAR, TEXT -> ps.setString(index, (String) value);
            case INTEGER -> ps.setInt(index, Math.toIntExact((Long) value));
            case BIGINT -> ps.setLong(index, (Long) value);
            case NUMERIC -> ps.setBigDecimal(index, (java.math.BigDecimal) value);
            case BOOLEAN -> ps.setBoolean(index, (Boolean) value);
            case DATE, TIMESTAMPTZ -> ps.setObject(index, value);
        }
    }

    static int sqlType(TableSpec.ColumnType type) {
        return switch (type) {
            case VARCHAR, TEXT -> Types.VARCHAR;
            case CHAR -> Types.CHAR;
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case NUMERIC -> Types.NUMERIC;
            case BOOLEAN -> Types.BOOLEAN;
            case DATE -> Types.DATE;
            case TIMESTAMPTZ -> Types.TIMESTAMP_WITH_TIMEZONE;
        };
    }

    /** Rows per data table currently in the database. */
    public Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (TableSpec table : TableRegistry.loadOrder()) {
            out.put(table.name(), jdbc.queryForObject("SELECT COUNT(*) FROM " + table.name(), Long.class));
        }
        return out;
    }

    public Dialect dialect() {
        return dialect;
    }
}
