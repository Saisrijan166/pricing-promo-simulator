package com.pricingsim.domain.model;

import java.math.BigDecimal;

import com.pricingsim.domain.enums.ApplicationStatus;
import com.pricingsim.domain.enums.ReasonCode;

/**
 * Bridge table {@code order_promotion}: Order ⇄ Promotion (N:M), PK (order_id, promotion_id). Records APPLIED and
 * REJECTED coupon attempts; {@code rejectionReason} is required iff REJECTED, and {@code discountAmount} is 0 then.
 */
public record OrderPromotion(
        String orderId,
        String promotionId,
        int sequenceNo,
        boolean couponPresented,
        ApplicationStatus applicationStatus,
        ReasonCode rejectionReason,
        BigDecimal discountAmount) {
}
