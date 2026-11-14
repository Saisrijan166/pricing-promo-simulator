package com.pricingsim.io;

import static com.pricingsim.io.TableSpec.ColumnType.BOOLEAN;
import static com.pricingsim.io.TableSpec.ColumnType.CHAR;
import static com.pricingsim.io.TableSpec.ColumnType.DATE;
import static com.pricingsim.io.TableSpec.ColumnType.INTEGER;
import static com.pricingsim.io.TableSpec.ColumnType.NUMERIC;
import static com.pricingsim.io.TableSpec.ColumnType.TEXT;
import static com.pricingsim.io.TableSpec.ColumnType.TIMESTAMPTZ;
import static com.pricingsim.io.TableSpec.ColumnType.VARCHAR;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.pricingsim.domain.enums.ActorRole;
import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.Category;
import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.CostChangeReason;
import com.pricingsim.domain.enums.CustomerSegment;
import com.pricingsim.domain.enums.DecisionSource;
import com.pricingsim.domain.enums.DecisionStatus;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.DecisionType;
import com.pricingsim.domain.enums.FundingSource;
import com.pricingsim.domain.enums.ObservationSource;
import com.pricingsim.domain.enums.ObservationUsage;
import com.pricingsim.domain.enums.OrderStatus;
import com.pricingsim.domain.enums.PricePosition;
import com.pricingsim.domain.enums.ProductStatus;
import com.pricingsim.domain.enums.PromoType;
import com.pricingsim.domain.enums.PromotionStatus;
import com.pricingsim.domain.enums.ReasonCode;
import com.pricingsim.domain.enums.RuleType;
import com.pricingsim.domain.enums.StackGroup;
import com.pricingsim.domain.enums.Uom;
import com.pricingsim.domain.id.IdPatterns;
import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.domain.model.CustomerOrder;
import com.pricingsim.domain.model.InventorySnapshot;
import com.pricingsim.domain.model.OrderPromotion;
import com.pricingsim.domain.model.PriceDecision;
import com.pricingsim.domain.model.PriceDecisionObservation;
import com.pricingsim.domain.model.PriceDecisionPromotion;
import com.pricingsim.domain.model.PriceDecisionStatusEvent;
import com.pricingsim.domain.model.PriceHistory;
import com.pricingsim.domain.model.Product;
import com.pricingsim.domain.model.ProductStatusEvent;
import com.pricingsim.domain.model.Promotion;
import com.pricingsim.domain.model.PromotionProduct;
import com.pricingsim.domain.model.PromotionRule;
import com.pricingsim.domain.model.PromotionStatusEvent;

/**
 * All generator data tables (9 entities + 4 bridges + 3 event histories, PLAN.md §4.1) in FK-safe load order.
 * Pipeline output tables (detected_exception, pipeline_run, validation_result) are not data tables and are not here.
 * Ground-truth labels are never a table here, so nothing driven by this registry can load or validate labels as features.
 *
 * <p>Must stay identical to the Flyway migrations; {@code TableRegistryTest} enforces that.
 */
public final class TableRegistry {

    public static final TableSpec PRODUCT = TableSpec.builder("product", Product.class)
            .req("sku_id", VARCHAR)
            .req("product_name", VARCHAR)
            .req("brand", VARCHAR)
            .req("category", VARCHAR)
            .req("subcategory", VARCHAR)
            .req("uom", VARCHAR)
            .req("pack_size", INTEGER)
            .req("status", VARCHAR)
            .req("launch_date", DATE)
            .opt("discontinued_date", DATE)
            .req("mrp", NUMERIC)
            .opt("map_price", NUMERIC)
            .req("min_margin_pct", NUMERIC)
            .req("min_promo_margin_pct", NUMERIC)
            .req("price_position", VARCHAR)
            .req("competitor_tracked", BOOLEAN)
            .req("created_at", TIMESTAMPTZ)
            .pk("sku_id")
            .enumColumn("category", Category.class)
            .enumColumn("uom", Uom.class)
            .enumColumn("status", ProductStatus.class)
            .enumColumn("price_position", PricePosition.class)
            .id("sku_id", IdPatterns.SKU)
            .build();

