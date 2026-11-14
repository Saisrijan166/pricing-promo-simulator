package com.pricingsim.validation.checks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.pricingsim.io.TableRegistry;
import com.pricingsim.io.TableSpec;
import com.pricingsim.persistence.Dialect;
import com.pricingsim.validation.CheckCategory;
import com.pricingsim.validation.ValidationCheck;

/**
 * PRIMARY_KEY, FOREIGN_KEY, ENUM and ID_FORMAT checks generated from {@link TableRegistry} metadata (PLAN.md §10), so
 * no table, key, enum column or ID column can be missed. The database also enforces PK/FK/enum constraints; these
 * checks prove the data independently (they catch a dataset loaded without constraints, and the fixtures in
 * {@code ValidationRunnerIT} drop the constraint to show it).
 */
public final class RegistryChecks {

    private RegistryChecks() {
    }

    /** SQL expression for a table's primary key as one string ({@code a || '|' || b} for composite keys). */
    public static String pkExpr(TableSpec t, String alias) {
        String prefix = alias == null ? "" : alias + ".";
        return t.primaryKey().stream().map(c -> prefix + c).collect(Collectors.joining(" || '|' || "));
    }

    public static List<ValidationCheck> primaryKeys() {
        List<ValidationCheck> out = new ArrayList<>();
        for (TableSpec t : TableRegistry.loadOrder()) {
            String cols = String.join(", ", t.primaryKey());
            String nulls = t.primaryKey().stream().map(c -> c + " IS NULL").collect(Collectors.joining(" OR "));
            out.add(new ValidationCheck.Sql(CheckCategory.PRIMARY_KEY, t.name(),
                    "Primary key (" + cols + ") is unique and not null",
                    "SELECT " + pkExpr(t, null) + " AS id FROM " + t.name() + " GROUP BY " + cols
                            + " HAVING COUNT(*) > 1 OR " + nulls));
        }
        return out;
    }

    public static List<ValidationCheck> foreignKeys() {
        List<ValidationCheck> out = new ArrayList<>();
        for (TableSpec t : TableRegistry.loadOrder()) {
            for (TableSpec.ForeignKey fk : t.foreignKeys()) {
                out.add(new ValidationCheck.Sql(CheckCategory.FOREIGN_KEY, t.name(),
                        t.name() + "." + fk.column() + " references " + fk.refTable() + "." + fk.refColumn(),
                        "SELECT " + pkExpr(t, "c") + " AS id FROM " + t.name() + " c LEFT JOIN " + fk.refTable()
                                + " p ON p." + fk.refColumn() + " = c." + fk.column() + " WHERE c." + fk.column()
                                + " IS NOT NULL AND p." + fk.refColumn() + " IS NULL"));
            }
        }
        return out;
    }

    public static List<ValidationCheck> enums() {
        List<ValidationCheck> out = new ArrayList<>();
        for (TableSpec t : TableRegistry.loadOrder()) {
            for (Map.Entry<String, List<String>> e : t.enumColumns().entrySet()) {
                String values = e.getValue().stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
                out.add(new ValidationCheck.Sql(CheckCategory.ENUM, t.name(),
                        t.name() + "." + e.getKey() + " is one of " + e.getValue().size() + " allowed values",
                        "SELECT " + pkExpr(t, null) + " AS id FROM " + t.name() + " WHERE " + e.getKey()
                                + " IS NOT NULL AND " + e.getKey() + " NOT IN (" + values + ")"));
            }
        }
        return out;
    }

    public static List<ValidationCheck> idFormats(Dialect dialect) {
        List<ValidationCheck> out = new ArrayList<>();
        for (TableSpec t : TableRegistry.loadOrder()) {
            for (Map.Entry<String, Pattern> e : t.idColumns().entrySet()) {
                out.add(new ValidationCheck.Sql(CheckCategory.ID_FORMAT, t.name(),
                        t.name() + "." + e.getKey() + " matches " + e.getValue().pattern(),
                        "SELECT " + pkExpr(t, null) + " AS id FROM " + t.name() + " WHERE " + e.getKey()
                                + " IS NOT NULL AND NOT (" + dialect.regexMatch(e.getKey(), e.getValue().pattern())
                                + ")"));
            }
        }
        // ID-embedded field consistency (PLAN.md §6 rule 6).
        out.add(sql(CheckCategory.ID_FORMAT, "price_history", "PRC id embeds sku_id and price_date",
                "SELECT price_history_id AS id FROM price_history WHERE price_history_id <> 'PRC-' || sku_id || '-'"
                        + " || TO_CHAR(price_date, 'YYYYMMDD')"));
        out.add(sql(CheckCategory.ID_FORMAT, "inventory_snapshot", "INV id embeds sku_id and snapshot_date",
                "SELECT inventory_snapshot_id AS id FROM inventory_snapshot WHERE inventory_snapshot_id <> 'INV-'"
                        + " || sku_id || '-' || TO_CHAR(snapshot_date, 'YYYYMMDD')"));
        out.add(sql(CheckCategory.ID_FORMAT, "cost_record", "COST id embeds sku_id and cost_month",
                "SELECT cost_record_id AS id FROM cost_record WHERE cost_record_id <> 'COST-' || sku_id || '-'"
                        + " || cost_month"));
        out.add(sql(CheckCategory.ID_FORMAT, "customer_order", "Order price_history_id embeds its sku_id and order_date",
                "SELECT order_id AS id FROM customer_order WHERE price_history_id <> 'PRC-' || sku_id || '-'"
                        + " || TO_CHAR(order_date, 'YYYYMMDD')"));
        return out;
    }

    /** Natural keys other than the primary key (PLAN.md §10 DUPLICATE). */
    public static List<ValidationCheck> duplicates() {
        List<ValidationCheck> out = new ArrayList<>();
        out.add(dup("price_history", "sku_id, price_date"));
        out.add(dup("inventory_snapshot", "sku_id, snapshot_date"));
        out.add(dup("price_decision", "sku_id, effective_date"));
        out.add(dup("competitor_observation", "sku_id, competitor, source_captured_at"));
        out.add(dup("cost_record", "sku_id, cost_month"));
        out.add(dup("promotion", "coupon_code"));
        out.add(dup("promotion_rule", "promotion_id, rule_type, target_promotion_id"));
        return out;
    }

    private static ValidationCheck dup(String table, String columns) {
        String first = columns.split(",")[0].trim();
        String expr = java.util.Arrays.stream(columns.split(",")).map(String::trim)
                .map(c -> "COALESCE(CAST(" + c + " AS VARCHAR(64)), '')").collect(Collectors.joining(" || '|' || "));
        String notNull = table.equals("promotion") ? " WHERE " + first + " IS NOT NULL" : "";
        return sql(CheckCategory.DUPLICATE, table, "Natural key (" + columns + ") is unique",
                "SELECT " + expr + " AS id FROM " + table + notNull + " GROUP BY " + columns + " HAVING COUNT(*) > 1");
    }

    static ValidationCheck sql(CheckCategory category, String table, String description, String sql) {
        return new ValidationCheck.Sql(category, table, description, sql);
    }
}
