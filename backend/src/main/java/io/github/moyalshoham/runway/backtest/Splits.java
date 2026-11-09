package io.github.moyalshoham.runway.backtest;

import java.util.List;
import java.util.stream.LongStream;

/**
 * Company seeds per split. Model parameters were tuned on DEV only; HOLDOUT is used for the reported
 * numbers. Seeds cycle through the four business archetypes (seed mod 4).
 */
public final class Splits {

    public static final List<Long> DEV = LongStream.rangeClosed(1, 24).boxed().toList();
    public static final List<Long> HOLDOUT =
            LongStream.rangeClosed(1001, 1060).boxed().toList();
    /** A small slice of DEV for CI. */
    public static final List<Long> SMOKE = List.of(1L, 2L, 3L, 4L);

    private Splits() {}

    public static List<Long> named(String name) {
        return switch (name) {
            case "dev" -> DEV;
            case "holdout" -> HOLDOUT;
            case "smoke" -> SMOKE;
            default -> throw new IllegalArgumentException("unknown split: " + name + " (dev, holdout, smoke)");
        };
    }
}
