package com.pricingsim.generator.rng;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Probability distributions over a {@link Random} (PLAN.md §7.1). Only {@link StrictMath} is used, so results are
 * bit-for-bit identical across JVMs and platforms. Every method consumes a fixed, documented number of draws where
 * possible, which keeps streams easy to reason about.
 */
public final class Distributions {

    /** Poisson draws above this mean are split into chunks so {@code exp(-lambda)} never underflows. */
    private static final double POISSON_CHUNK = 30.0;
    /** Rejection attempts before {@link #truncatedNormal} clamps (keeps the draw count bounded). */
    private static final int TRUNCATED_NORMAL_ATTEMPTS = 64;

    private Distributions() {
    }

    /** Uniform in {@code [lo, hi)}. One draw. */
    public static double uniform(Random r, double lo, double hi) {
        if (!(hi >= lo)) {
            throw new IllegalArgumentException("hi < lo: " + lo + ", " + hi);
        }
        return lo + (hi - lo) * r.nextDouble();
    }

    /** Uniform integer in {@code [lo, hi]} (both inclusive). One draw. */
    public static int uniformInt(Random r, int lo, int hi) {
        if (hi < lo) {
            throw new IllegalArgumentException("hi < lo: " + lo + ", " + hi);
        }
        return lo + r.nextInt(hi - lo + 1);
    }

    /** {@code true} with probability {@code p}. One draw. */
    public static boolean bernoulli(Random r, double p) {
        requireProbability(p);
        return r.nextDouble() < p;
    }

