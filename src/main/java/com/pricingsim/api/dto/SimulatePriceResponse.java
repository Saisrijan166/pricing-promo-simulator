package com.pricingsim.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Engine result for a simulated decision. Nothing is persisted ({@code persisted} is always false).
 *
 * @param candidateListPrice the final list price the engine would set
 * @param expectedNetPrice   list price minus applied markdowns (the shelf price)
 */
public record SimulatePriceResponse(
        String skuId,
        LocalDate asOfDate,
        Instant decisionTs,
        String trigger,
        Inputs inputs,
        BigDecimal candidateListPrice,
        BigDecimal expectedNetPrice,
        BigDecimal floorPrice,
        BigDecimal ceilingPrice,
        BigDecimal targetPrice,
        BigDecimal competitorRefPrice,
        BigDecimal markdownDiscountPct,
        BigDecimal marginPct,
        BigDecimal requiredMarginPct,
        String decisionType,
        String decisionSource,
        String status,
        String primaryReasonCode,
        List<String> reasonCodes,
        List<Observation> observations,
        List<Promotion> promotions,
        JsonNode explanation,
        String ruleVersion,
        boolean persisted) {

    public record Inputs(String costRecordId, BigDecimal landedCost, BigDecimal prevListPrice,
                         String previousDecisionId, int onHandUnits, int demand7dUnits, int demand28dUnits,
                         BigDecimal velocityRatio, BigDecimal daysOfCover) {
    }

    public record Observation(String observationId, String competitor, BigDecimal price, Instant capturedAt,
                              BigDecimal ageHours, String usage) {
    }

    public record Promotion(String promotionId, String status, String reasonCode, BigDecimal discountPerUnit,
                            BigDecimal discountPctEffective) {
    }
}
