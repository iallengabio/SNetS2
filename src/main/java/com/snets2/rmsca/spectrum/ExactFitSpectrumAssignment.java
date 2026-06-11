package com.snets2.rmsca.spectrum;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;
import java.util.List;

/**
 * Spectrum assignment using the Exact Fit (EF) policy (also known as Best Fit).
 * It selects the contiguous free block that is closest in size to the requested number of slots.
 */
public class ExactFitSpectrumAssignment implements ISpectrumAssignment {

    @Override
    public SpectrumInterval findSlots(ControlPlane cp, Path path, int coreIndex, int numSlots) {
        List<Link> links = path.links();
        if (links.isEmpty()) return null;

        int totalSlots = links.get(0).getCore(coreIndex).getSpectrum().getNumSlots();

        int bestStart = -1;
        int minBlockSize = Integer.MAX_VALUE;

        int blockStart = -1;
        for (int s = 0; s < totalSlots; s++) {
            boolean slotFree = true;
            for (Link link : links) {
                if (!link.getCore(coreIndex).getSpectrum().isFree(s)) {
                    slotFree = false;
                    break;
                }
            }

            if (slotFree) {
                if (blockStart == -1) {
                    blockStart = s;
                }
            } else {
                if (blockStart != -1) {
                    int blockSize = s - blockStart;
                    if (blockSize >= numSlots && blockSize < minBlockSize) {
                        minBlockSize = blockSize;
                        bestStart = blockStart;
                    }
                    blockStart = -1;
                }
            }
        }

        // Handle the last block if it goes up to the end of the spectrum
        if (blockStart != -1) {
            int blockSize = totalSlots - blockStart;
            if (blockSize >= numSlots && blockSize < minBlockSize) {
                minBlockSize = blockSize;
                bestStart = blockStart;
            }
        }

        if (bestStart != -1) {
            return new SpectrumInterval(bestStart, bestStart + numSlots - 1);
        }

        return null;
    }
}
