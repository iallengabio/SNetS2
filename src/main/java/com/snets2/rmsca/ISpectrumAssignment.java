package com.snets2.rmsca;

import com.snets2.model.ControlPlane;

/** Interface for Spectrum Assignment algorithms. */
public interface ISpectrumAssignment {
    /**
     * Finds a free contiguous interval of slots on the given path and core.
     */
    SpectrumInterval findSlots(ControlPlane cp, Path path, int coreIndex, int numSlots);
}
