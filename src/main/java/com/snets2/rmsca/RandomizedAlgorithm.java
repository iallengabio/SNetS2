package com.snets2.rmsca;

import java.util.Random;

/**
 * Marker for RMSCA sub-algorithms that make random choices (e.g. Random Fit).
 *
 * <p>The experiment runner injects a generator seeded from the replication seed so that every
 * replication is reproducible. The generator is independent from the traffic generator, which
 * keeps the traffic stream identical across algorithms (common random numbers).</p>
 */
public interface RandomizedAlgorithm {
    void setRandom(Random random);
}
