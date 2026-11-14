package com.pricingsim.io;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Minimal deterministic JSON text helpers. Output files (manifest, labels, summaries, exports) are written by hand so
 * key order and number formatting never depend on a library's defaults; numbers are written exactly as
 * {@link BigDecimal#toPlainString()}.
 */
public final class JsonText {

    private JsonText() {
    }

    /** A JSON string literal, or {@code null}. */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** A JSON number for a decimal ({@code null} → {@code null}). */
    public static String number(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    /** A JSON number for a double, with fixed decimals (for metrics). */
    public static String number(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "null";
        }
        return new BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
