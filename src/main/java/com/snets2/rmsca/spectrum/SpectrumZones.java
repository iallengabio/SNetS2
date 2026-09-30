package com.snets2.rmsca.spectrum;

import com.snets2.model.Link;
import java.util.ArrayList;
import java.util.List;

/**
 * Prioritized spectrum zones per group of cores (Fujii et al., JOCN 6(12), 2014), as in the {@code cpcas},
 * {@code rccas} and {@code icxtaa} algorithms of SNetS v1.
 *
 * <p>The cores are grouped by colour (adjacent cores never share a colour). With {@code k} colours the grid of
 * {@code N} slots is split into {@code k} consecutive zones; zone {@code q} is the priority zone of colour {@code q}.
 * A core of colour {@code q} searches its own zone first and then the zones {@code q+1, q+2, ...} (cyclically).
 * With 3 colours the zones keep the proportions of v1 (slots 1-137, 138-274 and 275-320 of 320: 137/320, 137/320
 * and 46/320 of the grid, the last one for the central core of the 7-core MCF); otherwise the zones are equal.</p>
 */
public final class SpectrumZones {

    /** Zone boundaries of v1 for 3 groups, as fractions of the grid: {@code [0, 137/320, 274/320, 1]}. */
    private static final double[] V1_BOUNDS = {0, 137.0 / 320, 274.0 / 320, 1};

    private SpectrumZones() {}

    /** The {@code numColours} zones of a grid of {@code totalSlots} slots, in slot order. */
    public static List<SpectrumInterval> zones(int totalSlots, int numColours) {
        int k = Math.max(1, numColours);
        List<SpectrumInterval> zones = new ArrayList<>(k);
        for (int q = 0; q < k; q++) {
            int start = k == 3 ? (int) Math.floor(totalSlots * V1_BOUNDS[q]) : totalSlots * q / k;
            int end = (k == 3 ? (int) Math.floor(totalSlots * V1_BOUNDS[q + 1]) : totalSlots * (q + 1) / k) - 1;
            zones.add(new SpectrumInterval(start, end));
        }
        return zones;
    }

    /** Zones in the search order of a core of colour {@code colour}: its own zone, then the next ones cyclically. */
    public static List<SpectrumInterval> searchOrder(int totalSlots, int numColours, int colour) {
        List<SpectrumInterval> zones = zones(totalSlots, numColours);
        List<SpectrumInterval> order = new ArrayList<>(zones.size());
        for (int i = 0; i < zones.size(); i++) order.add(zones.get((colour + i) % zones.size()));
        return order;
    }

    /** First-Fit restricted to a zone: the lowest interval entirely inside {@code zone}, or {@code null}. */
    public static SpectrumInterval firstFitInZone(List<Link> links, int core, int numSlots, SpectrumInterval zone) {
        for (int s = zone.start(); s + numSlots - 1 <= zone.end(); s++) {
            if (SpectrumSearch.fits(links, core, s, numSlots)) return new SpectrumInterval(s, s + numSlots - 1);
        }
        return null;
    }

    /** First-Fit in the zones of {@code colour}, in search order: the interval of v1 for that core, or {@code null}. */
    public static SpectrumInterval zonedFirstFit(List<Link> links, int core, int colour, int numColours, int numSlots) {
        if (links.isEmpty() || numSlots <= 0) return null;
        int totalSlots = links.get(0).getCore(core).getSpectrum().getNumSlots();
        for (SpectrumInterval zone : searchOrder(totalSlots, numColours, colour)) {
            SpectrumInterval found = firstFitInZone(links, core, numSlots, zone);
            if (found != null) return found;
        }
        return null;
    }
}
