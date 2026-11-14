package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.pricingsim.domain.enums.RuleType;

/**
 * Table {@code promotion_rule} (PLAN.md §5.7). {@code targetPromotionId} is set only for EXCLUDES_PROMOTION;
 * {@code paramNumeric} holds the count / fraction / units for MAX_* and MIN_ORDER_QTY.
 */
public record PromotionRule(
        String ruleId,
        String promotionId,
        RuleType ruleType,
        String targetPromotionId,
        BigDecimal paramNumeric,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