    public static final TableSpec PRODUCT_STATUS_EVENT = TableSpec.builder("product_status_event", ProductStatusEvent.class)
            .req("event_id", VARCHAR)
            .req("sku_id", VARCHAR)
            .opt("from_status", VARCHAR)
            .req("to_status", VARCHAR)
            .req("event_ts", TIMESTAMPTZ)
            .req("actor_role", VARCHAR)
            .opt("note", VARCHAR)
            .pk("event_id")
            .fk("sku_id", "product", "sku_id")
            .enumColumn("from_status", ProductStatus.class)
            .enumColumn("to_status", ProductStatus.class)
            .enumColumn("actor_role", ActorRole.class)
            .id("event_id", IdPatterns.PRODUCT_EVENT)
            .id("sku_id", IdPatterns.SKU)
            .build();

    public static final TableSpec COST_RECORD = TableSpec.builder("cost_record", CostRecord.class)
            .req("cost_record_id", VARCHAR)
            .req("sku_id", VARCHAR)
            .req("cost_month", CHAR)
            .req("effective_from", DATE)
            .opt("effective_to", DATE)
            .req("unit_cost", NUMERIC)
            .req("freight_cost", NUMERIC)
            .req("landed_cost", NUMERIC)
            .opt("change_pct", NUMERIC)
            .req("change_reason", VARCHAR)
            .pk("cost_record_id")
            .fk("sku_id", "product", "sku_id")
            .enumColumn("change_reason", CostChangeReason.class)
            .id("cost_record_id", IdPatterns.COST_RECORD)
            .id("sku_id", IdPatterns.SKU)
            .build();

    public static final TableSpec PROMOTION = TableSpec.builder("promotion", Promotion.class)
            .req("promotion_id", VARCHAR)
            .req("promotion_name", VARCHAR)
            .req("promo_type", VARCHAR)
            .req("stack_group", VARCHAR)
            .opt("discount_pct", NUMERIC)
            .opt("discount_amount", NUMERIC)
            .opt("coupon_code", VARCHAR)
            .req("start_date", DATE)
            .req("end_date", DATE)
            .req("priority", INTEGER)
            .req("channel", VARCHAR)
            .req("funding_source", VARCHAR)
            .req("status", VARCHAR)
            .req("created_at", TIMESTAMPTZ)
            .pk("promotion_id")
            .enumColumn("promo_type", PromoType.class)
            .enumColumn("stack_group", StackGroup.class)
            .enumColumn("channel", Channel.class)
            .enumColumn("funding_source", FundingSource.class)
            .enumColumn("status", PromotionStatus.class)
            .id("promotion_id", IdPatterns.PROMOTION)
            .id("coupon_code", IdPatterns.COUPON)
            .build();

    public static final TableSpec PROMOTION_RULE = TableSpec.builder("promotion_rule", PromotionRule.class)
            .req("rule_id", VARCHAR)
            .req("promotion_id", VARCHAR)
            .req("rule_type", VARCHAR)
            .opt("target_promotion_id", VARCHAR)
            .opt("param_numeric", NUMERIC)
            .req("effective_from", DATE)
            .req("effective_to", DATE)
            .pk("rule_id")
            .fk("promotion_id", "promotion", "promotion_id")
            .fk("target_promotion_id", "promotion", "promotion_id")
            .enumColumn("rule_type", RuleType.class)
            .id("rule_id", IdPatterns.RULE)
            .id("promotion_id", IdPatterns.PROMOTION)
            .id("target_promotion_id", IdPatterns.PROMOTION)
            .build();

    public static final TableSpec PROMOTION_PRODUCT = TableSpec.builder("promotion_product", PromotionProduct.class)
            .req("promotion_id", VARCHAR)
            .req("sku_id", VARCHAR)
            .req("added_at", TIMESTAMPTZ)
            .pk("promotion_id", "sku_id")
            .fk("promotion_id", "promotion", "promotion_id")
            .fk("sku_id", "product", "sku_id")
            .id("promotion_id", IdPatterns.PROMOTION)
            .id("sku_id", IdPatterns.SKU)
            .build();

