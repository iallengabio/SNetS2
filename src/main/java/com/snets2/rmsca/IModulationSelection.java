package com.snets2.rmsca;

/** Interface for Modulation Selection algorithms. */
public interface IModulationSelection {
    /**
     * Chooses the best modulation format for a given path and bit rate.
     */
    ModulationResult selectModulation(Path path, double bitRate);
}
