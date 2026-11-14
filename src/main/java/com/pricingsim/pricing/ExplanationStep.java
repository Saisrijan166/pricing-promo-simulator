package com.pricingsim.pricing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One engine step in a decision's explanation (PLAN.md §8). A decision stores all 12 steps (S1–S12) as a JSON array in
 * {@code price_decision.explanation}. Values are strings, so the JSON never depends on number formatting.
 *
 * @param step    step ID, {@code S1} … {@code S12}
 * @param rule    short rule name
 * @param inputs  the values the step used, in a fixed order
 * @param output  the step's result (null when the step produced no value, e.g. no competitor reference)
 * @param message one-line human explanation
 */
public record ExplanationStep(String step, String rule, Map<String, String> inputs, String output, String message) {

    public ExplanationStep {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(rule, "rule");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        Objects.requireNonNull(message, "message");
    }

    /** Compact, deterministic JSON array: keys in declaration order, input keys in insertion order. */
    public static String toJson(List<ExplanationStep> steps) {
        StringBuilder sb = new StringBuilder(steps.size() * 160);
        sb.append('[');
        for (int i = 0; i < steps.size(); i++) {
            ExplanationStep s = steps.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"step\":");
            string(sb, s.step);
            sb.append(",\"rule\":");
            string(sb, s.rule);
            sb.append(",\"inputs\":{");
            int k = 0;
            for (Map.Entry<String, String> e : s.inputs.entrySet()) {
                if (k++ > 0) {
                    sb.append(',');
                }
                string(sb, e.getKey());
                sb.append(':');
                if (e.getValue() == null) {
                    sb.append("null");
                } else {
                    string(sb, e.getValue());
                }
            }
            sb.append("},\"output\":");
            if (s.output == null) {
                sb.append("null");
            } else {
                string(sb, s.output);
            }
            sb.append(",\"message\":");
            string(sb, s.message);
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static void string(StringBuilder sb, String value) {
        sb.append('"');
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
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
