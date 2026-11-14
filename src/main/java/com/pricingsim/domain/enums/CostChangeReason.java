package com.pricingsim.domain.enums;

/** Why a monthly cost record changed (cost_record.change_reason). */
public enum CostChangeReason {
    INITIAL,
    NO_CHANGE,
    SUPPLIER_INCREASE,
    SUPPLIER_DECREASE,
    FREIGHT_CHANGE
}
