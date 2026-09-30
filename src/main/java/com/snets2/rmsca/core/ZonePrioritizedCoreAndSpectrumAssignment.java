package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumZones;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core prioritization with prioritized spectrum zones (Fujii et al., JOCN 6(12):1059-1071, 2014), base of the
 * {@code cpcas} and {@code rccas} algorithms of SNetS v1: a core chosen by the subclass, and First-Fit inside the
 * spectrum zones of the core's group ({@link SpectrumZones}), its own zone first.
 *
 * <p>As in v1, a single core is proposed per call: if it has no room the attempt fails, even if other cores are free.
 * The {@code -fallback} variants (extension, not in v1) then propose the other cores in the order given by the
 * subclass, each with the zones of its own group.</p>
 */
public abstract class ZonePrioritizedCoreAndSpectrumAssignment implements ICoreAndSpectrumAssignment {

    private final boolean fallback;

    protected ZonePrioritizedCoreAndSpectrumAssignment(boolean fallback) {
        this.fallback = fallback;
    }

    /**
     * Cores of the path in the order to propose them; the first one is the core of this call. Called once per call,
     * so stateful choices (weights, random numbers) advance once per call, as in v1.
     */
    protected abstract List<Integer> coreOrder(Path path, List<Integer> cores);

    @Override
    public Iterable<CoreSpectrumCandidate> candidates(ControlPlane cp, Path path, int numSlots) {
        List<Integer> cores = PeripheralFirstCoreAssignment.commonCores(path);
        if (cores.isEmpty()) return List.of();
        Map<Integer, Integer> colours = PeripheralFirstCoreAssignment.coreColours(path);
        int numColours = colours.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;

        List<Integer> order = coreOrder(path, cores);
        if (!fallback) order = order.subList(0, 1);
        List<CoreSpectrumCandidate> candidates = new ArrayList<>(order.size());
        for (int core : order) {
            candidates.add(new CoreSpectrumCandidate(core, SpectrumZones.zonedFirstFit(
                    path.links(), core, colours.getOrDefault(core, 0), numColours, numSlots)));
        }
        return candidates;
    }
}
