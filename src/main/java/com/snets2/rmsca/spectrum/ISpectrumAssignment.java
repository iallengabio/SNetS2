package com.snets2.rmsca.spectrum;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.routing.Path;

/** Interface for Spectrum Assignment algorithms. */
public interface ISpectrumAssignment {
    /**
     * Finds a free contiguous interval of slots on the given path and core.
     */
    SpectrumInterval findSlots(ControlPlane cp, Path path, int coreIndex, int numSlots);
}
