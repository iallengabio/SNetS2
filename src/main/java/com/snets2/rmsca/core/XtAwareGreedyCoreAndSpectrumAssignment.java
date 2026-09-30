package com.snets2.rmsca.core;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import com.snets2.rmsca.spectrum.SpectrumSearch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * XT-aware greedy (port of {@code XtAwareGreedyAlgorithm} of SNetS v1, id {@code xtawaregreedy}): among every free
 * (core, interval) of the demand, the feasible one with the largest crosstalk margin {@code XT_th(m) - XT} of the new
 * circuit.
 *
 * <p>v1 validates every candidate and keeps the best margin, returning at once a candidate without any crosstalk. For
 * a given format the threshold is the same for all candidates, so the largest margin is the smallest XT. Here the
 * candidates are ordered by the predicted XT of the new circuit ({@link PhysicalLayerModel#predictXtRatio}, which reads
 * the crosstalk cache and costs one pass over the links), ascending; ties keep the order of v1 (cores in increasing
 * id, start slots in increasing order). The RMSCA accepts the first feasible candidate of this order, which is exactly
 * the choice of v1, while the costly checks (SNR, active circuits) run only until that candidate is found.</p>
 *
 * <p>Without crosstalk ({@code activeXT = false} or no physical layer) every candidate has XT = 0 and the order is
 * First-Fit core with First-Fit spectrum over all intervals. Cores without any free interval are reported at the end
 * with {@code slots == null}.</p>
 */
public class XtAwareGreedyCoreAndSpectrumAssignment implements ICoreAndSpectrumAssignment {

    private record Scored(CoreSpectrumCandidate candidate, double xt) {}

    @Override
    public Iterable<CoreSpectrumCandidate> candidates(ControlPlane cp, Path path, int numSlots) {
        List<Integer> cores = PeripheralFirstCoreAssignment.commonCores(path);
        if (cores.isEmpty() || numSlots <= 0) return List.of();
        PhysicalLayerConfig config = cp.getPhysicalLayerConfig();
        boolean xtActive = config != null && config.activeQoT() && config.activeXT();
        List<Link> links = path.links();

        List<Scored> scored = new ArrayList<>();
        List<CoreSpectrumCandidate> full = new ArrayList<>();
        for (int core : cores) {
            int last = links.get(0).getCore(core).getSpectrum().getNumSlots() - numSlots;
            boolean found = false;
            for (int s = 0; s <= last; s++) {
                if (!SpectrumSearch.fits(links, core, s, numSlots)) continue;
                double xt = xtActive ? PhysicalLayerModel.predictXtRatio(cp, path, core, s, s + numSlots - 1) : 0;
                scored.add(new Scored(new CoreSpectrumCandidate(core, new SpectrumInterval(s, s + numSlots - 1)), xt));
                found = true;
            }
            if (!found) full.add(new CoreSpectrumCandidate(core, null));
        }
        scored.sort(Comparator.comparingDouble(Scored::xt)); // stable: ties keep (core, slot) order
        List<CoreSpectrumCandidate> ordered = new ArrayList<>(scored.size() + full.size());
        for (Scored s : scored) ordered.add(s.candidate());
        ordered.addAll(full);
        return ordered;
    }
}
