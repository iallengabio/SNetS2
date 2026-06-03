package com.snets2.rmsca.spectrum;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Spectrum assignment using the Random Fit (RF) policy.
 * It finds all available contiguous blocks of slots and picks one at random.
 */
public class RandomFitSpectrumAssignment implements ISpectrumAssignment {
    private final Random random = new Random();

    @Override
    public SpectrumInterval findSlots(ControlPlane cp, Path path, int coreIndex, int numSlots) {
        List<Link> links = path.links();
        if (links.isEmpty()) return null;

        int totalSlots = links.get(0).getCore(coreIndex).getSpectrum().getNumSlots();
        List<SpectrumInterval> candidates = new ArrayList<>();

        for (int s = 0; s <= totalSlots - numSlots; s++) {
            boolean fits = true;
            for (Link link : links) {
                if (!link.getCore(coreIndex).getSpectrum().isRangeFree(s, s + numSlots - 1)) {
                    fits = false;
                    break;
                }
            }

            if (fits) {
                candidates.add(new SpectrumInterval(s, s + numSlots - 1));
            }
        }

        if (candidates.isEmpty()) return null;

        return candidates.get(random.nextInt(candidates.size()));
    }
}
