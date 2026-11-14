package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.CustomerSegment;
import com.pricingsim.domain.enums.OrderStatus;

/**
 * Table {@code customer_order} (PLAN.md §5.8). A single-SKU order line. {@code customerRef} is an opaque synthetic
 * token with no PII and no FK. Amounts are derived: gross = qty × unit_list_price, net = gross − discount,
 * gross_margin = net − qty × unit_landed_cost.
 */
public record CustomerOrder(
        String orderId,
        Instant orderTs,
        LocalDate orderDate,
        String skuId,
        String customerRef,
        CustomerSegment customerSegment,
        Channel channel,
        int quantity,
        String priceHistoryId,
        String costRecordId,
        BigDecimal unitListPrice,
        BigDecimal grossAmount,
        BigDecimal discountAmount,
        BigDecimal netAmount,
        BigDecimal unitLandedCost,
        BigDecimal grossMarginAmount,
        OrderStatus orderStatus) {
}
