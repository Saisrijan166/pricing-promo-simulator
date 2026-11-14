package com.pricingsim.generator.anomaly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.generator.rng.Distributions;
import com.pricingsim.generator.rng.RandomStreams;

/**
 * Chooses the anomaly targets up front from the dedicated {@code anomaly} streams, among records eligible in the
 * baseline (PLAN.md §9). Exactly {@code perType} targets per issue type, and at most one anomaly per SKU across all
 * types (scarcest type first): every SKU draws from its own streams, so a SKU's data up to its injection point is identical to the baseline
 * dry run and the planned target is guaranteed to exist. Downstream effects of an injection (for example, more demand
 * at a below-floor price) stay within that SKU.
 */
public final class AnomalyPlanner {

    /** The five labelled issue types, in planning order. */
    public static final List<IssueType> TYPES = List.of(IssueType.PRICE_BELOW_FLOOR, IssueType.PROMO_STACK_VIOLATION,
            IssueType.STALE_OBSERVATION_AS_CURRENT, IssueType.MARGIN_BEFORE_DISCOUNT,
            IssueType.PRICE_CHANGE_WITHOUT_REASON);

    private static final Comparator<AnomalyPlan.Target> NATURAL = Comparator.comparing(AnomalyPlan.Target::skuId)
            .thenComparing(AnomalyPlan.Target::day).thenComparingInt(AnomalyPlan.Target::orderIndex);

    private AnomalyPlanner() {
    }

    public static AnomalyPlan plan(Map<IssueType, List<AnomalyPlan.Target>> candidates, int perType,
                                   RandomStreams streams) {
        Map<IssueType, List<AnomalyPlan.Target>> chosen = new EnumMap<>(IssueType.class);
        TreeSet<String> usedSkus = new TreeSet<>();
        // Scarcest type first (fewest distinct candidate SKUs), so common types cannot use up the SKUs rare ones need.
        List<IssueType> order = new ArrayList<>(TYPES);
        order.sort(Comparator.comparingLong((IssueType t) -> candidates.getOrDefault(t, List.of()).stream()
                .map(AnomalyPlan.Target::skuId).distinct().count()).thenComparingInt(TYPES::indexOf));
        for (IssueType type : order) {
            List<AnomalyPlan.Target> pool = new ArrayList<>(candidates.getOrDefault(type, List.of()));
            pool.sort(NATURAL);
            Distributions.shuffle(streams.of("anomaly", type.name()), pool);
            List<AnomalyPlan.Target> picked = new ArrayList<>(perType);
            for (AnomalyPlan.Target t : pool) {
                if (picked.size() == perType) {
                    break;
                }
                if (usedSkus.add(t.skuId())) {
                    picked.add(t);
                }
            }
            if (picked.size() < perType) {
                throw new IllegalStateException("Only " + picked.size() + " eligible " + type + " targets on distinct"
                        + " SKUs; " + perType + " requested. Lower --anomalies-per-type or raise --skus.");
            }
            picked.sort(NATURAL);
            chosen.put(type, picked);
        }
        return new AnomalyPlan(chosen);
    }
}
