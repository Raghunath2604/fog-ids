package fogids.sim;

import java.util.Random;

/**
 * Process-wide seeded RNG. Nothing in the current baseline scenarios consumes
 * randomness (sensor emission is deterministic), but the run harness already
 * threads a --seed argument through every invocation so that once Member C's
 * attack injectors introduce randomised timing, source selection, or jitter,
 * every run remains reproducible from its recorded seed without any other
 * code needing to change.
 */
public final class Rng {
    private static Random instance;

    private Rng() {}

    public static void seed(long seed) {
        instance = new Random(seed);
    }

    public static Random get() {
        if (instance == null) {
            throw new IllegalStateException("Rng.seed(seed) must be called before Rng.get()");
        }
        return instance;
    }
}
