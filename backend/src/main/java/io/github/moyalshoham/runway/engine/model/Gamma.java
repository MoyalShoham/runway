package io.github.moyalshoham.runway.engine.model;

import java.util.SplittableRandom;

final class Gamma {

    private Gamma() {}

    /** Gamma(shape, 1) by Marsaglia and Tsang; shape below 1 uses the boost u^(1/shape). */
    static double sample(double shape, SplittableRandom rng) {
        if (shape <= 0) {
            throw new IllegalArgumentException("shape must be positive");
        }
        if (shape < 1) {
            return sample(shape + 1, rng) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x;
            double v;
            do {
                x = normal(rng);
                v = 1.0 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x || Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    private static double normal(SplittableRandom rng) {
        double u = 1.0 - rng.nextDouble();
        double v = rng.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }
}
