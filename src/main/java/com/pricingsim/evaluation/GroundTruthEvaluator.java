package com.pricingsim.evaluation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.pricingsim.domain.enums.IssueType;

/**
 * Detected exceptions vs ground truth per anomaly type (PLAN.md §10 evaluation), matched on
 * (issue_type, entity_type, entity_id). Precision is 1.0 when nothing was detected and recall is 1.0 when nothing was
 * labelled (no possible error), so a clean dataset scores 1.0 / 1.0 with zero counts.
 */
public final class GroundTruthEvaluator {

    public static final List<IssueType> ANOMALY_TYPES = List.of(IssueType.PRICE_BELOW_FLOOR,
            IssueType.PROMO_STACK_VIOLATION, IssueType.STALE_OBSERVATION_AS_CURRENT, IssueType.MARGIN_BEFORE_DISCOUNT,
            IssueType.PRICE_CHANGE_WITHOUT_REASON);

    private GroundTruthEvaluator() {
    }

    /** TP / FP / FN with derived scores. */
    public record Score(long labelled, long detected, long truePositives, long falsePositives, long falseNegatives) {
        public double precision() {
            return truePositives + falsePositives == 0 ? 1.0 : truePositives / (double) (truePositives + falsePositives);
        }

        public double recall() {
            return truePositives + falseNegatives == 0 ? 1.0 : truePositives / (double) (truePositives + falseNegatives);
        }

        public double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0.0 : 2 * p * r / (p + r);
        }
    }

    /**
     * @param perType  score per anomaly type
     * @param micro    pooled over all types
     * @param macroPrecision / macroRecall / macroF1 unweighted means over the types
     * @param missed   labelled keys not detected; {@code unexpected} detected keys not labelled
     */
    public record Evaluation(Map<IssueType, Score> perType, Score micro, double macroPrecision, double macroRecall,
                             double macroF1, List<String> missed, List<String> unexpected) {
    }

    /** Keys are {@code issue_type|entity_type|entity_id}. */
    public static Evaluation evaluate(List<String> labelledKeys, List<String> detectedKeys) {
        Map<IssueType, Score> per = new EnumMap<>(IssueType.class);
        List<String> missed = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        long l = 0;
        long d = 0;
        long tp = 0;
        long fp = 0;
        long fn = 0;
        double mp = 0;
        double mr = 0;
        double mf = 0;
        for (IssueType type : ANOMALY_TYPES) {
            String prefix = type.name() + "|";
            TreeSet<String> gt = new TreeSet<>(labelledKeys.stream().filter(k -> k.startsWith(prefix)).toList());
            TreeSet<String> det = new TreeSet<>(detectedKeys.stream().filter(k -> k.startsWith(prefix)).toList());
            long t = gt.stream().filter(det::contains).count();
            gt.stream().filter(k -> !det.contains(k)).forEach(missed::add);
            det.stream().filter(k -> !gt.contains(k)).forEach(unexpected::add);
            Score s = new Score(gt.size(), det.size(), t, det.size() - t, gt.size() - t);
            per.put(type, s);
            l += s.labelled();
            d += s.detected();
            tp += s.truePositives();
            fp += s.falsePositives();
            fn += s.falseNegatives();
            mp += s.precision();
            mr += s.recall();
            mf += s.f1();
        }
        int n = ANOMALY_TYPES.size();
        return new Evaluation(per, new Score(l, d, tp, fp, fn), mp / n, mr / n, mf / n, missed, unexpected);
    }
}
