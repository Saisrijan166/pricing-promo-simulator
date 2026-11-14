package com.pricingsim.domain.model;

import java.math.BigDecimal;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.ReasonCode;

/**
 * Bridge table {@code price_decision_promotion}: PriceDecision ⇄ Promotion (N:M), PK (decision_id, promotion_id).
 * Every candidate markdown the engine considered, APPLIED or REJECTED with its reason.
 */
public record PriceDecisionPromotion(
        String decisionId,
        String promotionId,
        ApplicationStatus applicationStatus,
        ReasonCode reasonCode,
        BigDecimal discountPctEffective) {
}
