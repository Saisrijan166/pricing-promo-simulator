package com.pricingsim.domain.id;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only place IDs are formatted and parsed (PLAN.md §6 rule 5). IDs are immutable once assigned; anomaly injection
 * changes attribute values only, never IDs.
 *
 * <ul>
 *   <li>Global: {@code SKU-000001}, {@code CUST-0000001}.</li>
 *   <li>Natural key (the full SKU ID is embedded): {@code COST-SKU-000001-2026-01}, {@code PRC-SKU-000001-20260101},
 *       {@code INV-SKU-000001-20260101}.</li>
 *   <li>Per-year sequences: see {@link Sequence}.</li>
 * </ul>
 * Invalid input always raises {@link IllegalArgumentException}.
 */
public final class IdFactory {

    public static final int SKU_WIDTH = 6;
    public static final int CUSTOMER_WIDTH = 7;

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd, strict

    /** Per-year sequence ID types: {@code PREFIX-YYYY-<counter zero-padded to width>}. */
    public enum Sequence {
        PROMOTION("PROMO", 6, IdPatterns.PROMOTION),
        RULE("RULE", 6, IdPatterns.RULE),
        OBSERVATION("OBS", 7, IdPatterns.OBSERVATION),
        ORDER("ORD", 7, IdPatterns.ORDER),
        DECISION("DEC", 6, IdPatterns.DECISION),
        COUPON("CPN", 6, IdPatterns.COUPON),
        PRODUCT_EVENT("PEV", 6, IdPatterns.PRODUCT_EVENT),
        PROMOTION_EVENT("PMEV", 6, IdPatterns.PROMOTION_EVENT),
        DECISION_EVENT("DEV", 7, IdPatterns.DECISION_EVENT),
        GROUND_TRUTH("GT", 6, IdPatterns.GROUND_TRUTH),
        DETECTED_EXCEPTION("EXC", 7, IdPatterns.DETECTED_EXCEPTION);

        private final String prefix;
        private final int width;
        private final Pattern pattern;

        Sequence(String prefix, int width, Pattern pattern) {
            this.prefix = prefix;
            this.width = width;
            this.pattern = pattern;
        }

        public String prefix() {
            return prefix;
        }

        public int width() {
            return width;
        }

        public Pattern pattern() {
            return pattern;
        }

        /** Largest counter value per year. */
        public long capacity() {
            return SequenceCounter.maxValue(width);
        }
    }

    /** A parsed per-year sequence ID. */
    public record SequenceId(Sequence sequence, int year, long number) {
    }

    private IdFactory() {
    }

    // --- Global IDs ---------------------------------------------------------------------------------------------

    public static String sku(long number) {
        return "SKU-" + SequenceCounter.pad(number, SKU_WIDTH);
    }

    public static long skuNumber(String skuId) {
        return Long.parseLong(match(IdPatterns.SKU, skuId, "SKU").group(1));
    }

    public static String customerRef(long number) {
        return "CUST-" + SequenceCounter.pad(number, CUSTOMER_WIDTH);
    }

    // --- Natural-key IDs ----------------------------------------------------------------------------------------

    /** {@code COST-<sku>-YYYY-MM}. */
    public static String costRecord(String skuId, YearMonth month) {
        requireSku(skuId);
        Objects.requireNonNull(month, "month");
        return "COST-" + skuId + "-" + costMonth(month);
    }

    /** {@code PRC-<sku>-YYYYMMDD}. */
    public static String priceHistory(String skuId, LocalDate date) {
        requireSku(skuId);
        return "PRC-" + skuId + "-" + basicDate(date);
    }

    /** {@code INV-<sku>-YYYYMMDD}. */
    public static String inventorySnapshot(String skuId, LocalDate date) {
        requireSku(skuId);
        return "INV-" + skuId + "-" + basicDate(date);
    }

    /** The {@code cost_month} column value, {@code YYYY-MM}. */
    public static String costMonth(YearMonth month) {
        requireYear(month.getYear());
        return month.toString();
    }

