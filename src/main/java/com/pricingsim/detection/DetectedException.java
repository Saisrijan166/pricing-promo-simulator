package com.pricingsim.detection;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.pricingsim.domain.enums.ExceptionCategory;
import com.pricingsim.domain.enums.IssueType;

/**
 * One finding of the detector ({@code detected_exception} row, PLAN.md §10). Findings come only from the loaded data;
 * the detector never reads labels.
 *
 * @param exceptionId {@code EXC-YYYY-%07d}, assigned in (issue_type, entity_id) order; null until assigned
 * @param runId       the DETECT run; null until written
 */
public record DetectedException(
        String exceptionId,
        String runId,
        IssueType issueType,
        String entityType,
        String entityId,
        String skuId,
        LocalDate eventDate,
        List<String> relatedIds,
        String violatedRule,
        String observedValue,
        String expectedValue,
        String recommendedAction,
        String detail) {

    public DetectedException {
        Objects.requireNonNull(issueType, "issueType");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        relatedIds = List.copyOf(relatedIds);
    }

    public ExceptionCategory category() {
        return issueType.category();
    }

    public DetectedException withIds(String id, String run) {
        return new DetectedException(id, run, issueType, entityType, entityId, skuId, eventDate, relatedIds,
                violatedRule, observedValue, expectedValue, recommendedAction, detail);
    }

    /** A finding without IDs (assigned when the detector writes them). */
    public static DetectedException of(IssueType type, String entityType, String entityId, String skuId,
                                       LocalDate eventDate, List<String> relatedIds, String observed, String expected,
                                       String detail) {
        return new DetectedException(null, null, type, entityType, entityId, skuId, eventDate, relatedIds,
                Rules.violatedRule(type), observed, expected, Rules.recommendedAction(type), detail);
    }

    /** Rule names and actions per issue type (PLAN.md §9 and §10). */
    public static final class Rules {
        private Rules() {
        }

        public static String violatedRule(IssueType type) {
            return switch (type) {
                case PRICE_BELOW_FLOOR -> "LIST_PRICE_GTE_FLOOR";
                case PROMO_STACK_VIOLATION -> "PROMO_STACKING_RULES";
                case STALE_OBSERVATION_AS_CURRENT -> "OBSERVATION_FRESH_72H";
                case MARGIN_BEFORE_DISCOUNT -> "MARGIN_ON_NET_PRICE";
                case PRICE_CHANGE_WITHOUT_REASON -> "PRICE_CHANGE_REQUIRES_REASON";
                case COMPETITOR_DATA_STALE -> "COMPETITOR_DATA_FRESH";
                case GUARDRAIL_CONFLICT_ESCALATED -> "FLOOR_LTE_MRP";
                case STOCKOUT -> "STOCK_AVAILABLE";
                case COUPON_REJECTED -> "COUPON_STACKING_RULES";
            };
        }

        public static String recommendedAction(IssueType type) {
            return switch (type) {
                case PRICE_BELOW_FLOOR -> "BLOCK_PRICE";
                case PROMO_STACK_VIOLATION -> "REJECT_PROMOTION";
                case STALE_OBSERVATION_AS_CURRENT -> "EXCLUDE_OBSERVATION";
                case MARGIN_BEFORE_DISCOUNT -> "RECOMPUTE_MARGIN_ON_NET";
                case PRICE_CHANGE_WITHOUT_REASON -> "REQUIRE_REASON";
                case COMPETITOR_DATA_STALE -> "REFRESH_COMPETITOR_FEED";
                case GUARDRAIL_CONFLICT_ESCALATED -> "RENEGOTIATE_COST_OR_MRP";
                case STOCKOUT -> "EXPEDITE_REPLENISHMENT";
                case COUPON_REJECTED -> "INFORM_CUSTOMER";
            };
        }
    }
}
