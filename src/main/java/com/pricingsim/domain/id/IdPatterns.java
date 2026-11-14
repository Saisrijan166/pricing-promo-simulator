package com.pricingsim.domain.id;

import java.util.regex.Pattern;

/**
 * Compiled ID regexes (PLAN.md §6). Shared by {@link IdFactory}, the table registry and the validator so that every
 * component agrees on what a valid ID is.
 *
 * <p>The regex strings use only {@code ^ $ \d {n} ( | )}, so the same text works in Java, in PostgreSQL ({@code ~})
 * and in H2 ({@code REGEXP_LIKE}). Capture groups expose the embedded fields (SKU, year, month, date, counter).
 * {@code YYYY} is the year of the record's business date; it is 2026 throughout the simulation window.
 */
public final class IdPatterns {

    // --- Global / natural-key IDs -------------------------------------------------------------------------------
    /** {@code SKU-000001}; group 1 = number. */
    public static final String SKU_REGEX = "^SKU-(\\d{6})$";
    /** {@code CUST-0000001}; opaque customer token, no PII. Group 1 = number. */
    public static final String CUSTOMER_REGEX = "^CUST-(\\d{7})$";
    /** {@code COST-SKU-000001-2026-01}; groups: 1 = SKU, 2 = year, 3 = month. */
    public static final String COST_RECORD_REGEX = "^COST-(SKU-\\d{6})-(\\d{4})-(0[1-9]|1[0-2])$";
    /** {@code PRC-SKU-000001-20260101}; groups: 1 = SKU, 2 = yyyyMMdd. */
    public static final String PRICE_HISTORY_REGEX = "^PRC-(SKU-\\d{6})-(\\d{8})$";
    /** {@code INV-SKU-000001-20260101}; groups: 1 = SKU, 2 = yyyyMMdd. */
    public static final String INVENTORY_SNAPSHOT_REGEX = "^INV-(SKU-\\d{6})-(\\d{8})$";

    // --- Per-year sequence IDs: PREFIX-YYYY-<zero-padded counter>; groups: 1 = year, 2 = counter ------------------
    public static final String PROMOTION_REGEX = "^PROMO-(\\d{4})-(\\d{6})$";
    public static final String RULE_REGEX = "^RULE-(\\d{4})-(\\d{6})$";
    public static final String OBSERVATION_REGEX = "^OBS-(\\d{4})-(\\d{7})$";
    public static final String ORDER_REGEX = "^ORD-(\\d{4})-(\\d{7})$";
    public static final String DECISION_REGEX = "^DEC-(\\d{4})-(\\d{6})$";
    public static final String COUPON_REGEX = "^CPN-(\\d{4})-(\\d{6})$";
    public static final String PRODUCT_EVENT_REGEX = "^PEV-(\\d{4})-(\\d{6})$";
    public static final String PROMOTION_EVENT_REGEX = "^PMEV-(\\d{4})-(\\d{6})$";
    public static final String DECISION_EVENT_REGEX = "^DEV-(\\d{4})-(\\d{7})$";
    public static final String GROUND_TRUTH_REGEX = "^GT-(\\d{4})-(\\d{6})$";
    public static final String DETECTED_EXCEPTION_REGEX = "^EXC-(\\d{4})-(\\d{7})$";

    public static final Pattern SKU = Pattern.compile(SKU_REGEX);
    public static final Pattern CUSTOMER = Pattern.compile(CUSTOMER_REGEX);
    public static final Pattern COST_RECORD = Pattern.compile(COST_RECORD_REGEX);
    public static final Pattern PRICE_HISTORY = Pattern.compile(PRICE_HISTORY_REGEX);
    public static final Pattern INVENTORY_SNAPSHOT = Pattern.compile(INVENTORY_SNAPSHOT_REGEX);
    public static final Pattern PROMOTION = Pattern.compile(PROMOTION_REGEX);
    public static final Pattern RULE = Pattern.compile(RULE_REGEX);
    public static final Pattern OBSERVATION = Pattern.compile(OBSERVATION_REGEX);
    public static final Pattern ORDER = Pattern.compile(ORDER_REGEX);
    public static final Pattern DECISION = Pattern.compile(DECISION_REGEX);
    public static final Pattern COUPON = Pattern.compile(COUPON_REGEX);
    public static final Pattern PRODUCT_EVENT = Pattern.compile(PRODUCT_EVENT_REGEX);
    public static final Pattern PROMOTION_EVENT = Pattern.compile(PROMOTION_EVENT_REGEX);
    public static final Pattern DECISION_EVENT = Pattern.compile(DECISION_EVENT_REGEX);
    public static final Pattern GROUND_TRUTH = Pattern.compile(GROUND_TRUTH_REGEX);
    public static final Pattern DETECTED_EXCEPTION = Pattern.compile(DETECTED_EXCEPTION_REGEX);

    private IdPatterns() {
    }

    /** Null-safe full match. */
    public static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }
}
