package com.snets2.rmsca.modulation;

import com.snets2.model.ControlPlane;
import com.snets2.rmsca.routing.Path;

/** Interface for Modulation Selection algorithms. */
public interface IModulationSelection {
    /**
     * Chooses the best modulation format for a given path and bit rate.
     */
    ModulationResult selectModulation(ControlPlane cp, Path path, double bitRate);
}
