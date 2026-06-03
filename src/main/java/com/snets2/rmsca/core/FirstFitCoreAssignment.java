package com.snets2.rmsca.core;

import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.rmsca.routing.Path;

/**
 * Core assignment using the First Fit policy.
 * It selects the first core ID that exists in all links of the path.
 */
public class FirstFitCoreAssignment implements ICoreAssignment {

    @Override
    public Integer selectCore(ControlPlane cp, Path path) {
        if (path.links().isEmpty()) return null;
        
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
