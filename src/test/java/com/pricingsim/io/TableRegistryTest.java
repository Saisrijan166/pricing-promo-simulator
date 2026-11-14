package com.pricingsim.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The registry is the single source of table metadata for the CSV writer, loader and validator. This test migrates a
 * fresh H2 database with the real Flyway scripts and asserts the registry matches the schema exactly — columns
 * (order, nullability, type), primary keys, foreign keys and enum CHECK domains — and that each domain record's
 * components map 1:1 to the columns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TableRegistryTest {

    private static final String URL =
            "jdbc:h2:mem:registry_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    private static final Set<String> OUTPUT_TABLES = Set.of("detected_exception", "pipeline_run", "validation_result");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    private Connection connection;
    private DatabaseMetaData meta;

    @BeforeAll
    void migrate() throws SQLException {
        Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration").load().migrate();
        connection = DriverManager.getConnection(URL, "sa", "");
        meta = connection.getMetaData();
    }

    @AfterAll
    void close() throws SQLException {
        connection.close();
    }

    static List<TableSpec> tables() {
        return TableRegistry.loadOrder();
    }

    @Test
    void registersExactlyTheSixteenDataTablesOfTheSchema() throws SQLException {
        Set<String> schemaTables = new TreeSet<>();
        try (ResultSet rs = meta.getTables(null, "public", "%", new String[] {"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                schemaTables.add(rs.getString("TABLE_NAME"));
            }
        }
        schemaTables.remove("flyway_schema_history");
        schemaTables.removeAll(OUTPUT_TABLES);

        assertThat(TableRegistry.loadOrder()).hasSize(16);
        assertThat(TableRegistry.loadOrder()).extracting(TableSpec::name).containsExactlyInAnyOrderElementsOf(schemaTables);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void columnsMatchTheMigratedSchemaInOrderNullabilityAndType(TableSpec spec) throws SQLException {
        List<TableSpec.Column> actual = new ArrayList<>();
        Map<String, Integer> sqlTypes = new TreeMap<>();
        try (ResultSet rs = meta.getColumns(null, "public", spec.name(), "%")) {
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Object[] {rs.getInt("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable, rs.getInt("DATA_TYPE")});
            }
            rows.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
            for (Object[] r : rows) {
                actual.add(new TableSpec.Column((String) r[1], null, (boolean) r[2]));
                sqlTypes.put((String) r[1], (int) r[3]);
            }
        }

        assertThat(actual).extracting(TableSpec.Column::name).as("column order").containsExactlyElementsOf(spec.columnNames());
        for (TableSpec.Column column : spec.columns()) {
            TableSpec.Column db = actual.stream().filter(c -> c.name().equals(column.name())).findFirst().orElseThrow();
            assertThat(db.nullable()).as(spec.name() + "." + column.name() + " nullable").isEqualTo(column.nullable());
            assertThat(compatibleJdbcTypes(column.type()))
                    .as(spec.name() + "." + column.name() + " type " + column.type())
                    .contains(sqlTypes.get(column.name()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void primaryKeyMatches(TableSpec spec) throws SQLException {
        Map<Integer, String> pk = new TreeMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, "public", spec.name())) {
            while (rs.next()) {
                pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        assertThat(List.copyOf(pk.values())).containsExactlyElementsOf(spec.primaryKey());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void foreignKeysMatch(TableSpec spec) throws SQLException {
        Set<TableSpec.ForeignKey> actual = new LinkedHashSet<>();
        try (ResultSet rs = meta.getImportedKeys(null, "public", spec.name())) {
            while (rs.next()) {
                actual.add(new TableSpec.ForeignKey(rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")));
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(spec.foreignKeys());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void enumColumnsMatchCheckConstraintDomains(TableSpec spec) throws SQLException {
        Map<String, Set<String>> dbDomains = new TreeMap<>();
        String sql = "SELECT cc.constraint_name, cc.check_clause FROM information_schema.check_constraints cc"
                + " JOIN information_schema.table_constraints tc ON tc.constraint_name = cc.constraint_name"
                + " AND tc.constraint_schema = cc.constraint_schema"
                + " WHERE tc.table_schema = 'public' AND tc.table_name = ? AND tc.constraint_type = 'CHECK'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, spec.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String column = rs.getString(1).substring(("ck_" + spec.name() + "_").length());
                    Set<String> values = new TreeSet<>();
                    Matcher m = QUOTED.matcher(rs.getString(2));
                    while (m.find()) {
                        values.add(m.group(1));
                    }
                    dbDomains.put(column, values);
                }
            }
        }
        Map<String, Set<String>> registryDomains = new TreeMap<>();
        spec.enumColumns().forEach((column, values) -> registryDomains.put(column, new TreeSet<>(values)));

        assertThat(dbDomains).isEqualTo(registryDomains);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void recordComponentsMapOneToOneToColumns(TableSpec spec) {
        List<String> components = Arrays.stream(spec.recordType().getRecordComponents())
                .map(c -> toSnakeCase(c.getName()))
                .toList();
        assertThat(components).containsExactlyElementsOf(spec.columnNames());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyKeyColumnHasAnIdFormat(TableSpec spec) {
        for (String pk : spec.primaryKey()) {
            assertThat(spec.idColumns()).as(spec.name() + " PK " + pk).containsKey(pk);
        }
        for (TableSpec.ForeignKey fk : spec.foreignKeys()) {
            assertThat(spec.idColumns()).as(spec.name() + " FK " + fk.column()).containsKey(fk.column());
        }
        assertThat(spec.fileName()).isEqualTo(spec.name() + ".csv");
    }

    @Test
    void loadOrderIsForeignKeySafeAndTruncateOrderIsItsReverse() {
        List<String> order = TableRegistry.loadOrder().stream().map(TableSpec::name).toList();
        for (TableSpec table : TableRegistry.loadOrder()) {
            for (TableSpec.ForeignKey fk : table.foreignKeys()) {
                if (!fk.refTable().equals(table.name())) {
                    assertThat(order.indexOf(fk.refTable()))
                            .as(table.name() + " → " + fk.refTable())
                            .isGreaterThanOrEqualTo(0)
                            .isLessThan(order.indexOf(table.name()));
                }
            }
        }
        assertThat(order.getFirst()).isEqualTo("product");
        assertThat(TableRegistry.truncateOrder().getFirst().name()).isEqualTo(order.getLast());
        assertThat(TableRegistry.byName("price_decision")).isSameAs(TableRegistry.PRICE_DECISION);
    }

    /** demand7dUnits → demand_7d_units, priceHistoryId → price_history_id. */
    static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            char prev = i > 0 ? camel.charAt(i - 1) : 0;
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else if (Character.isDigit(c) && Character.isLetter(prev)) {
                sb.append('_').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Set<Integer> compatibleJdbcTypes(TableSpec.ColumnType type) {
        return switch (type) {
            case VARCHAR -> Set.of(Types.VARCHAR);
            case CHAR -> Set.of(Types.CHAR);
            case INTEGER -> Set.of(Types.INTEGER);
            case BIGINT -> Set.of(Types.BIGINT);
            case NUMERIC -> Set.of(Types.NUMERIC, Types.DECIMAL);
            case BOOLEAN -> Set.of(Types.BOOLEAN, Types.BIT);
            case DATE -> Set.of(Types.DATE);
            case TIMESTAMPTZ -> Set.of(Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP);
            case TEXT -> Set.of(Types.CLOB, Types.VARCHAR, Types.LONGVARCHAR);
        };
    }
}