    /** SKU embedded in a COST / PRC / INV ID. */
    public static String skuOf(String naturalKeyId) {
        if (naturalKeyId != null) {
            if (naturalKeyId.startsWith("COST-")) {
                return match(IdPatterns.COST_RECORD, naturalKeyId, "cost record").group(1);
            }
            if (naturalKeyId.startsWith("PRC-")) {
                return match(IdPatterns.PRICE_HISTORY, naturalKeyId, "price history").group(1);
            }
            if (naturalKeyId.startsWith("INV-")) {
                return match(IdPatterns.INVENTORY_SNAPSHOT, naturalKeyId, "inventory snapshot").group(1);
            }
        }
        throw new IllegalArgumentException("Not a COST/PRC/INV id: " + naturalKeyId);
    }

    /** Month embedded in a COST ID. */
    public static YearMonth costMonthOf(String costRecordId) {
        Matcher m = match(IdPatterns.COST_RECORD, costRecordId, "cost record");
        return YearMonth.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    /** Date embedded in a PRC or INV ID. */
    public static LocalDate dateOf(String priceHistoryOrInventoryId) {
        Matcher m;
        if (priceHistoryOrInventoryId != null && priceHistoryOrInventoryId.startsWith("PRC-")) {
            m = match(IdPatterns.PRICE_HISTORY, priceHistoryOrInventoryId, "price history");
        } else if (priceHistoryOrInventoryId != null && priceHistoryOrInventoryId.startsWith("INV-")) {
            m = match(IdPatterns.INVENTORY_SNAPSHOT, priceHistoryOrInventoryId, "inventory snapshot");
        } else {
            throw new IllegalArgumentException("Not a PRC/INV id: " + priceHistoryOrInventoryId);
        }
        try {
            return LocalDate.parse(m.group(2), BASIC_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid calendar date in id: " + priceHistoryOrInventoryId, e);
        }
    }

    // --- Per-year sequence IDs ----------------------------------------------------------------------------------

    /** {@code PREFIX-YYYY-<number zero-padded>}; throws if the number overflows the sequence width. */
    public static String sequenceId(Sequence sequence, int year, long number) {
        Objects.requireNonNull(sequence, "sequence");
        requireYear(year);
        return sequence.prefix() + "-" + year + "-" + SequenceCounter.pad(number, sequence.width());
    }

    public static SequenceId parseSequenceId(Sequence sequence, String id) {
        Matcher m = match(sequence.pattern(), id, sequence.name());
        return new SequenceId(sequence, Integer.parseInt(m.group(1)), Long.parseLong(m.group(2)));
    }

    public static String promotion(int year, long n) {
        return sequenceId(Sequence.PROMOTION, year, n);
    }

    public static String rule(int year, long n) {
        return sequenceId(Sequence.RULE, year, n);
    }

    public static String observation(int year, long n) {
        return sequenceId(Sequence.OBSERVATION, year, n);
    }

    public static String order(int year, long n) {
        return sequenceId(Sequence.ORDER, year, n);
    }

    public static String decision(int year, long n) {
        return sequenceId(Sequence.DECISION, year, n);
    }

    public static String coupon(int year, long n) {
        return sequenceId(Sequence.COUPON, year, n);
    }

    public static String productEvent(int year, long n) {
        return sequenceId(Sequence.PRODUCT_EVENT, year, n);
    }

    public static String promotionEvent(int year, long n) {
        return sequenceId(Sequence.PROMOTION_EVENT, year, n);
    }

    public static String decisionEvent(int year, long n) {
        return sequenceId(Sequence.DECISION_EVENT, year, n);
    }

    public static String groundTruth(int year, long n) {
        return sequenceId(Sequence.GROUND_TRUTH, year, n);
    }

    public static String detectedException(int year, long n) {
        return sequenceId(Sequence.DETECTED_EXCEPTION, year, n);
    }

    // --- Helpers ------------------------------------------------------------------------------------------------

    static void requireYear(int year) {
        if (year < 1000 || year > 9999) {
            throw new IllegalArgumentException("Year must have 4 digits, was " + year);
        }
    }

    private static void requireSku(String skuId) {
        match(IdPatterns.SKU, skuId, "SKU");
    }

    private static String basicDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        requireYear(date.getYear());
        return date.format(BASIC_DATE);
    }

    private static Matcher match(Pattern pattern, String value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("Null " + what + " id");
        }
        Matcher m = pattern.matcher(value);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid " + what + " id: '" + value + "' (expected " + pattern.pattern() + ")");
        }
        return m;
    }
}
