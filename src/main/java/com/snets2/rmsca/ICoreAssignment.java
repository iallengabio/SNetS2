package com.snets2.rmsca;

import com.snets2.model.ControlPlane;

/** Interface for Core Assignment algorithms. */
public interface ICoreAssignment {
    /**
     * Selects a core for the given path.
     * For basic implementations, it returns the same core index for all links in the path.
     */
    Integer selectCore(ControlPlane cp, Path path);
}
