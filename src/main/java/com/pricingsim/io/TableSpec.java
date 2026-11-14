package com.pricingsim.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Metadata for one data table (PLAN.md §3 io/). It drives the CSV writer/reader, the bulk loader and the validator, so
 * column order, keys, enum domains and ID formats are defined once.
 *
 * @param name        SQL table name
 * @param fileName    CSV file name inside a dataset's {@code csv/} folder
 * @param recordType  domain record whose components map 1:1 (camelCase ↔ snake_case) to {@link #columns()}
 * @param columns     ordered columns (CSV header order = DDL order)
 * @param primaryKey  PK column(s)
 * @param foreignKeys single-column FKs
 * @param enumColumns enum column → allowed values (declaration order)
 * @param idColumns   ID column → expected format
 */
public record TableSpec(
        String name,
        String fileName,
        Class<? extends Record> recordType,
        List<Column> columns,
        List<String> primaryKey,
        List<ForeignKey> foreignKeys,
        Map<String, List<String>> enumColumns,
        Map<String, Pattern> idColumns) {

    /** Logical column types (mapped to SQL in the Flyway migrations). */
    public enum ColumnType {
        VARCHAR, CHAR, INTEGER, BIGINT, NUMERIC, BOOLEAN, DATE, TIMESTAMPTZ, TEXT
    }

    /** One column; {@code nullable=false} means NOT NULL in the DDL. */
    public record Column(String name, ColumnType type, boolean nullable) {
    }

    /** A single-column foreign key {@code column → refTable(refColumn)}. */
    public record ForeignKey(String column, String refTable, String refColumn) {
    }

    public TableSpec {
        Objects.requireNonNull(name, "name");
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        foreignKeys = List.copyOf(foreignKeys);
        enumColumns = Collections.unmodifiableMap(new LinkedHashMap<>(enumColumns));
        idColumns = Collections.unmodifiableMap(new LinkedHashMap<>(idColumns));
    }

    public List<String> columnNames() {
        return columns.stream().map(Column::name).toList();
    }

    public Optional<Column> column(String columnName) {
        return columns.stream().filter(c -> c.name().equals(columnName)).findFirst();
    }

    public static Builder builder(String name, Class<? extends Record> recordType) {
        return new Builder(name, recordType);
    }

    /** Fluent builder that checks every key/enum/id reference points at a declared column. */
    public static final class Builder {
        private final String name;
        private final Class<? extends Record> recordType;
        private final List<Column> columns = new ArrayList<>();
        private final List<String> primaryKey = new ArrayList<>();
        private final List<ForeignKey> foreignKeys = new ArrayList<>();
        private final Map<String, List<String>> enumColumns = new LinkedHashMap<>();
        private final Map<String, Pattern> idColumns = new LinkedHashMap<>();

        private Builder(String name, Class<? extends Record> recordType) {
            this.name = name;
            this.recordType = recordType;
        }

        /** Required (NOT NULL) column. */
        public Builder req(String column, ColumnType type) {
            columns.add(new Column(column, type, false));
            return this;
        }

        /** Optional (NULLable) column. */
        public Builder opt(String column, ColumnType type) {
            columns.add(new Column(column, type, true));
            return this;
        }

        public Builder pk(String... pkColumns) {
            primaryKey.addAll(Arrays.asList(pkColumns));
            return this;
        }

        public Builder fk(String column, String refTable, String refColumn) {
            foreignKeys.add(new ForeignKey(column, refTable, refColumn));
            return this;
        }

        /** Enum column whose domain is every constant of {@code type}. */
        public Builder enumColumn(String column, Class<? extends Enum<?>> type) {
            enumColumns.put(column, Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
            return this;
        }

        /** Enum column restricted to a subset of constants. */
        public Builder enumColumn(String column, Enum<?>... allowed) {
            enumColumns.put(column, Arrays.stream(allowed).map(Enum::name).toList());
            return this;
        }

        public Builder id(String column, Pattern pattern) {
            idColumns.put(column, pattern);
            return this;
        }

        public TableSpec build() {
            List<String> names = columns.stream().map(Column::name).toList();
            if (names.stream().distinct().count() != names.size()) {
                throw new IllegalStateException(name + ": duplicate column");
            }
            requireColumns("primary key", primaryKey, names);
            requireColumns("foreign key", foreignKeys.stream().map(ForeignKey::column).toList(), names);
            requireColumns("enum", List.copyOf(enumColumns.keySet()), names);
            requireColumns("id", List.copyOf(idColumns.keySet()), names);
            if (primaryKey.isEmpty()) {
                throw new IllegalStateException(name + ": no primary key");
            }
            return new TableSpec(name, name + ".csv", recordType, columns, primaryKey, foreignKeys, enumColumns, idColumns);
        }

        private void requireColumns(String what, List<String> referenced, List<String> declared) {
            for (String c : referenced) {
                if (!declared.contains(c)) {
                    throw new IllegalStateException(name + ": " + what + " references unknown column " + c);
                }
            }
        }
    }
}
