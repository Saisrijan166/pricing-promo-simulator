package com.pricingsim.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies Flyway V1–V5 to H2 (PostgreSQL mode) through the real Spring Boot context and checks that all
 * 19 tables, their primary keys, foreign keys and enum CHECK constraints exist — and that business rules are
 * deliberately NOT enforced by the database (PLAN.md §2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class FlywayMigrationIT {

    static final Set<String> EXPECTED_TABLES = Set.of(
            // 9 core entities
            "product", "cost_record", "price_history", "competitor_observation", "inventory_snapshot",
            "promotion", "promotion_rule", "customer_order", "price_decision",
            // 4 bridges
            "promotion_product", "order_promotion", "price_decision_promotion", "price_decision_observation",
            // 3 event histories
            "product_status_event", "promotion_status_event", "price_decision_status_event",
            // 3 pipeline outputs
            "detected_exception", "pipeline_run", "validation_result");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void appliesAllFiveMigrationsSuccessfully() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT \"version\", \"success\" FROM flyway_schema_history WHERE \"version\" IS NOT NULL ORDER BY \"installed_rank\"");
        assertThat(rows).extracting(r -> r.get("version")).containsExactly("1", "2", "3", "4", "5");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(true));
    }

    @Test
    void createsExactlyTheNineteenApplicationTables() {
        Set<String> tables = new TreeSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
                        + " AND table_name <> 'flyway_schema_history'", String.class));
        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void everyTableHasAPrimaryKey() {
        Set<String> withPk = new TreeSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.table_constraints WHERE table_schema = 'public'"
                        + " AND constraint_type = 'PRIMARY KEY'", String.class));
        assertThat(withPk).containsAll(EXPECTED_TABLES);
    }

    @Test
    void createsAllForeignKeysAndEnumChecks() {
        Integer fks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = 'public'"
                        + " AND constraint_type = 'FOREIGN KEY'", Integer.class);
        Integer checks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = 'public'"
                        + " AND constraint_type = 'CHECK' AND constraint_name LIKE 'ck\\_%' ESCAPE '\\'", Integer.class);
        // 25 FKs among the 16 data tables + 3 in the output tables.
        assertThat(fks).isEqualTo(28);
        // 36 enum-domain CHECKs on the 16 data tables + 6 on the output tables.
        assertThat(checks).isEqualTo(42);
    }

    @Test
    void rejectsValuesOutsideAnEnumDomain() {
        insertProduct("SKU-000001", "GROCERY");
        assertThatThrownBy(() -> insertProduct("SKU-000002", "FURNITURE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOrphanForeignKeys() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO cost_record (cost_record_id, sku_id, cost_month, effective_from, unit_cost, freight_cost,"
                        + " landed_cost, change_reason) VALUES ('COST-SKU-999999-2026-01', 'SKU-999999', '2026-01',"
                        + " DATE '2026-01-01', 100.00, 3.00, 103.00, 'INITIAL')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void doesNotEnforceBusinessRulesSoLabelledAnomaliesStayLoadable() {
        insertProduct("SKU-000001", "GROCERY");
        jdbc.update("INSERT INTO cost_record (cost_record_id, sku_id, cost_month, effective_from, unit_cost, freight_cost,"
                + " landed_cost, change_reason) VALUES ('COST-SKU-000001-2026-01', 'SKU-000001', '2026-01',"
                + " DATE '2026-01-01', 100.00, 3.00, 103.00, 'INITIAL')");
        // Price below landed cost, no reason and no source decision: a business-rule violation, but structurally valid.
        int inserted = jdbc.update("INSERT INTO price_history (price_history_id, sku_id, price_date, list_price, shelf_price,"
                + " previous_list_price, change_reason, source_decision_id, cost_record_id) VALUES"
                + " ('PRC-SKU-000001-20260105', 'SKU-000001', DATE '2026-01-05', 50.00, 50.00, 129.00, NULL, NULL,"
                + " 'COST-SKU-000001-2026-01')");
        assertThat(inserted).isEqualTo(1);
    }

    private void insertProduct(String skuId, String category) {
        jdbc.update("INSERT INTO product (sku_id, product_name, brand, category, subcategory, uom, pack_size, status,"
                        + " launch_date, mrp, min_margin_pct, min_promo_margin_pct, price_position, competitor_tracked, created_at)"
                        + " VALUES (?, 'Test Brand Item', 'Test Brand', ?, 'Staples', 'EACH', 1, 'ACTIVE', DATE '2025-06-01',"
                        + " 199.00, 0.1500, 0.1000, 'MATCH', TRUE, TIMESTAMP WITH TIME ZONE '2025-05-01 00:00:00+00')",
                skuId, category);
    }
}
