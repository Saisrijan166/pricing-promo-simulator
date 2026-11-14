package com.pricingsim.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** One day's price with its source decision and reason. */
public record PriceHistoryView(String priceHistoryId, LocalDate priceDate, BigDecimal listPrice, BigDecimal shelfPrice,
                               BigDecimal previousListPrice, String changeReason, String sourceDecisionId,
                               String decisionTrigger, String decisionPrimaryReason, String costRecordId) {

    public static PriceHistoryView of(Map<String, Object> r) {
        return new PriceHistoryView((String) r.get("price_history_id"), (LocalDate) r.get("price_date"),
                (BigDecimal) r.get("list_price"), (BigDecimal) r.get("shelf_price"),
                (BigDecimal) r.get("previous_list_price"), (String) r.get("change_reason"),
                (String) r.get("source_decision_id"), (String) r.get("decision_trigger"),
                (String) r.get("decision_primary_reason"), (String) r.get("cost_record_id"));
    }
}
