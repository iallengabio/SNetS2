package com.snets2.rmsca.core;

import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPCAS, core prioritization crosstalk avoidance strategy (port of {@code CorePrioritizationCrosstalkAvoidanceStrategy}
 * of SNetS v1, id {@code cpcas}), with the prioritized spectrum zones of
 * {@link ZonePrioritizedCoreAndSpectrumAssignment}.
 *
 * <p>Each link keeps a weight per core. The core of a call is the one with the lowest sum of weights along the path
 * (ties to the highest id, as v1 scans the cores downwards). On every link of the path the chosen core then gets the
 * maximum weight ({@value #MAX_WEIGHT}) and its adjacent cores get +1; when all the cores of a link reach the maximum,
 * the weights of that link are reset to 0. The weights are updated when the core is chosen, even if the candidate
 * fails the validation, and never decrease at teardown, as in v1. In v1 they are stored in the network model
 * ({@code Core.peso}); here they belong to the algorithm instance (one per replication).</p>
 *
 * <p>{@code cpcas-fallback} (extension) proposes the other cores after the chosen one, by increasing weight sum before
 * the update (ties to the highest id).</p>
 */
public class CorePrioritizationCoreAndSpectrumAssignment extends ZonePrioritizedCoreAndSpectrumAssignment {

    /** Weight of a core just used ({@code MAXPESOCORE} of v1). */
    public static final int MAX_WEIGHT = 99999;

    private final Map<Link, int[]> weights = new IdentityHashMap<>();

    public CorePrioritizationCoreAndSpectrumAssignment() {
        this(false);
    }

    protected CorePrioritizationCoreAndSpectrumAssignment(boolean fallback) {
        super(fallback);
    }

    /** {@code cpcas-fallback}. */
    public static class Fallback extends CorePrioritizationCoreAndSpectrumAssignment {
        public Fallback() { super(true); }
    }

    /** Current weight of {@code core} on {@code link} (0 if never updated). */
    public int weight(Link link, int core) {
        int[] w = weights.get(link);
        return w == null || core >= w.length ? 0 : w[core];
    }

    @Override
    protected List<Integer> coreOrder(Path path, List<Integer> cores) {
        List<Integer> byWeight = new ArrayList<>(cores);
        Map<Integer, Long> sums = new java.util.HashMap<>();
        for (int core : cores) {
            long sum = 0;
            for (Link link : path.links()) sum += weight(link, core);
            sums.put(core, sum);
        }
        byWeight.sort(Comparator.<Integer>comparingLong(sums::get).thenComparing(Comparator.reverseOrder()));
        update(path, byWeight.get(0));
        return byWeight;
    }

    private void update(Path path, int chosen) {
        for (Link link : path.links()) {
            int[] w = weights.computeIfAbsent(link, l -> new int[l.getCores().keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1]);
            w[chosen] = MAX_WEIGHT;
            for (int adj : link.getCore(chosen).getAdjacentCores()) w[adj]++;
        }
        for (Link link : path.links()) {
            int[] w = weights.get(link);
            boolean allUsed = link.getCores().keySet().stream().allMatch(c -> w[c] >= MAX_WEIGHT);
            if (allUsed) java.util.Arrays.fill(w, 0);
        }
    }
}
