package com.pricingsim.domain.model;

import java.time.Instant;

import com.pricingsim.domain.enums.ActorRole;
import com.pricingsim.domain.enums.DecisionStatus;

/** Event table {@code price_decision_status_event}: append-only decision lifecycle history. fromStatus is null on the first event. */
public record PriceDecisionStatusEvent(
        String eventId,
        String decisionId,
        DecisionStatus fromStatus,
        DecisionStatus toStatus,
        Instant eventTs,
        ActorRole actorRole,
        String note) {
}
