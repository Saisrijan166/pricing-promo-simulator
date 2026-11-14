package com.pricingsim.generator.rng;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Random;

/**
 * Independent, named random streams derived from one global seed (PLAN.md §7.1).
 *
 * <p>Every draw in the generator comes from {@code of(stream, key)}, which returns
 * {@code new java.util.Random(first8Bytes(SHA-256(seed + "|" + stream + "|" + key)))}. {@link Random}'s algorithm is
 * fixed by the Java specification, so the same (seed, stream, key) gives the same sequence on every JVM and platform.
 * Streams are independent: adding draws to one stream (for example one SKU's demand, or anomaly targeting) never shifts
 * another stream's sequence.
 */
public final class RandomStreams {

    private final long seed;

    public RandomStreams(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    /** A fresh generator for {@code (stream, key)}; the same arguments always give the same sequence. */
    public Random of(String stream, String key) {
        return new Random(derive(seed, stream, key));
    }

    /** A fresh generator for a stream that needs no key (equivalent to key {@code ""}). */
    public Random of(String stream) {
        return of(stream, "");
    }

    /** The 64-bit seed for {@code (seed, stream, key)}: the first 8 bytes (big-endian) of the SHA-256 digest. */
    public static long derive(long seed, String stream, String key) {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(key, "key");
        byte[] digest = sha256(seed + "|" + stream + "|" + key);
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        return value;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
