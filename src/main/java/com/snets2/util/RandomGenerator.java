package com.snets2.util;

import java.util.Random;

/**
 * Central utility for generating random numbers and managing stochastic distributions.
 * 
 * <p>This class encapsulates a {@link Random} instance to ensure reproducibility 
 * through seed management. It provides methods for common distributions used in 
 * network simulations, such as Exponential (for inter-arrival and hold times).</p>
 */
public class RandomGenerator {
    private final Random random;

    /**
     * Constructs a RandomGenerator with a specific seed.
     *
     * @param seed The seed for the underlying random number generator.
     */
    public RandomGenerator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Generates a random value following an Exponential distribution.
     * 
     * <p>Used for modeling inter-arrival times (Poisson process) and 
     * connection holding times.</p>
     *
     * @param rate The rate parameter (lambda for arrivals, mu for departures).
     * @return A random value from the exponential distribution.
     */
    public double nextExponential(double rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        return -Math.log(1.0 - random.nextDouble()) / rate;
    }

    /**
     * Returns a random integer between 0 (inclusive) and the specified bound (exclusive).
     *
     * @param bound The upper bound (exclusive).
     * @return A random integer.
     */
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    /**
     * Returns a random double between 0.0 and 1.0.
     *
     * @return A random double.
     */
    public double nextDouble() {
        return random.nextDouble();
    }

    /**
     * Returns the underlying Random instance if needed for more complex operations.
     *
     * @return The {@link Random} instance.
     */
    public Random getInternalRandom() {
        return random;
    }
}
