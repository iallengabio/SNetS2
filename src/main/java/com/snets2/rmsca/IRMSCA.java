package com.snets2.rmsca;

import com.snets2.model.AllocationSolution;
import com.snets2.model.ControlPlane;
import com.snets2.model.Node;

/**
 * Base interface for Routing, Modulation, Spectrum, and Core Allocation (RMSCA) algorithms.
 */
public interface IRMSCA {
    /**
     * Attempts to find a resource allocation for a new connection request.
     *
     * @param cp          The Control Plane providing network state.
     * @param source      The source node of the request.
     * @param destination The destination node of the request.
     * @param bitRate     The requested bit rate in Gbps.
     * @return An {@link AllocationSolution} if resources are available, null otherwise.
     */
    AllocationSolution allocate(ControlPlane cp, Node source, Node destination, double bitRate);
}
