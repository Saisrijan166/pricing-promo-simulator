package com.pricingsim.domain.model;

import java.math.BigDecimal;

import com.pricingsim.domain.enums.ObservationUsage;

/**
 * Bridge table {@code price_decision_observation}: PriceDecision ⇄ CompetitorObservation (N:M),
 * PK (decision_id, observation_id). Every observation the engine considered, with its usage and age at decision time.
 */
public record PriceDecisionObservation(
        String decisionId,
        String observationId,
        ObservationUsage usage,
        BigDecimal ageHours) {
}