    public static final TableSpec PROMOTION_STATUS_EVENT = TableSpec.builder("promotion_status_event", PromotionStatusEvent.class)
            .req("event_id", VARCHAR)
            .req("promotion_id", VARCHAR)
            .opt("from_status", VARCHAR)
            .req("to_status", VARCHAR)
            .req("event_ts", TIMESTAMPTZ)
            .req("actor_role", VARCHAR)
            .opt("note", VARCHAR)
            .pk("event_id")
            .fk("promotion_id", "promotion", "promotion_id")
            .enumColumn("from_status", PromotionStatus.class)
            .enumColumn("to_status", PromotionStatus.class)
            .enumColumn("actor_role", ActorRole.class)
            .id("event_id", IdPatterns.PROMOTION_EVENT)
            .id("promotion_id", IdPatterns.PROMOTION)
            .build();

    public static final TableSpec COMPETITOR_OBSERVATION = TableSpec.builder("competitor_observation", CompetitorObservation.class)
            .req("observation_id", VARCHAR)
            .req("sku_id", VARCHAR)
            .req("competitor", VARCHAR)
            .req("observed_price", NUMERIC)
            .req("source_captured_at", TIMESTAMPTZ)
            .req("ingested_at", TIMESTAMPTZ)
            .req("source_type", VARCHAR)
            .req("competitor_in_stock", BOOLEAN)
            .req("match_confidence", NUMERIC)
            .pk("observation_id")
            .fk("sku_id", "product", "sku_id")
            .enumColumn("competitor", Competitor.class)
            .enumColumn("source_type", ObservationSource.class)
            .id("observation_id", IdPatterns.OBSERVATION)
            .id("sku_id", IdPatterns.SKU)
            .build();

    public static final TableSpec PRICE_DECISION = TableSpec.builder("price_decision", PriceDecision.class)
            .req("decision_id", VARCHAR)
            .req("sku_id", VARCHAR)
            .req("decision_ts", TIMESTAMPTZ)
            .req("effective_date", DATE)
            .req("trigger", VARCHAR)
            .req("decision_source", VARCHAR)
            .opt("previous_decision_id", VARCHAR)
            .req("cost_record_id", VARCHAR)
            .req("landed_cost", NUMERIC)
            .opt("prev_list_price", NUMERIC)
            .req("floor_price", NUMERIC)
            .req("ceiling_price", NUMERIC)
            .opt("competitor_ref_price", NUMERIC)
            .req("competitor_fresh_count", INTEGER)
            .req("competitor_stale_count", INTEGER)
            .req("on_hand_units", INTEGER)
            .req("demand_7d_units", INTEGER)
            .req("demand_28d_units", INTEGER)
            .req("velocity_ratio", NUMERIC)
            .req("days_of_cover", NUMERIC)
            .req("target_price", NUMERIC)
            .req("final_list_price", NUMERIC)
            .req("markdown_discount_pct", NUMERIC)
            .req("expected_net_price", NUMERIC)
            .req("margin_pct", NUMERIC)
            .req("required_margin_pct", NUMERIC)
            .req("decision_type", VARCHAR)
            .req("primary_reason_code", VARCHAR)
            .req("reason_codes", VARCHAR)
            .req("explanation", TEXT)
            .req("rule_version", VARCHAR)
            .req("status", VARCHAR)
            .pk("decision_id")
            .fk("sku_id", "product", "sku_id")
            .fk("previous_decision_id", "price_decision", "decision_id")
            .fk("cost_record_id", "cost_record", "cost_record_id")
            .enumColumn("trigger", DecisionTrigger.class)
            .enumColumn("decision_source", DecisionSource.class)
            .enumColumn("decision_type", DecisionType.class)
            .enumColumn("primary_reason_code", ReasonCode.class)
            .enumColumn("status", DecisionStatus.class)
            .id("decision_id", IdPatterns.DECISION)
            .id("sku_id", IdPatterns.SKU)
            .id("previous_decision_id", IdPatterns.DECISION)
            .id("cost_record_id", IdPatterns.COST_RECORD)
            .build();

    public static final TableSpec PRICE_DECISION_PROMOTION = TableSpec.builder("price_decision_promotion", PriceDecisionPromotion.class)
            .req("decision_id", VARCHAR)
            .req("promotion_id", VARCHAR)
            .req("application_status", VARCHAR)
            .req("reason_code", VARCHAR)
            .req("discount_pct_effective", NUMERIC)
            .pk("decision_id", "promotion_id")
            .fk("decision_id", "price_decision", "decision_id")
            .fk("promotion_id", "promotion", "promotion_id")
            .enumColumn("application_status", ApplicationStatus.class)
            .enumColumn("reason_code", ReasonCode.class)
            .id("decision_id", IdPatterns.DECISION)
            .id("promotion_id", IdPatterns.PROMOTION)
            .build();

