package com.pricingsim.domain.id;

import java.util.Objects;
import java.util.function.LongFunction;

/**
 * Zero-padded, overflow-checked counter (PLAN.md §6 rule 3). A counter of width {@code w} issues 1 … 10^w − 1 and
 * then throws, so a scale that would overflow an ID format fails fast instead of producing malformed or duplicate IDs.
 *
 * <p>Not thread-safe by design: the generator is single-threaded so ID assignment order is deterministic.
 */
public final class SequenceCounter {

    private final String name;
    private final int width;
    private final long capacity;
    private final LongFunction<String> formatter;
    private long last;

    /** A plain counter whose {@link #nextId()} returns the zero-padded number. */
    public SequenceCounter(String name, int width) {
        this(name, width, n -> pad(n, width));
    }

    private SequenceCounter(String name, int width, LongFunction<String> formatter) {
        if (width < 1 || width > 18) {
            throw new IllegalArgumentException("width must be 1..18, was " + width);
        }
        this.name = Objects.requireNonNull(name, "name");
        this.width = width;
        this.capacity = maxValue(width);
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    /** Counter for a per-year sequence, e.g. {@code DEC-2026-000001}. */
    public static SequenceCounter forSequence(IdFactory.Sequence sequence, int year) {
        IdFactory.requireYear(year);
        return new SequenceCounter(sequence.prefix() + "-" + year, sequence.width(),
                n -> IdFactory.sequenceId(sequence, year, n));
    }

    /** Global SKU counter: {@code SKU-000001}. */
    public static SequenceCounter forSku() {
        return new SequenceCounter("SKU", IdFactory.SKU_WIDTH, IdFactory::sku);
    }

    /** Global customer-token counter: {@code CUST-0000001}. */
    public static SequenceCounter forCustomer() {
        return new SequenceCounter("CUST", IdFactory.CUSTOMER_WIDTH, IdFactory::customerRef);
    }

    /** Issues the next number. */
    public long next() {
        if (last >= capacity) {
            throw new IllegalStateException("Sequence " + name + " overflow: capacity " + capacity
                    + " (" + width + " digits) exhausted");
        }
        return ++last;
    }

    /** Issues the next number and formats it as an ID. */
    public String nextId() {
        return formatter.apply(next());
    }

    /** Fails fast if {@code additional} more IDs would not fit (used to reject a scale before generating). */
    public void ensureCapacity(long additional) {
        if (additional < 0 || additional > remaining()) {
            throw new IllegalStateException("Sequence " + name + " cannot issue " + additional + " more IDs: "
                    + remaining() + " of " + capacity + " remaining");
        }
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    /** Largest number this counter can issue. */
    public long capacity() {
        return capacity;
    }

    /** Numbers issued so far (also the last number issued). */
    public long issued() {
        return last;
    }

    public long remaining() {
        return capacity - last;
    }

    /** Largest value representable in {@code width} digits. */
    public static long maxValue(int width) {
        long max = 1;
        for (int i = 0; i < width; i++) {
            max *= 10;
        }
        return max - 1;
    }

    /** Zero-pads {@code value} to {@code width} digits; throws if it does not fit or is not positive. */
    public static String pad(long value, int width) {
        if (value < 1 || value > maxValue(width)) {
            throw new IllegalArgumentException("Value " + value + " does not fit in " + width + " digits (1.."
                    + maxValue(width) + ")");
        }
        String digits = Long.toString(value);
        return "0".repeat(width - digits.length()) + digits;
    }
}
