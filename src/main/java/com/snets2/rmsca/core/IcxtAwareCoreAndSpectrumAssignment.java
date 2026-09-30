package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import com.snets2.rmsca.spectrum.SpectrumSearch;
import com.snets2.rmsca.spectrum.SpectrumZones;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ICXTAA, inter-core crosstalk aware algorithm (port of {@code IcxtAwareAlgorithm} of SNetS v1, id {@code icxtaa}).
 *
 * <p>Proposes <b>every</b> free interval of the demand, not only one per core: cores in decreasing id order (as v1)
 * and, in each core, the prioritized spectrum zones of its group ({@link SpectrumZones}, own zone first) with every
 * start slot of each zone in increasing order. The RMSCA validates them in this order (SNR and crosstalk of the new
 * circuit and of the active ones) and accepts the first feasible one, which is the choice of v1. Unlike the
 * one-interval-per-core algorithms, a core whose first free interval fails on crosstalk is not given up: the next
 * intervals of the same core are tried.</p>
 *
 * <p>A core without any free interval is reported once with {@code slots == null} (blocking cause
 * {@code FRAGMENTATION} when no core has room).</p>
 */
public class IcxtAwareCoreAndSpectrumAssignment implements ICoreAndSpectrumAssignment {

    @Override
    public Iterable<CoreSpectrumCandidate> candidates(ControlPlane cp, Path path, int numSlots) {
        List<Integer> cores = new ArrayList<>(PeripheralFirstCoreAssignment.commonCores(path));
        if (cores.isEmpty() || numSlots <= 0) return List.of();
        java.util.Collections.reverse(cores);
        Map<Integer, Integer> colours = PeripheralFirstCoreAssignment.coreColours(path);
        int numColours = colours.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        List<Link> links = path.links();

        List<CoreSpectrumCandidate> candidates = new ArrayList<>();
        for (int core : cores) {
            int totalSlots = links.get(0).getCore(core).getSpectrum().getNumSlots();
            boolean found = false;
            for (SpectrumInterval zone : SpectrumZones.searchOrder(totalSlots, numColours, colours.getOrDefault(core, 0))) {
                for (int s = zone.start(); s + numSlots - 1 <= zone.end(); s++) {
                    if (SpectrumSearch.fits(links, core, s, numSlots)) {
                        candidates.add(new CoreSpectrumCandidate(core, new SpectrumInterval(s, s + numSlots - 1)));
                        found = true;
                    }
                }
            }
            if (!found) candidates.add(new CoreSpectrumCandidate(core, null));
        }
        return candidates;
    }
}
