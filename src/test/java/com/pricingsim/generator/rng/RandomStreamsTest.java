package com.pricingsim.generator.rng;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class RandomStreamsTest {

    private static final long SEED = 20261022L;

    @Test
    void seedIsFirstEightBytesOfSha256OfSeedStreamAndKey() {
        // echo -n '20261022|product|SKU-000001' | sha256sum → 3c98ddbee5ff2c19…
        assertThat(RandomStreams.derive(SEED, "product", "SKU-000001"))
                .isEqualTo(Long.parseUnsignedLong("3c98ddbee5ff2c19", 16));
    }

    @Test
    void streamSequenceIsPinnedAcrossJvms() {
        // java.util.Random's algorithm is fixed by the Java spec, so this value must never change.
        assertThat(new RandomStreams(SEED).of("demand", "SKU-000001").nextLong()).isEqualTo(-6564475651113083233L);
    }

    @Test
    void sameSeedStreamAndKeyGiveTheSameSequence() {
        RandomStreams a = new RandomStreams(SEED);
        RandomStreams b = new RandomStreams(SEED);
        assertThat(draws(a.of("demand", "SKU-000042"), 1_000)).containsExactly(draws(b.of("demand", "SKU-000042"), 1_000));
    }

    @Test
    void differentKeysStreamsAndSeedsGiveDifferentSequences() {
        RandomStreams streams = new RandomStreams(SEED);
        long[] base = draws(streams.of("demand", "SKU-000001"), 100);
        assertThat(draws(streams.of("demand", "SKU-000002"), 100)).isNotEqualTo(base);
        assertThat(draws(streams.of("operations", "SKU-000001"), 100)).isNotEqualTo(base);
        assertThat(draws(new RandomStreams(SEED + 1).of("demand", "SKU-000001"), 100)).isNotEqualTo(base);
        // The separator keeps (stream, key) pairs apart: "a|bc" vs "ab|c".
        assertThat(RandomStreams.derive(SEED, "a", "bc")).isNotEqualTo(RandomStreams.derive(SEED, "ab", "c"));
    }

    @Test
    void drawingFromOneStreamLeavesOtherStreamsUnaffected() {
        RandomStreams streams = new RandomStreams(SEED);
        long[] untouched = draws(streams.of("demand", "SKU-000002"), 200);

        // Consume heavily from a different stream first (e.g. another SKU, or anomaly targeting), then re-open.
        draws(streams.of("demand", "SKU-000001"), 50_000);
        draws(streams.of("anomaly"), 10_000);
        assertThat(draws(streams.of("demand", "SKU-000002"), 200)).containsExactly(untouched);
    }

    @Test
    void keylessStreamEqualsEmptyKey() {
        RandomStreams streams = new RandomStreams(SEED);
        assertThat(draws(streams.of("product-category"), 10)).containsExactly(draws(streams.of("product-category", ""), 10));
    }

    @Test
    void derivedSeedsSpreadOverTheLongRange() {
        long distinct = IntStream.range(0, 10_000)
                .mapToLong(i -> RandomStreams.derive(SEED, "demand", "SKU-" + i))
                .distinct().count();
        assertThat(distinct).isEqualTo(10_000);
    }

    private static long[] draws(Random r, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = r.nextLong();
        }
        return out;
    }
}
