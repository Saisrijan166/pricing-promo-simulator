package com.pricingsim.domain.enums;

/** Promotion stacking group (promotion.stack_group). COUPON iff promo_type is COUPON_*. */
public enum StackGroup {
    SITEWIDE,
    CATEGORY,
    SKU,
    COUPON
}
