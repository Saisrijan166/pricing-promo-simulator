package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.pricingsim.domain.enums.ReasonCode;

/**
 * Table {@code price_history} (PLAN.md §5.3). One row per SKU per active day. {@code changeReason} and
 * {@code sourceDecisionId} are nullable at DB level so the "price change without reason" anomaly stays loadable.
 */
public record PriceHistory(
        String priceHistoryId,
        String skuId,
        LocalDate priceDate,
        BigDecimal listPrice,
        BigDecimal shelfPrice,
        BigDecimal previousListPrice,
        ReasonCode changeReason,
        String sourceDecisionId,
        String costRecordId) {
}
