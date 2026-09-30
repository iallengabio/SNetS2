package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.routing.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One candidate per core, cores in decreasing id order, with the spectrum rule of ABNE by core colour (First-Fit,
 * Last-Fit, medium fit; {@link AbneCoreAndSpectrumAssignment}). This is the candidate generator of the {@code KSPXT}
 * algorithm of SNetS v1 (id {@code colourfit}), meant for {@code kspxt}; with the standard RMSCA it tries the cores
 * from the highest id down.
 */
public class ColourFitCoreAndSpectrumAssignment implements ICoreAndSpectrumAssignment {

    @Override
    public Iterable<CoreSpectrumCandidate> candidates(ControlPlane cp, Path path, int numSlots) {
        List<Integer> cores = new ArrayList<>(PeripheralFirstCoreAssignment.commonCores(path));
        Collections.reverse(cores);
        Map<Integer, Integer> colours = PeripheralFirstCoreAssignment.coreColours(path);
        List<CoreSpectrumCandidate> candidates = new ArrayList<>(cores.size());
        for (int core : cores) {
            candidates.add(new CoreSpectrumCandidate(core, AbneCoreAndSpectrumAssignment.assignSpectrum(
                    path.links(), core, colours.getOrDefault(core, 0), numSlots)));
        }
        return candidates;
    }
}
