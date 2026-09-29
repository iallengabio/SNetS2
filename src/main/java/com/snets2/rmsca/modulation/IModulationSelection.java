package com.snets2.rmsca.modulation;

import com.snets2.model.ControlPlane;
import com.snets2.model.ModulationFormat;
import com.snets2.rmsca.routing.Path;
import java.util.List;

/** Interface for Modulation Selection algorithms. */
public interface IModulationSelection {
    /**
     * Chooses the best modulation format for a given path and bit rate.
     */
    ModulationResult selectModulation(ControlPlane cp, Path path, double bitRate);

    /**
     * Ordered list of modulation formats the integrated RMSCA is allowed to try for this path, from
     * the most preferred to the least preferred. The RMSCA still enforces the reach constraint
     * ({@code path length <= maxReach}) unless a regenerator assignment is configured.
     */
    List<ModulationFormat> candidateFormats(ControlPlane cp, Path path, double bitRate);
}
