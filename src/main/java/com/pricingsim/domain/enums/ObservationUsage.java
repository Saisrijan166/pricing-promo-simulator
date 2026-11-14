package com.pricingsim.domain.enums;

/** How a considered competitor observation was used by a decision (price_decision_observation.usage). */
public enum ObservationUsage {
    USED,
    EXCLUDED_STALE,
    EXCLUDED_LOW_CONFIDENCE,
    EXCLUDED_SUPERSEDED
}