    public static final TableSpec PRICE_DECISION_OBSERVATION = TableSpec.builder("price_decision_observation", PriceDecisionObservation.class)
            .req("decision_id", VARCHAR)
            .req("observation_id", VARCHAR)
            .req("usage", VARCHAR)
            .req("age_hours", NUMERIC)
            .pk("decision_id", "observation_id")
            .fk("decision_id", "price_decision", "decision_id")
            .fk("observation_id", "competitor_observation", "observation_id")
            .enumColumn("usage", ObservationUsage.class)
            .id("decision_id", IdPatterns.DECISION)
            .id("observation_id", IdPatterns.OBSERVATION)
            .build();

    public static final TableSpec PRICE_DECISION_STATUS_EVENT = TableSpec.builder("price_decision_status_event", PriceDecisionStatusEvent.class)
            .req("event_id", VARCHAR)
            .req("decision_id", VARCHAR)
            .opt("from_status", VARCHAR)
            .req("to_status", VARCHAR)
            .req("event_ts", TIMESTAMPTZ)
            .req("actor_role", VARCHAR)
            .opt("note", VARCHAR)
            .pk("event_id")
            .fk("decision_id", "price_decision", "decision_id")
            .enumColumn("from_status", DecisionStatus.class)
            .enumColumn("to_status", DecisionStatus.class)
            .enumColumn("actor_role", ActorRole.class)
            .id("event_id", IdPatterns.DECISION_EVENT)
            .id("decision_id", IdPatterns.DECISION)
            .build();

    public static final TableSpec PRICE_HISTORY = TableSpec.builder("price_history", PriceHistory.class)
            .req("price_history_id", VARCHAR)
            .req("sku_id", VARCHAR)
            .req("price_date", DATE)
            .req("list_price", NUMERIC)
            .req("shelf_price", NUMERIC)
            .opt("previous_list_price", NUMERIC)
            .opt("change_reason", VARCHAR)
            .opt("source_decision_id", VARCHAR)
            .req("cost_record_id", VARCHAR)
            .pk("price_history_id")
            .fk("sku_id", "product", "sku_id")
            .fk("source_decision_id", "price_decision", "decision_id")
            .fk("cost_record_id", "cost_record", "cost_record_id")
            .enumColumn("change_reason", ReasonCode.class)
            .id("price_history_id", IdPatterns.PRICE_HISTORY)
            .id("sku_id", IdPatterns.SKU)
            .id("source_decision_id", IdPatterns.DECISION)
            .id("cost_record_id", IdPatterns.COST_RECORD)
            .build();

    public static final TableSpec INVENTORY_SNAPSHOT = TableSpec.builder("inventory_snapshot", InventorySnapshot.class)
            .req("inventory_snapshot_id", VARCHAR)
            .req("sku_id", VARCHAR)
            .req("snapshot_date", DATE)
            .req("opening_on_hand", INTEGER)
            .req("receipts_units", INTEGER)
            .req("units_sold", INTEGER)
            .req("adjustment_units", INTEGER)
            .req("closing_on_hand", INTEGER)
            .req("on_order_units", INTEGER)
            .req("demand_units", INTEGER)
            .req("lost_sales_units", INTEGER)
            .req("stockout_flag", BOOLEAN)
            .req("days_of_cover", NUMERIC)
            .pk("inventory_snapshot_id")
            .fk("sku_id", "product", "sku_id")
            .id("inventory_snapshot_id", IdPatterns.INVENTORY_SNAPSHOT)
            .id("sku_id", IdPatterns.SKU)
            .build();