    /** Standard normal via Box–Muller (cosine branch). Two draws. */
    public static double standardNormal(Random r) {
        double u1 = 1.0 - r.nextDouble(); // (0, 1], so log is finite
        double u2 = r.nextDouble();
        return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2);
    }

    public static double normal(Random r, double mean, double sd) {
        return mean + sd * standardNormal(r);
    }

    /** Lognormal: {@code exp(mu + sigma * Z)}; mean is {@code exp(mu + sigma^2 / 2)}. Two draws. */
    public static double lognormal(Random r, double mu, double sigma) {
        return StrictMath.exp(mu + sigma * standardNormal(r));
    }

    /** Lognormal parameterised by its median ({@code exp(mu)}). Two draws. */
    public static double lognormalMedian(Random r, double median, double sigma) {
        if (!(median > 0)) {
            throw new IllegalArgumentException("median must be > 0: " + median);
        }
        return lognormal(r, StrictMath.log(median), sigma);
    }

    /** Exponential with the given mean. One draw. */
    public static double exponential(Random r, double mean) {
        if (!(mean > 0)) {
            throw new IllegalArgumentException("mean must be > 0: " + mean);
        }
        return -mean * StrictMath.log(1.0 - r.nextDouble());
    }

    /**
     * Poisson with mean {@code lambda} (Knuth's multiplication method). Means above 30 are drawn as a sum of
     * independent chunks of at most 30, which is exact because Poisson variables add.
     */
    public static int poisson(Random r, double lambda) {
        if (!(lambda >= 0) || Double.isInfinite(lambda)) {
            throw new IllegalArgumentException("lambda must be finite and >= 0: " + lambda);
        }
        int total = 0;
        double remaining = lambda;
        while (remaining > POISSON_CHUNK) {
            total += knuthPoisson(r, POISSON_CHUNK);
            remaining -= POISSON_CHUNK;
        }
        return total + knuthPoisson(r, remaining);
    }

    private static int knuthPoisson(Random r, double lambda) {
        if (lambda == 0) {
            return 0;
        }
        double limit = StrictMath.exp(-lambda);
        double product = r.nextDouble();
        int k = 0;
        while (product > limit) {
            k++;
            product *= r.nextDouble();
        }
        return k;
    }

    /**
     * Normal truncated to {@code [lo, hi]} by rejection. After 64 rejected attempts the last draw is clamped, so a
     * call never loops forever and always consumes a bounded number of draws.
     */
    public static double truncatedNormal(Random r, double mean, double sd, double lo, double hi) {
        if (hi < lo) {
            throw new IllegalArgumentException("hi < lo: " + lo + ", " + hi);
        }
        double x = mean;
        for (int i = 0; i < TRUNCATED_NORMAL_ATTEMPTS; i++) {
            x = normal(r, mean, sd);
            if (x >= lo && x <= hi) {
                return x;
            }
        }
        return StrictMath.min(hi, StrictMath.max(lo, x));
    }

    /**
     * "Beta-like" value in {@code [lo, hi)} with a share {@code lowShare} forced below {@code threshold}
     * (PLAN.md §7.2 step 4: match confidence 0.6–1.0 with 5% below 0.80). The low part is uniform on
     * {@code [lo, threshold)}; the high part is {@code threshold + (hi − threshold) · max(U1, U2)}, a Beta(2,1) shape
     * that leans towards {@code hi}. Three draws.
     */
    public static double betaLike(Random r, double lo, double threshold, double hi, double lowShare) {
        if (!(lo <= threshold && threshold <= hi)) {
            throw new IllegalArgumentException("require lo <= threshold <= hi");
        }
        boolean low = bernoulli(r, lowShare);
        double u1 = r.nextDouble();
        double u2 = r.nextDouble();
        if (low) {
            return lo + (threshold - lo) * u1;
        }
        return threshold + (hi - threshold) * StrictMath.max(u1, u2);
    }

    /** Index drawn with probability proportional to {@code weights[i]}. One draw. */
    public static int weightedIndex(Random r, double[] weights) {
        Objects.requireNonNull(weights, "weights");
        double total = 0;
        for (double w : weights) {
            if (!(w >= 0)) {
                throw new IllegalArgumentException("negative or NaN weight");
            }
            total += w;
        }
        if (!(total > 0)) {
            throw new IllegalArgumentException("weights sum to zero");
        }
        double target = r.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (target < cumulative) {
                return i;
            }
        }
        // Floating-point rounding can leave target == total; return the last positive weight.
        for (int i = weights.length - 1; i >= 0; i--) {
            if (weights[i] > 0) {
                return i;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** Element drawn with probability proportional to its weight. One draw. */
    public static <T> T weightedChoice(Random r, List<T> items, double[] weights) {
        if (items.size() != weights.length) {
            throw new IllegalArgumentException("items and weights differ in length");
        }
        return items.get(weightedIndex(r, weights));
    }

    /** Uniformly chosen element. One draw. */
    public static <T> T choice(Random r, List<T> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("empty list");
        }
        return items.get(r.nextInt(items.size()));
    }

    /** In-place Fisher–Yates shuffle (backwards, {@code nextInt(i + 1)} per position). */
    public static <T> void shuffle(Random r, List<T> items) {
        for (int i = items.size() - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            T tmp = items.get(i);
            items.set(i, items.get(j));
            items.set(j, tmp);
        }
    }

    /**
     * Splits {@code total} into integer counts proportional to {@code weights} using the largest-remainder method, so
     * the counts always sum to {@code total}. Ties in the remainder go to the lower index. Draws nothing.
     */
    public static int[] apportion(int total, double[] weights) {
        if (total < 0) {
            throw new IllegalArgumentException("total < 0");
        }
        double sum = 0;
        for (double w : weights) {
            if (!(w >= 0)) {
                throw new IllegalArgumentException("negative or NaN weight");
            }
            sum += w;
        }
        if (!(sum > 0)) {
            throw new IllegalArgumentException("weights sum to zero");
        }
        int[] counts = new int[weights.length];
        double[] remainders = new double[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            double exact = total * weights[i] / sum;
            counts[i] = (int) StrictMath.floor(exact);
            remainders[i] = exact - counts[i];
            assigned += counts[i];
        }
        for (int left = total - assigned; left > 0; left--) {
            int best = 0;
            for (int i = 1; i < weights.length; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            counts[best]++;
            remainders[best] = -1;
        }
        return counts;
    }

    private static void requireProbability(double p) {
        if (!(p >= 0 && p <= 1)) {
            throw new IllegalArgumentException("probability must be in [0, 1]: " + p);
        }
    }
}
