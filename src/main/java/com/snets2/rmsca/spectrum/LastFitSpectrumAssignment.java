package com.snets2.rmsca.spectrum;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import java.util.List;

/**
 * Spectrum assignment using the Last Fit (LF) policy.
 * It searches for a contiguous block of slots starting from the end of the spectrum.
 */
public class LastFitSpectrumAssignment implements ISpectrumAssignment {

    @Override
    public SpectrumInterval findSlots(ControlPlane cp, Path path, int coreIndex, int numSlots) {
        List<Link> links = path.links();
        if (links.isEmpty()) return null;

        int totalSlots = links.get(0).getCore(coreIndex).getSpectrum().getNumSlots();

        for (int s = totalSlots - numSlots; s >= 0; s--) {
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
