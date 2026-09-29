package com.snets2.rmsca.spectrum;

import com.snets2.model.Link;
import java.util.List;

/**
 * Searches for a free interval of {@code numSlots} slots in one core, with slot continuity along the path (the same
 * slots free on every link). Used by the joint core and spectrum assignments.
 */
public final class SpectrumSearch {

    private SpectrumSearch() {}

    /** Whether slots {@code start .. start + numSlots - 1} of {@code core} are free on every link. */
    public static boolean fits(List<Link> links, int core, int start, int numSlots) {
        for (Link link : links) {
            if (!link.getCore(core).getSpectrum().isRangeFree(start, start + numSlots - 1)) return false;
        }
        return true;
    }

    /** Last valid start slot, or -1 if the demand is larger than the grid. */
    private static int lastStart(List<Link> links, int core, int numSlots) {
        if (links.isEmpty() || numSlots <= 0) return -1;
        return links.get(0).getCore(core).getSpectrum().getNumSlots() - numSlots;
    }

    /** First-Fit: the lowest free interval, or {@code null}. */
    public static SpectrumInterval firstFit(List<Link> links, int core, int numSlots) {
        int last = lastStart(links, core, numSlots);
        for (int s = 0; s <= last; s++) {
            if (fits(links, core, s, numSlots)) return new SpectrumInterval(s, s + numSlots - 1);
        }
        return null;
    }

    /** Last-Fit: the highest free interval, or {@code null}. */
    public static SpectrumInterval lastFit(List<Link> links, int core, int numSlots) {
        for (int s = lastStart(links, core, numSlots); s >= 0; s--) {
            if (fits(links, core, s, numSlots)) return new SpectrumInterval(s, s + numSlots - 1);
        }
        return null;
    }

    /**
     * Medium fit of SNetS v1 ({@code CSBASDM.policy}): the free interval whose <b>first</b> slot is closest to the middle
     * slot {@code floor(N / 2)} of the grid; ties go to the lowest start. Returns {@code null} if none.
     */
    public static SpectrumInterval mediumFit(List<Link> links, int core, int numSlots) {
        int last = lastStart(links, core, numSlots);
        if (last < 0) return null;
        int reference = links.get(0).getCore(core).getSpectrum().getNumSlots() / 2;
        SpectrumInterval best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int s = 0; s <= last; s++) {
            int distance = Math.abs(reference - s);
            if (distance < bestDistance && fits(links, core, s, numSlots)) {
                best = new SpectrumInterval(s, s + numSlots - 1);
                bestDistance = distance;
            }
        }
        return best;
    }
}
