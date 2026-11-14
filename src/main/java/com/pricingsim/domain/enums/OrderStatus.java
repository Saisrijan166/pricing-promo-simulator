package com.pricingsim.domain.enums;

/** Order status (customer_order.order_status). CANCELLED orders are excluded from units_sold. */
public enum OrderStatus {
    FULFILLED,
    CANCELLED
}
