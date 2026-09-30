package com.snets2.rmsca.core;

import com.snets2.rmsca.RandomizedAlgorithm;
import com.snets2.rmsca.routing.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * RCCAS, random core crosstalk avoidance strategy (port of {@code RandomCoreCrosstalkAvoidanceStrategy} of SNetS v1,
 * id {@code rccas}): a core drawn uniformly at random, with the prioritized spectrum zones of
 * {@link ZonePrioritizedCoreAndSpectrumAssignment}. v1 draws from an unseeded {@code new Random()} at every call; here
 * the generator is derived from the replication seed ({@link RandomizedAlgorithm}), so runs are reproducible.
 *
 * <p>{@code rccas-fallback} (extension) proposes the other cores in a random order after the drawn one.</p>
 */
public class RandomCoreZoneAssignment extends ZonePrioritizedCoreAndSpectrumAssignment implements RandomizedAlgorithm {

    private Random random = new Random(0);
    private final boolean fallback;

    public RandomCoreZoneAssignment() {
        this(false);
    }

    protected RandomCoreZoneAssignment(boolean fallback) {
        super(fallback);
        this.fallback = fallback;
    }

    /** {@code rccas-fallback}. */
    public static class Fallback extends RandomCoreZoneAssignment {
        public Fallback() { super(true); }
    }

    @Override
    public void setRandom(Random random) {
        this.random = random;
    }

    @Override
    protected List<Integer> coreOrder(Path path, List<Integer> cores) {
        List<Integer> order = new ArrayList<>(cores);
        if (fallback) {
            Collections.shuffle(order, random);
        } else {
            Collections.swap(order, 0, random.nextInt(order.size()));
        }
        return order;
    }
}
