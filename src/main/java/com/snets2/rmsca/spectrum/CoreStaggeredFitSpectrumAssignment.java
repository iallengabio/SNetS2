package com.snets2.rmsca.spectrum;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.core.PeripheralFirstCoreAssignment;
import com.snets2.rmsca.routing.Path;
import java.util.List;
import java.util.Map;

/**
 * Spectrum assignment with a different starting point per core ("staggered" First-Fit), to keep
 * adjacent cores on disjoint parts of the spectrum for as long as possible and so reduce inter-core
 * crosstalk.
 *
 * <p>The cores of the fibre are coloured greedily so that adjacent cores never share a colour
 * ({@link PeripheralFirstCoreAssignment#coreColours}); with {@code k} colours and {@code N} slots:</p>
 * <ul>
 *   <li>colour 0: First-Fit, from slot 0 upwards;</li>
 *   <li>colour 1: Last-Fit, from slot {@code N - 1} downwards;</li>
 *   <li>colour {@code c >= 2}: First-Fit starting at slot {@code floor(N (c - 1) / (k - 1))} and
 *       wrapping around to slot 0.</li>
 * </ul>
 * <p>For the 7-core hexagonal MCF, the outer cores 1, 3, 5 fill from the bottom, the outer cores 2, 4, 6
 * from the top and the central core 0 from the middle of the band. Without adjacency (single core or
 * uncoupled cores) it reduces to First-Fit.</p>
 */
public class CoreStaggeredFitSpectrumAssignment implements ISpectrumAssignment {

    @Override
    public SpectrumInterval findSlots(ControlPlane cp, Path path, int coreIndex, int numSlots) {
        List<Link> links = path.links();
        if (links.isEmpty()) return null;

        int totalSlots = links.get(0).getCore(coreIndex).getSpectrum().getNumSlots();
        int lastStart = totalSlots - numSlots;
        if (lastStart < 0) return null;

        Map<Integer, Integer> colours = PeripheralFirstCoreAssignment.coreColours(path);
        int colour = colours.getOrDefault(coreIndex, 0);
        int numColours = colours.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;

        if (colour == 1) {
            for (int s = lastStart; s >= 0; s--) {
                if (fits(links, coreIndex, s, numSlots)) return new SpectrumInterval(s, s + numSlots - 1);
            }
            return null;
        }

        int first = colour == 0 ? 0 : Math.min(lastStart, totalSlots * (colour - 1) / (numColours - 1));
        for (int i = 0; i <= lastStart; i++) {
            int s = (first + i) % (lastStart + 1);
            if (fits(links, coreIndex, s, numSlots)) return new SpectrumInterval(s, s + numSlots - 1);
        }
        return null;
    }

    private static boolean fits(List<Link> links, int coreIndex, int start, int numSlots) {
        for (Link link : links) {
            if (!link.getCore(coreIndex).getSpectrum().isRangeFree(start, start + numSlots - 1)) return false;
        }
        return true;
    }
}
