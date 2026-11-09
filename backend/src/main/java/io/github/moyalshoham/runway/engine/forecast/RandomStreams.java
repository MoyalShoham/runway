package io.github.moyalshoham.runway.engine.forecast;

import java.util.SplittableRandom;

/**
 * Independent random streams per (seed, path, item). Because every item draws from its own stream,
 * adding or removing one item (a scenario) leaves every other item's draws unchanged. Comparing a
 * scenario with the baseline therefore shows the effect of the scenario, not a different roll of the
 * dice (common random numbers).
 */
final class RandomStreams {

    private RandomStreams() {}

    static SplittableRandom of(long seed, int path, String item) {
        long h = mix(seed);
        h = mix(h ^ (path * 0x9E3779B97F4A7C15L));
        h = mix(h ^ item.hashCode());
        h = mix(h ^ ((long) item.length() << 32));
        return new SplittableRandom(h);
    }

    /** SplitMix64 finaliser. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
