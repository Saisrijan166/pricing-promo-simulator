package com.pricingsim.generator.anomaly;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.pricingsim.domain.enums.IssueType;

/**
 * The anomaly targets chosen before the real run (PLAN.md §9). A target is identified by natural keys (SKU, date and,
 * for orders, the order's position that day), not by generated IDs, because IDs are assigned during the run.
 */
public final class AnomalyPlan {

    /**
     * @param orderIndex position of the order among the SKU-day's orders (write order), or −1 for non-order targets
     */
    public record Target(IssueType type, String skuId, LocalDate day, int orderIndex) {
        public Target {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(skuId, "skuId");
            Objects.requireNonNull(day, "day");
        }

        String key() {
            return key(skuId, day, orderIndex);
        }

        static String key(String skuId, LocalDate day, int orderIndex) {
            return skuId + "|" + day + "|" + orderIndex;
        }
    }

    private final Map<IssueType, List<Target>> byType;
    private final Map<String, Target> byKey = new TreeMap<>();

    public AnomalyPlan(Map<IssueType, List<Target>> byType) {
        Map<IssueType, List<Target>> copy = new EnumMap<>(IssueType.class);
        byType.forEach((t, l) -> copy.put(t, List.copyOf(l)));
        this.byType = Collections.unmodifiableMap(copy);
        for (List<Target> targets : copy.values()) {
            for (Target t : targets) {
                if (byKey.put(t.key(), t) != null) {
                    throw new IllegalArgumentException("Two anomalies on the same record: " + t);
                }
            }
        }
    }

    public Map<IssueType, List<Target>> byType() {
        return byType;
    }

    public int size() {
        return byKey.size();
    }

    /** The decision or price-history target on (SKU, day), or null. */
    public Target dayTarget(String skuId, LocalDate day) {
        return byKey.get(Target.key(skuId, day, -1));
    }

    /** The order target, or null. */
    public Target orderTarget(String skuId, LocalDate day, int orderIndex) {
        return byKey.get(Target.key(skuId, day, orderIndex));
    }
}
