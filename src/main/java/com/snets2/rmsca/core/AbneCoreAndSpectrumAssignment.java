package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import com.snets2.rmsca.spectrum.SpectrumSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ABNE, core and spectrum balancing for SDM networks (port of {@code CSBASDM} of SNetS v1, id {@code csbasdm}).
 *
 * <ul>
 *   <li><b>Core:</b> round-robin over the cores of the path, one core per call (the counter advances at every call,
 *       i.e. at every path and format tried by the RMSCA, as in v1). {@code abne} proposes only that core: if it has no
 *       room the attempt fails, even if other cores are free.</li>
 *   <li><b>Spectrum</b>, by the colour of the core ({@link PeripheralFirstCoreAssignment#coreColours}, adjacent cores never
 *       share a colour): colour 0 First-Fit, colour 1 Last-Fit, colour {@code >= 2} medium fit (start closest to the middle
 *       of the grid). In the 7-core hexagonal MCF this is the rule of v1: odd outer cores First-Fit, even outer cores
 *       Last-Fit, central core medium fit. v1 uses the parity of the core id and a fixed central core (0, or 18 with 19
 *       cores), which only matches the adjacency of the 7-core fibre; the colouring generalizes it.</li>
 * </ul>
 *
 * <p>Variants: {@code abne2} ({@code CSBASDM2} of v1) uses the central cores (colour {@code >= 2}) only once every
 * {@value #CENTRAL_CORE_PERIOD} rounds of the rotation. {@code abne-fallback} (extension, not in v1) proposes the
 * round-robin core first and then the other cores in rotation order, each with its own spectrum policy.</p>
 *
 * <p>Reference: J. C. Lacerda Jr., A. G. Morais, A. V. T. Cartaxo, A. Soares, "A New Algorithm to Mitigate
 * Fragmentation and Crosstalk in Multi-Core Elastic Optical Networks", Photonics 11(6):504, 2024 (describes ABNE, the
 * earlier algorithm of the same authors).</p>
 */
public class AbneCoreAndSpectrumAssignment implements ICoreAndSpectrumAssignment {

    /** {@code abne2}: rounds of the rotation per use of the central cores ({@code QUANTCENTRALCORE + 1} in v1). */
    public static final int CENTRAL_CORE_PERIOD = 6;

    public enum Variant { ABNE, ABNE2, FALLBACK }

    private final Variant variant;
    private int next = 0;       // position of the next core in the rotation
    private int round = 0;      // completed rounds (abne2)

    public AbneCoreAndSpectrumAssignment() {
        this(Variant.ABNE);
    }

    public AbneCoreAndSpectrumAssignment(Variant variant) {
        this.variant = variant;
    }

    /** {@code abne2}. */
    public static class Abne2 extends AbneCoreAndSpectrumAssignment {
        public Abne2() { super(Variant.ABNE2); }
    }

    /** {@code abne-fallback}. */
    public static class Fallback extends AbneCoreAndSpectrumAssignment {
        public Fallback() { super(Variant.FALLBACK); }
    }

    @Override
    public Iterable<CoreSpectrumCandidate> candidates(ControlPlane cp, Path path, int numSlots) {
        List<Integer> cores = PeripheralFirstCoreAssignment.commonCores(path);
        if (cores.isEmpty()) return List.of();
        Map<Integer, Integer> colours = PeripheralFirstCoreAssignment.coreColours(path);
        int first = nextCore(cores, colours);

        List<Integer> order = new ArrayList<>();
        order.add(cores.get(first));
        if (variant == Variant.FALLBACK) {
            for (int i = 1; i < cores.size(); i++) order.add(cores.get((first + i) % cores.size()));
        }
        List<CoreSpectrumCandidate> candidates = new ArrayList<>(order.size());
        for (int core : order) {
            candidates.add(new CoreSpectrumCandidate(core, assignSpectrum(path.links(), core, colours.getOrDefault(core, 0), numSlots)));
        }
        return candidates;
    }

    /** Index in {@code cores} of the round-robin core of this call, advancing the rotation. */
    private int nextCore(List<Integer> cores, Map<Integer, Integer> colours) {
        while (true) {
            if (next >= cores.size()) { // end of a round
                next = 0;
                round++;
            }
            int index = next++;
            boolean central = colours.getOrDefault(cores.get(index), 0) >= 2;
            if (variant == Variant.ABNE2 && central && round % CENTRAL_CORE_PERIOD != CENTRAL_CORE_PERIOD - 1) continue;
            return index;
        }
    }

    /** Spectrum policy of a core by its colour: 0 First-Fit, 1 Last-Fit, {@code >= 2} medium fit. */
    static SpectrumInterval assignSpectrum(List<Link> links, int core, int colour, int numSlots) {
        return switch (colour) {
            case 0 -> SpectrumSearch.firstFit(links, core, numSlots);
            case 1 -> SpectrumSearch.lastFit(links, core, numSlots);
            default -> SpectrumSearch.mediumFit(links, core, numSlots);
        };
    }
}
