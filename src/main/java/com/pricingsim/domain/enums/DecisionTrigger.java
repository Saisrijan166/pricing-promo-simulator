package com.pricingsim.domain.enums;

/** What caused a price decision (price_decision.trigger). */
public enum DecisionTrigger {
    INITIAL,
    WEEKLY_REVIEW,
    COST_CHANGE,
    STOCKOUT_EVENT,
    MANUAL
}
