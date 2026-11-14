package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.pricingsim.domain.enums.CostChangeReason;

/**
 * Table {@code cost_record} (PLAN.md §5.2). One record per SKU per active month; effective_to is exclusive and
 * null for the latest record. landed_cost = unit_cost + freight_cost.
 */
public record CostRecord(
        String costRecordId,
        String skuId,
        String costMonth,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal unitCost,
        BigDecimal freightCost,
        BigDecimal landedCost,
        BigDecimal changePct,
        CostChangeReason changeReason) {
}
