package io.github.moyalshoham.runway.engine.model;

import java.util.SplittableRandom;

final class Poisson {

    private Poisson() {}

    /** Knuth's method; fine for the small daily rates used here (lambda well under 30). */
    static int sample(double lambda, SplittableRandom rng) {
        if (lambda <= 0) {
            return 0;
        }
        double limit = Math.exp(-lambda);
        int k = 0;
        double p = rng.nextDouble();
        while (p > limit) {
            k++;
            p *= rng.nextDouble();
        }
        return k;
    }
}
