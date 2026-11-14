package com.pricingsim.domain.enums;

/** Price decision lifecycle state (price_decision.status, price_decision_status_event). */
public enum DecisionStatus {
    PROPOSED,
    ESCALATED,
    APPROVED,
    REJECTED,
    APPLIED,
    SUPERSEDED
}
