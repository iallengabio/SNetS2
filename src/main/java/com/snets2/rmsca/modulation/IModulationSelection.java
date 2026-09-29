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
     * ({@code path length <= maxReach}) on transparent candidates when {@link #enforcesReach} is true.
     */
    List<ModulationFormat> candidateFormats(ControlPlane cp, Path path, double bitRate);

    /**
     * Whether the RMSCA (and the regenerator assignment) must discard formats whose {@code maxReach} does
     * not cover the path or the transparent segment. Policies driven by the physical model return false:
     * the format is then accepted or rejected only by the QoT validation of the RMSCA.
     */
    default boolean enforcesReach(ControlPlane cp) {
        return true;
    }
}
