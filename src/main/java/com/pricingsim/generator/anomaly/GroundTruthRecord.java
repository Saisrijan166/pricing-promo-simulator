package com.pricingsim.generator.anomaly;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.pricingsim.domain.enums.IssueType;

/**
 * One labelled anomaly (PLAN.md §9 {@code labels/ground_truth.csv}). Labels are written only to {@code labels/} and
 * never appear in a feature table.
 *
 * @param gtId               {@code GT-YYYY-%06d}, in (issue_type, entity_id) order
 * @param entityType         table of the anomalous record ({@code price_decision}, {@code customer_order},
 *                           {@code price_history})
 * @param entityId           its primary key, the key detection is matched on
 * @param relatedIds         other records involved (cost record, covered price-history rows, promotion, observation)
 * @param fieldName          the field that was changed
 * @param baselineValue      its value in the valid baseline
 * @param injectedValue      its value after injection
 * @param violatedRule       the business rule broken
 * @param expectedAction     what an operator should do
 */
public record GroundTruthRecord(
        String gtId,
        IssueType issueType,
        String entityType,
        String entityId,
        String skuId,
        LocalDate eventDate,
        List<String> relatedIds,
        String fieldName,
        String baselineValue,
        String injectedValue,
        String violatedRule,
        boolean expectedDetection,
        String expectedAction) {

    /** CSV and JSON column order (PLAN.md §9). */
    public static final List<String> COLUMNS = List.of("gt_id", "issue_type", "entity_type", "entity_id", "sku_id",
            "event_date", "related_ids", "field_name", "baseline_value", "injected_value", "violated_rule",
            "expected_detection", "expected_action");

    public GroundTruthRecord {
        Objects.requireNonNull(issueType, "issueType");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        relatedIds = List.copyOf(relatedIds);
    }

    public GroundTruthRecord withId(String id) {
        return new GroundTruthRecord(id, issueType, entityType, entityId, skuId, eventDate, relatedIds, fieldName,
                baselineValue, injectedValue, violatedRule, expectedDetection, expectedAction);
    }

    /** Rule broken per issue type. */
    public static String violatedRule(IssueType type) {
        return switch (type) {
            case PRICE_BELOW_FLOOR -> "LIST_PRICE_GTE_FLOOR";
            case PROMO_STACK_VIOLATION -> "PROMO_STACKING_RULES";
            case STALE_OBSERVATION_AS_CURRENT -> "OBSERVATION_FRESH_72H";
            case MARGIN_BEFORE_DISCOUNT -> "MARGIN_ON_NET_PRICE";
            case PRICE_CHANGE_WITHOUT_REASON -> "PRICE_CHANGE_REQUIRES_REASON";
            default -> throw new IllegalArgumentException("Not an anomaly type: " + type);
        };
    }

    /** Expected operator action per issue type (PLAN.md §9). */
    public static String expectedAction(IssueType type) {
        return switch (type) {
            case PRICE_BELOW_FLOOR -> "BLOCK_PRICE";
            case PROMO_STACK_VIOLATION -> "REJECT_PROMOTION";
            case STALE_OBSERVATION_AS_CURRENT -> "EXCLUDE_OBSERVATION";
            case MARGIN_BEFORE_DISCOUNT -> "RECOMPUTE_MARGIN_ON_NET";
            case PRICE_CHANGE_WITHOUT_REASON -> "REQUIRE_REASON";
            default -> throw new IllegalArgumentException("Not an anomaly type: " + type);
        };
    }
}
