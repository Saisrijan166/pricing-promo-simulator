package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.ObservationSource;

/**
 * Table {@code competitor_observation} (PLAN.md §5.4). Freshness is judged on {@code sourceCapturedAt}
 * (truth time), not {@code ingestedAt} (system time).
 */
public record CompetitorObservation(
        String observationId,
        String skuId,
        Competitor competitor,
        BigDecimal observedPrice,
        Instant sourceCapturedAt,
        Instant ingestedAt,
        ObservationSource sourceType,
        boolean competitorInStock,
        BigDecimal matchConfidence) {
}
