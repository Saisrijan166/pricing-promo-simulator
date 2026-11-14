package com.pricingsim.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The full traceability chain of a stored decision (PLAN.md §8 "every price is explainable", §13). Stored rows are
 * returned with their column names. {@code detectedExceptions} are detector findings on the decision or the price
 * history it produced; ground-truth labels are never read or returned.
 */
public record DecisionExplanationResponse(
        Map<String, Object> decision,
        Map<String, Object> product,
        Map<String, Object> costRecord,
        List<Map<String, Object>> observations,
        List<Map<String, Object>> promotions,
        JsonNode explanation,
        PriceHistoryRange priceHistory,
        List<Map<String, Object>> statusEvents,
        String previousDecisionId,
        String nextDecisionId,
        List<Map<String, Object>> detectedExceptions) {

    public record PriceHistoryRange(LocalDate from, LocalDate to, int days, List<Map<String, Object>> rows) {
    }
}
