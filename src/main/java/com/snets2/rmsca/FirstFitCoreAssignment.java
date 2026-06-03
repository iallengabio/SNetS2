package com.snets2.rmsca;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;

/**
 * Core assignment using the First Fit policy.
 * It selects the first core ID that exists in all links of the path.
 */
public class FirstFitCoreAssignment implements ICoreAssignment {

    @Override
    public Integer selectCore(ControlPlane cp, Path path) {
        if (path.links().isEmpty()) return null;
        
        // Simple implementation: check core IDs available in the first link
        // and verify if they are available in the rest.
        // Usually core IDs are consistent across the network.
        Link firstLink = path.links().get(0);
        
        for (Integer coreId : firstLink.getCores().keySet()) {
            boolean allHaveIt = true;
            for (Link link : path.links()) {
                if (!link.getCores().containsKey(coreId)) {
                    allHaveIt = false;
                    break;
                }
            }
            if (allHaveIt) return coreId;
        }
        
        return null;
    }
}