    public static final TableSpec CUSTOMER_ORDER = TableSpec.builder("customer_order", CustomerOrder.class)
            .req("order_id", VARCHAR)
            .req("order_ts", TIMESTAMPTZ)
            .req("order_date", DATE)
            .req("sku_id", VARCHAR)
            .req("customer_ref", VARCHAR)
            .req("customer_segment", VARCHAR)
            .req("channel", VARCHAR)
            .req("quantity", INTEGER)
            .req("price_history_id", VARCHAR)
            .req("cost_record_id", VARCHAR)
            .req("unit_list_price", NUMERIC)
            .req("gross_amount", NUMERIC)
            .req("discount_amount", NUMERIC)
            .req("net_amount", NUMERIC)
            .req("unit_landed_cost", NUMERIC)
            .req("gross_margin_amount", NUMERIC)
            .req("order_status", VARCHAR)
            .pk("order_id")
            .fk("sku_id", "product", "sku_id")
            .fk("price_history_id", "price_history", "price_history_id")
            .fk("cost_record_id", "cost_record", "cost_record_id")
            .enumColumn("customer_segment", CustomerSegment.class)
            .enumColumn("channel", Channel.ECOM, Channel.STORE)
            .enumColumn("order_status", OrderStatus.class)
            .id("order_id", IdPatterns.ORDER)
            .id("sku_id", IdPatterns.SKU)
            .id("customer_ref", IdPatterns.CUSTOMER)
            .id("price_history_id", IdPatterns.PRICE_HISTORY)
            .id("cost_record_id", IdPatterns.COST_RECORD)
            .build();

    public static final TableSpec ORDER_PROMOTION = TableSpec.builder("order_promotion", OrderPromotion.class)
            .req("order_id", VARCHAR)
            .req("promotion_id", VARCHAR)
            .req("sequence_no", INTEGER)
            .req("coupon_presented", BOOLEAN)
            .req("application_status", VARCHAR)
            .opt("rejection_reason", VARCHAR)
            .req("discount_amount", NUMERIC)
            .pk("order_id", "promotion_id")
            .fk("order_id", "customer_order", "order_id")
            .fk("promotion_id", "promotion", "promotion_id")
            .enumColumn("application_status", ApplicationStatus.class)
            .enumColumn("rejection_reason", ReasonCode.class)
            .id("order_id", IdPatterns.ORDER)
            .id("promotion_id", IdPatterns.PROMOTION)
            .build();

    /** FK-safe load order: every table appears after all tables it references (self-references excepted). */
    private static final List<TableSpec> LOAD_ORDER = List.of(
            PRODUCT,
            PRODUCT_STATUS_EVENT,
            COST_RECORD,
            PROMOTION,
            PROMOTION_RULE,
            PROMOTION_PRODUCT,
            PROMOTION_STATUS_EVENT,
            COMPETITOR_OBSERVATION,
            PRICE_DECISION,
            PRICE_DECISION_PROMOTION,
            PRICE_DECISION_OBSERVATION,
            PRICE_DECISION_STATUS_EVENT,
            PRICE_HISTORY,
            INVENTORY_SNAPSHOT,
            CUSTOMER_ORDER,
            ORDER_PROMOTION);

    static {
        verifyLoadOrder(LOAD_ORDER);
    }

    private TableRegistry() {
    }

    /** All data tables in FK-safe load order. */
    public static List<TableSpec> loadOrder() {
        return LOAD_ORDER;
    }

    /** Reverse of {@link #loadOrder()}: children before parents (for deletes/truncates). */
    public static List<TableSpec> truncateOrder() {
        List<TableSpec> reversed = new ArrayList<>(LOAD_ORDER);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    public static Optional<TableSpec> find(String tableName) {
        return LOAD_ORDER.stream().filter(t -> t.name().equals(tableName)).findFirst();
    }

    public static TableSpec byName(String tableName) {
        return find(tableName).orElseThrow(() -> new IllegalArgumentException("Unknown data table: " + tableName));
    }

    /** Throws if some table references a table that is loaded after it (or not registered at all). */
    static void verifyLoadOrder(List<TableSpec> order) {
        Set<String> loaded = new HashSet<>();
        for (TableSpec table : order) {
            for (TableSpec.ForeignKey fk : table.foreignKeys()) {
                if (!fk.refTable().equals(table.name()) && !loaded.contains(fk.refTable())) {
                    throw new IllegalStateException("Load order violation: " + table.name() + "." + fk.column()
                            + " references " + fk.refTable() + ", which is not loaded earlier");
                }
            }
            if (!loaded.add(table.name())) {
                throw new IllegalStateException("Duplicate table in load order: " + table.name());
            }
        }
    }
}
