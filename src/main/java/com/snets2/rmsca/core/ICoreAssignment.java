package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.routing.Path;
import java.util.List;

/** Interface for Core Assignment algorithms. */
public interface ICoreAssignment {
    /**
     * Selects candidate cores for the given path, in priority order.
     */
    List<Integer> selectCores(ControlPlane cp, Path path);
}
