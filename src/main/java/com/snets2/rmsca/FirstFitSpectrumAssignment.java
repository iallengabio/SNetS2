package com.snets2.rmsca;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import java.util.List;

/**
 * Spectrum assignment using the First Fit (FF) policy.
 * It searches for the first contiguous block of slots that is free in all links of the path.
 */
public class FirstFitSpectrumAssignment implements ISpectrumAssignment {

    @Override
    public SpectrumInterval findSlots(ControlPlane cp, Path path, int coreIndex, int numSlots) {
        List<Link> links = path.links();
        if (links.isEmpty()) return null;

        // Assumes all cores have the same number of slots
        int totalSlots = links.get(0).getCore(coreIndex).getSpectrum().getNumSlots();

        for (int s = 0; s <= totalSlots - numSlots; s++) {
            boolean fits = true;
            for (Link link : links) {
                if (!link.getCore(coreIndex).getSpectrum().isRangeFree(s, s + numSlots - 1)) {
                    fits = false;
                    break;
                }
            }

            if (fits) {
                return new SpectrumInterval(s, s + numSlots - 1);
            }
        }

        return null;
    }
}
