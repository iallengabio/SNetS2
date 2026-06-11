package com.snets2.rmsca.regenerator;

import com.snets2.model.*;
import com.snets2.rmsca.routing.Path;
import java.util.List;

/**
 * Interface for regenerator assignment algorithms.
 */
public interface IRegeneratorAssignment {
    /**
     * Determines where to place regenerators along the given path for a connection request.
     * 
     * @param cp            The control plane.
     * @param path          The routing path.
     * @param coreId        The chosen core index.
     * @param mod           The chosen modulation format.
     * @param startSlot     The starting slot index.
     * @param endSlot       The ending slot index.
     * @param bitRate       The bit rate in Gbps.
     * @return A list of nodes where regenerators should be allocated, or null if allocation fails.
     */
    List<Node> assignRegenerators(ControlPlane cp, Path path, int coreId, ModulationFormat mod, int startSlot, int endSlot, double bitRate);
}
