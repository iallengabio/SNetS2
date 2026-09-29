package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.model.Core;
import com.snets2.model.Link;
import com.snets2.rmsca.RandomizedAlgorithm;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.FirstFitSpectrumAssignment;
import com.snets2.rmsca.spectrum.ISpectrumAssignment;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Slot-aware crosstalk core assignment.
 *
 * <p>For each candidate core it estimates the interval the request would actually receive (the
 * interval chosen by the configured spectrum policy for {@code numSlots} slots; First-Fit when the
 * policy is randomized or unknown, so that scoring does not consume the policy's random stream) and
 * computes the crosstalk cost of that interval:</p>
 *
 * <pre>  cost(c) = sum_{links l} L_l * sum_{a in adj(c)} |occupied_l(a) ∩ [s_c, e_c]|</pre>
 *
 * <p>With the model {@code XT = sum h L} over overlapping neighbours, this overlap count weighted by
 * the link length is proportional both to the crosstalk the new circuit would suffer and to the
 * crosstalk it would inject into the active circuits of the adjacent cores (equal launch PSD per
 * slot). Cores are sorted by increasing cost; ties are broken by the peripheral-first order of
 * {@link PeripheralFirstCoreAssignment} (which is also the full order when all costs are zero).
 * Cores without a free interval go last. The order is deterministic.</p>
 *
 * <p>Called without the demand size ({@link #selectCores(ControlPlane, Path)}) it falls back to the
 * peripheral-first order.</p>
 */
public class XtAwareCoreAssignment implements ICoreAssignment {

    private static final ISpectrumAssignment FALLBACK_SPECTRUM = new FirstFitSpectrumAssignment();

    @Override
    public List<Integer> selectCores(ControlPlane cp, Path path) {
        return PeripheralFirstCoreAssignment.peripheralOrder(path);
    }

    @Override
    public List<Integer> selectCores(ControlPlane cp, Path path, int numSlots, ISpectrumAssignment spectrumAssignment) {
        List<Integer> order = PeripheralFirstCoreAssignment.peripheralOrder(path);
        if (order.size() < 2 || numSlots <= 0) return order;

        ISpectrumAssignment scoring = spectrumAssignment == null || spectrumAssignment instanceof RandomizedAlgorithm
                ? FALLBACK_SPECTRUM : spectrumAssignment;

        Map<Integer, Double> cost = new HashMap<>();
        for (Integer coreId : order) {
            SpectrumInterval interval = scoring.findSlots(cp, path, coreId, numSlots);
            cost.put(coreId, interval == null ? Double.POSITIVE_INFINITY : overlapCost(path, coreId, interval));
        }

        List<Integer> sorted = new ArrayList<>(order);
        // List.sort is stable: equal costs keep the peripheral-first order
        sorted.sort((c1, c2) -> Double.compare(cost.get(c1), cost.get(c2)));
        return sorted;
    }

    /** Sum over the links of the link length times the number of occupied adjacent slots in the interval. */
    static double overlapCost(Path path, int coreId, SpectrumInterval interval) {
        double cost = 0;
        for (Link link : path.links()) {
            Core core = link.getCore(coreId);
            int overlaps = 0;
            for (int adjId : core.getAdjacentCores()) {
                Core adj = link.getCore(adjId);
                if (adj == null) continue;
                for (int s = interval.start(); s <= interval.end(); s++) {
                    if (adj.getSpectrum().isOccupied(s)) overlaps++;
                }
            }
            cost += overlaps * link.getLength();
        }
        return cost;
    }
}
