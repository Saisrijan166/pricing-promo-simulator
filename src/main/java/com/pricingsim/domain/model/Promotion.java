package com.pricingsim.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.pricingsim.domain.enums.Channel;
import com.pricingsim.domain.enums.FundingSource;
import com.pricingsim.domain.enums.PromoType;
import com.pricingsim.domain.enums.PromotionStatus;
import com.pricingsim.domain.enums.StackGroup;

/**
 * Table {@code promotion} (PLAN.md §5.6). start_date and end_date are both inclusive. {@code status} is the
 * as-of status on the last simulated day and equals the last promotion_status_event.
 */
public record Promotion(
        String promotionId,
        String promotionName,
        PromoType promoType,
        StackGroup stackGroup,
        BigDecimal discountPct,
        BigDecimal discountAmount,
        String couponCode,
        LocalDate startDate,
        LocalDate endDate,
        int priority,
        Channel channel,
        FundingSource fundingSource,
        PromotionStatus status,
        Instant createdAt) {
}
