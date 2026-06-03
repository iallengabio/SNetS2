package com.snets2.rmsca.spectrum;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import java.util.List;

/**
 * Spectrum assignment using the Dummy Fit policy.
 * It only checks if the required slots are available starting from slot 0.
 * If not, it fails.
 */
public class DummyFitSpectrumAssignment implements ISpectrumAssignment {

    @Override
    public SpectrumInterval findSlots(ControlPlane cp, Path path, int coreIndex, int numSlots) {
        List<Link> links = path.links();
        if (links.isEmpty()) return null;

        // Check if there are enough slots in the spectrum at all
        int totalSlots = links.get(0).getCore(coreIndex).getSpectrum().getNumSlots();
        if (numSlots > totalSlots) return null;

        // Check only the first possible interval [0, numSlots - 1]
        boolean fits = true;
        for (Link link : links) {
            if (!link.getCore(coreIndex).getSpectrum().isRangeFree(0, numSlots - 1)) {
                fits = false;
                break;
            }
        }

        if (fits) {
            return new SpectrumInterval(0, numSlots - 1);
        }

        return null;
    }
}
