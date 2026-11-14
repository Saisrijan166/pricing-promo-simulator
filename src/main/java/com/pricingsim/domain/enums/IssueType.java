package com.pricingsim.domain.enums;

/**
 * Issue types used by ground truth labels and detected exceptions.
 *
 * <p>The five {@link ExceptionCategory#ANOMALY} types are the labelled edge cases injected by the generator
 * (PLAN.md §9). The {@link ExceptionCategory#OPERATIONAL} types are naturally occurring exceptions that the
 * operations queue reports (PLAN.md §10 "operational exception counts"); they are never injected or labelled.
 */
public enum IssueType {
    PRICE_BELOW_FLOOR(ExceptionCategory.ANOMALY),
    PROMO_STACK_VIOLATION(ExceptionCategory.ANOMALY),
    STALE_OBSERVATION_AS_CURRENT(ExceptionCategory.ANOMALY),
    MARGIN_BEFORE_DISCOUNT(ExceptionCategory.ANOMALY),
    PRICE_CHANGE_WITHOUT_REASON(ExceptionCategory.ANOMALY),

    COMPETITOR_DATA_STALE(ExceptionCategory.OPERATIONAL),
    GUARDRAIL_CONFLICT_ESCALATED(ExceptionCategory.OPERATIONAL),
    STOCKOUT(ExceptionCategory.OPERATIONAL),
    COUPON_REJECTED(ExceptionCategory.OPERATIONAL);

    private final ExceptionCategory category;

    IssueType(ExceptionCategory category) {
        this.category = category;
    }

    public ExceptionCategory category() {
        return category;
    }

    public boolean isAnomaly() {
        return category == ExceptionCategory.ANOMALY;
    }
}
