package com.snets2.model;

import java.util.List;

/**
 * Represents a proposed allocation of resources for a new connection.
 * 
 * <p>This object is returned by RMSCA algorithms when a valid path, core, 
 * and spectrum range are found. It acts as a template for creating a {@link Circuit}.</p>
 */
public record AllocationSolution(
    Node source,
    Node destination,
    List<Link> path,
    List<Integer> coreIndices,
    int startSlot,
    int endSlot,
    ModulationFormat modulation,
    double bitRate
) {
    /**
     * Converts this solution into a persistent {@link Circuit} object.
     *
     * @param id The unique identifier for the circuit.
     * @return A new Circuit instance.
     */
    public Circuit toCircuit(String id) {
        return new Circuit(id, source, destination, path, coreIndices, startSlot, endSlot, modulation, bitRate);
    }
}
