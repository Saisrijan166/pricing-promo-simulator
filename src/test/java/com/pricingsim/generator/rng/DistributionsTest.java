package com.pricingsim.generator.rng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Distribution moments over 100k draws with a fixed seed (PLAN.md §12). */
class DistributionsTest {

    private static final int N = 100_000;

    private static Random rng(String key) {
        return new RandomStreams(20261022L).of("distributions-test", key);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.05, 0.7, 4.5, 29.9, 75.0, 400.0})
    void poissonMeanAndVarianceMatchLambda(double lambda) {
        Random r = rng("poisson-" + lambda);
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < N; i++) {
            int k = Distributions.poisson(r, lambda);
            assertThat(k).isNotNegative();
            sum += k;
            sumSq += (double) k * k;
        }
        double mean = sum / N;
        double variance = sumSq / N - mean * mean;
        // Standard error of the mean is sqrt(lambda / N); allow 5 standard errors.
        assertThat(mean).isCloseTo(lambda, within(5 * StrictMath.sqrt(lambda / N) + 1e-9));
        assertThat(variance).isCloseTo(lambda, within(0.05 * lambda + 0.01));
    }

    @Test
    void poissonOfZeroIsZeroAndNegativeIsRejected() {
        Random r = rng("poisson-zero");
        assertThat(Distributions.poisson(r, 0.0)).isZero();
        assertThatThrownBy(() -> Distributions.poisson(r, -1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Distributions.poisson(r, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lognormalMeanAndMedian() {
        double mu = StrictMath.log(250.0);
        double sigma = 0.5;
        Random r = rng("lognormal");
        double[] xs = new double[N];
        double sum = 0;
        for (int i = 0; i < N; i++) {
            xs[i] = Distributions.lognormal(r, mu, sigma);
            assertThat(xs[i]).isPositive();
            sum += xs[i];
        }
        double expectedMean = StrictMath.exp(mu + sigma * sigma / 2);
        assertThat(sum / N).isCloseTo(expectedMean, within(expectedMean * 0.01));
        java.util.Arrays.sort(xs);
        assertThat(xs[N / 2]).isCloseTo(250.0, within(250.0 * 0.01));
    }

    @Test
    void lognormalMedianParameterisation() {
        Random r = rng("lognormal-median");
        double[] xs = IntStream.range(0, N).mapToDouble(i -> Distributions.lognormalMedian(r, 3.0, 1.2)).sorted().toArray();
        assertThat(xs[N / 2]).isCloseTo(3.0, within(0.1));
    }

    @Test
    void standardNormalHasMeanZeroAndUnitVariance() {
        Random r = rng("normal");
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < N; i++) {
            double z = Distributions.standardNormal(r);
            sum += z;
            sumSq += z * z;
        }
        assertThat(sum / N).isCloseTo(0.0, within(0.02));
        assertThat(sumSq / N).isCloseTo(1.0, within(0.02));
    }

    @Test
    void exponentialMean() {
        Random r = rng("exponential");
        double sum = 0;
        for (int i = 0; i < N; i++) {
            double x = Distributions.exponential(r, 6.0);
            assertThat(x).isNotNegative();
            sum += x;
        }
        assertThat(sum / N).isCloseTo(6.0, within(0.1));
    }

    @Test
    void uniformAndUniformIntStayInRange() {
        Random r = rng("uniform");
        int[] seen = new int[4];
        for (int i = 0; i < N; i++) {
            assertThat(Distributions.uniform(r, 0.40, 0.80)).isBetween(0.40, 0.80);
            int k = Distributions.uniformInt(r, 1, 4);
            assertThat(k).isBetween(1, 4);
            seen[k - 1]++;
        }
        for (int count : seen) {
            assertThat(count).isCloseTo(N / 4, within(N / 50));
        }
    }

    @Test
    void bernoulliRate() {
        Random r = rng("bernoulli");
        long hits = IntStream.range(0, N).filter(i -> Distributions.bernoulli(r, 0.08)).count();
        assertThat(hits / (double) N).isCloseTo(0.08, within(0.005));
        assertThatThrownBy(() -> Distributions.bernoulli(r, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatedNormalRespectsBounds() {
        Random r = rng("truncated");
        for (int i = 0; i < N; i++) {
            assertThat(Distributions.truncatedNormal(r, 0.0, 5.0, -1.0, 2.0)).isBetween(-1.0, 2.0);
        }
    }

    @Test
    void betaLikeHasTheRequestedLowShareAndRange() {
        Random r = rng("beta-like");
        int low = 0;
        for (int i = 0; i < N; i++) {
            double x = Distributions.betaLike(r, 0.60, 0.80, 1.00, 0.05);
            assertThat(x).isBetween(0.60, 1.00);
            if (x < 0.80) {
                low++;
            }
        }
        assertThat(low / (double) N).isCloseTo(0.05, within(0.004));
    }

    @Test
    void weightedChoiceFollowsWeights() {
        Random r = rng("weighted");
        double[] weights = {0.30, 0.50, 0.20};
        int[] counts = new int[3];
        for (int i = 0; i < N; i++) {
            counts[Distributions.weightedIndex(r, weights)]++;
        }
        for (int i = 0; i < weights.length; i++) {
            assertThat(counts[i] / (double) N).isCloseTo(weights[i], within(0.01));
        }
        assertThat(Distributions.weightedIndex(rng("zero-weight"), new double[] {0, 1, 0})).isEqualTo(1);
        assertThatThrownBy(() -> Distributions.weightedIndex(r, new double[] {0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apportionIsExactAndSumsToTotal() {
        double[] lifecycle = {0.92, 0.05, 0.03};
        assertThat(Distributions.apportion(200, lifecycle)).containsExactly(184, 10, 6);
        assertThat(Distributions.apportion(5000, lifecycle)).containsExactly(4600, 250, 150);
        assertThat(Distributions.apportion(20, lifecycle)).containsExactly(18, 1, 1);
        assertThat(IntStream.of(Distributions.apportion(37, new double[] {0.2, 0.12, 0.08, 0.6})).sum()).isEqualTo(37);
    }

    @Test
    void shuffleIsAPermutationAndDeterministic() {
        List<Integer> a = new ArrayList<>(IntStream.range(0, 100).boxed().toList());
        List<Integer> b = new ArrayList<>(a);
        Distributions.shuffle(rng("shuffle"), a);
        Distributions.shuffle(rng("shuffle"), b);
        assertThat(a).isEqualTo(b).containsExactlyInAnyOrderElementsOf(IntStream.range(0, 100).boxed().toList());
        assertThat(a).isNotEqualTo(IntStream.range(0, 100).boxed().toList());
    }

    @Test
    void sameSeedGivesTheSameDraws() {
        Random a = rng("repeat");
        Random b = rng("repeat");
        for (int i = 0; i < 1_000; i++) {
            assertThat(Distributions.poisson(a, 3.3)).isEqualTo(Distributions.poisson(b, 3.3));
            assertThat(Distributions.lognormal(a, 1.0, 0.7)).isEqualTo(Distributions.lognormal(b, 1.0, 0.7));
        }
    }
}
