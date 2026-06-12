package com.snets2.model;

import com.snets2.metrics.BlockingCause;
import java.util.List;

/**
 * Represents the result of a resource allocation attempt by the RMSCA algorithm.
 * 
 * <p>This object contains either the parameters of a successful resource allocation 
 * or the information about a blocked connection request, including the specific blocking cause.</p>
 */
public record AllocationResult(
    Node source,
    Node destination,
    double bitRate,
    boolean isBlocked,
    BlockingCause blockingCause,
    Integer blockingCoreId,
    List<Link> path,
    List<Integer> coreIndices,
    int startSlot,
    int endSlot,
    ModulationFormat modulation,
    List<Node> regeneratorNodes
) {
    /**
     * Constructor for a successful allocation with regenerators.
     */
    public AllocationResult(Node source, Node destination, List<Link> path, List<Integer> coreIndices, 
                            int startSlot, int endSlot, ModulationFormat modulation, double bitRate, List<Node> regeneratorNodes) {
        this(source, destination, bitRate, false, null, null, path, coreIndices, startSlot, endSlot, modulation, regeneratorNodes);
    }

    /**
     * Constructor for a successful allocation without regenerators.
     */
    public AllocationResult(Node source, Node destination, List<Link> path, List<Integer> coreIndices, 
                            int startSlot, int endSlot, ModulationFormat modulation, double bitRate) {
        this(source, destination, path, coreIndices, startSlot, endSlot, modulation, bitRate, List.of());
    }

    /**
     * Constructor for a blocked allocation with a core ID.
     */
    public AllocationResult(Node source, Node destination, double bitRate, BlockingCause blockingCause, Integer blockingCoreId) {
        this(source, destination, bitRate, true, blockingCause, blockingCoreId, null, null, -1, -1, null, List.of());
    }

    /**
     * Constructor for a blocked allocation without a core ID.
     */
    public AllocationResult(Node source, Node destination, double bitRate, BlockingCause blockingCause) {
        this(source, destination, bitRate, blockingCause, null);
    }

    /**
     * Converts this successful result into a persistent {@link Circuit} object.
     *
     * @param id The unique identifier for the circuit.
     * @return A new Circuit instance.
     */
    public Circuit toCircuit(String id) {
        if (isBlocked) {
            throw new IllegalStateException("Cannot convert a blocked AllocationResult to a Circuit.");
        }
        return new Circuit(id, source, destination, path, coreIndices, startSlot, endSlot, modulation, bitRate, regeneratorNodes);
    }
}
