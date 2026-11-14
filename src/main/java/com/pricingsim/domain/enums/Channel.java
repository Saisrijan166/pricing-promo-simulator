package com.pricingsim.domain.enums;

/**
 * Sales channel. A promotion may target {@link #ALL}, {@link #ECOM} or {@link #STORE} (promotion.channel);
 * an order is always placed in a concrete channel, ECOM or STORE (customer_order.channel).
 */
public enum Channel {
    ALL,
    ECOM,
    STORE;

    /** True for the channels an order can carry. */
    public boolean isOrderChannel() {
        return this != ALL;
    }

    /** True if a promotion with this channel applies to an order or shelf price in {@code target}. */
    public boolean covers(Channel target) {
        return this == ALL || this == target;
    }
}
